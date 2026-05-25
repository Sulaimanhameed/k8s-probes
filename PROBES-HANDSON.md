# Kubernetes Probes — A Hands-On Guide

A practical walk-through of how Kubernetes checks the health of your app, and how to wire it up the right way. Written for people who learn by doing.

We will use the Java/Spring Boot app and `k8s/app.yaml` from this repo as the running example, but the ideas apply to any language. The code samples in Section 2 are written in Go so you can see what a clean implementation looks like from scratch.

---

## Table of Contents

1. The three probe types (what they do and when they run)
2. Application side — what `/livez`, `/readyz`, and `/startupz` should actually check
3. Kubernetes side — how the kubelet runs probes under the hood
4. Try it yourself

---

## 1. The Three Probe Types

Kubernetes cannot read your app's mind. It does not know if the JVM is still warming up, if your database connection dropped, or if a background thread is stuck in an infinite loop. So it asks your app, over and over, by calling a few small HTTP endpoints you expose.

There are three of these checks. They look similar but they answer very different questions, and they cause very different things to happen.

Before we go into each probe, here are the knobs every probe shares. You will see these in every YAML example below, so it helps to know what they actually do.

| Field                  | What it controls                                                                                       | Default | Notes                                                                                  |
|------------------------|--------------------------------------------------------------------------------------------------------|---------|----------------------------------------------------------------------------------------|
| `initialDelaySeconds`  | How long the kubelet waits after the container starts before the first probe runs.                    | 0       | Counted from container start, not pod start.                                           |
| `periodSeconds`        | How often the probe runs after the first one.                                                          | 10      | Minimum 1.                                                                              |
| `timeoutSeconds`       | How long the kubelet waits for a single probe response before counting it as a failure.                | 1       | A timeout counts as a failure, not a separate event.                                   |
| `failureThreshold`     | Consecutive failures needed before Kubernetes reacts (restart, remove from Service, give up on startup). | 3      | Resets to 0 the moment one success comes in.                                           |
| `successThreshold`     | Consecutive successes needed before the probe is considered passing again.                              | 1       | Must be `1` for liveness and startup. Only readiness can be higher.                    |

You also pick one **handler type** per probe — the actual mechanism the kubelet uses to check your app:

- **`httpGet`** — kubelet makes an HTTP GET to your pod IP on the configured port and path. Success is any status code from 200 through 399 inclusive. Anything else, or a connection error, or a timeout, is a failure. Each probe opens a fresh TCP connection. There is no HTTP keep-alive between probes.
- **`tcpSocket`** — kubelet just opens a TCP connection. If the handshake completes, success. If the connection is refused or times out, failure. No bytes are exchanged.
- **`exec`** — kubelet asks the container runtime to run a command inside the container. Exit code 0 is success, anything else is failure. This is the slowest option because it spawns a process every period.
- **`grpc`** — kubelet calls the [standard gRPC health checking protocol](https://github.com/grpc/grpc/blob/master/doc/health-checking.md) on your service. Response status `SERVING` is success.

A couple of details that bite people:

- For `httpGet`, redirects are followed for one hop only. If your app returns a 301/302, the kubelet treats the 3xx itself as success and does not follow it further.
- You can pass custom headers in `httpGet.httpHeaders` — useful for `Host:` overrides, or an `Authorization:` header on a probe endpoint that requires auth.
- A probe failure due to timeout is identical to a probe failure due to a 500 response, as far as the failure counter is concerned. You cannot tell them apart from the YAML side; you have to look at the kubelet's pod events.

Now to the three probes themselves.

### Startup probe — "Are you done booting yet?"

This one runs first, and only at the beginning. While it is running, the kubelet **suspends** the liveness and readiness probes for that container — they are configured but not executed. The first success flips a one-way switch: the kubelet stops calling startup forever, and the other two probes begin running on their own schedules.

It exists because some apps take a while to start. A Spring Boot app on the JVM usually needs 30 to 60 seconds. A Rails app loading a big initializer chain is similar. Without a startup probe, the liveness probe fires too early, decides the app is broken, and restarts the container — you get a crash loop on a perfectly healthy app.

**The math you need to know.** The maximum startup budget is `initialDelaySeconds + (failureThreshold × periodSeconds)`. After that, the kubelet gives up, marks the container as failed, and the pod's restart policy kicks in. In the YAML in this repo:

```yaml
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 30
  successThreshold: 1
```

That gives the app `30 + (30 × 10) = 330 seconds` — five and a half minutes — to return a single 2xx. After that, the container is restarted and the whole startup countdown starts over. The `RestartCount` in `kubectl describe pod` ticks up.

**Important constraint:** `successThreshold` must be `1` for startup probes. The API will reject any other value. The first success ends startup, period.

**How the kubelet gates the other probes.** Internally, the prober manager keeps a `startup` result in its cache. As long as that result is not `Success`, the workers for liveness and readiness simply skip their iterations — they do not call your endpoints at all. This is why you can set aggressive liveness thresholds without worrying about boot time: the kubelet literally will not run them yet.

### Liveness probe — "Are you stuck?"

Liveness asks one question: is the process responsive? Not "is everything in the system working", just "are you alive". If it fails `failureThreshold` times in a row, the kubelet asks the container runtime to send SIGTERM to PID 1 in the container, waits up to `terminationGracePeriodSeconds`, then sends SIGKILL. The container exits, the pod's `RestartCount` increments, and the runtime starts a new container in the same pod.

The reason this matters is that processes can be running but useless. A deadlock holds two threads forever. A memory leak slows everything to a crawl. An infinite loop pegs CPU and never returns. The process is technically alive, but it cannot do its job. A restart is the only way out.

**What "failure" means here, exactly.** Any of these counts as a single failed probe:

- HTTP status outside 200–399.
- A connection refused, connection reset, or DNS failure to the pod IP.
- A response that does not return within `timeoutSeconds`.

These all increment the same counter. One success in between resets the counter to 0. You need `failureThreshold` consecutive failures with no successes in between for the kubelet to act.

**Per-probe termination grace period (Kubernetes 1.27+).** A normal liveness restart uses the pod's `terminationGracePeriodSeconds`, which can be 30 or 60 seconds. If your app is truly stuck, you may not want to wait that long. You can override it on the probe itself:

```yaml
livenessProbe:
  httpGet:
    path: /livez
    port: 8080
  failureThreshold: 3
  periodSeconds: 10
  terminationGracePeriodSeconds: 5   # kill faster on liveness failure
```

This applies only to restarts triggered by this probe. A normal pod delete still uses the pod-level grace period.

**Liveness does not affect Service endpoints.** It is purely about restarts. If your app fails liveness once but is still in the failure threshold window, it can still be in the Service's endpoint list and receive traffic. That is intentional — failures are common and transient, and pulling endpoints on every blip would be chaos.

**The restart cycle is throttled.** After a few crash-restart cycles in a row, the kubelet applies exponential backoff: 10s, 20s, 40s, up to 5 minutes. You will see this as `CrashLoopBackOff` in `kubectl get pods`. The backoff resets after the container has been running successfully for 10 minutes.

### Readiness probe — "Are you ready to take traffic right now?"

This one is about routing, not restarts. When readiness fails `failureThreshold` times, the kubelet updates the **pod's `Ready` condition** to `False`. The pod stays running. The endpoint controller (or the EndpointSlice controller, in modern clusters) watches that condition and **removes the pod's IP from the corresponding EndpointSlice**. kube-proxy on every node sees the EndpointSlice update and stops routing Service traffic to that pod. When readiness flips back to passing, the IP goes back into the EndpointSlice and traffic resumes.

The whole cycle — failure detected on the node, condition updated in the API server, EndpointSlice rewritten, kube-proxy rules updated on every node — usually completes in a few seconds, but it is not instant. Plan for it. A pod that "just failed readiness" may still receive a few more requests before kube-proxy on the other nodes catches up.

**Readiness is where you check the things you depend on.** Database connection. Cache. Downstream API. If your database is down for thirty seconds, you do not want Kubernetes to restart your app — restarting will not bring the database back. You just want to stop sending traffic until the database is healthy again.

**`successThreshold` matters here.** Liveness and startup are stuck at `1`, but for readiness you can require multiple consecutive successes before traffic resumes. This is useful when your dependency check is flappy:

```yaml
readinessProbe:
  httpGet:
    path: /readyz
    port: 8080
  failureThreshold: 3
  successThreshold: 2   # need 2 in a row before traffic comes back
  periodSeconds: 5
```

**Readiness gates — extra conditions outside the probe.** If you need readiness to depend on something the kubelet cannot check (a controller has finished provisioning a sidecar, a service mesh has accepted the pod, etc.), use [readiness gates](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-readiness-gate). The pod is only considered `Ready` when all its readiness probes pass **and** all its readiness gates are set to `True` by an external controller. The probe and the gates are ANDed together.

**Pod readiness vs container readiness.** The `Ready` column in `kubectl get pods` (the `1/1` you stare at all day) is the AND of every container's readiness in the pod plus every readiness gate. One container failing readiness flips the whole pod to `0/1` and removes it from every Service.

**One subtle behavior.** Readiness keeps running for the entire life of the container — there is no "passed once, done" like startup. If your app deadlocks but does not crash, readiness will eventually start failing, traffic will drain, and the pod will sit there idle. Liveness is what eventually kills it. The two work as a pair: readiness drains traffic gracefully, liveness restarts the broken container.

### How the fields interact — a worked example

Take the readiness probe from `k8s/app.yaml`:

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 0
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 3
  successThreshold: 1
```

Translated:

- First call goes out the moment the startup probe passes (because `initialDelaySeconds: 0` and readiness is gated by startup here).
- Then a call every 5 seconds, from the kubelet on the node, to `http://<pod-ip>:8080/actuator/health/readiness`.
- The kubelet waits at most 3 seconds for a response. A timeout counts as a failure.
- After 3 consecutive failures (about 15 seconds of bad responses), the pod's `Ready` condition flips to `False`, the pod is dropped from the Service's EndpointSlice, kube-proxy stops sending it traffic.
- The next successful response (only 1 needed) flips it back. Traffic resumes within seconds.

Knowing this math lets you tune probes consciously. If you want to drain traffic within 5 seconds of a failure, you need `periodSeconds × failureThreshold ≤ 5`. If you want a buffer against transient blips, you go the other way.

### How they fit together

```
                ┌──────────────────────────────────────────┐
                │              Container starts             │
                └──────────────────────┬───────────────────┘
                                       │
                                       ▼
                ┌──────────────────────────────────────────┐
                │  STARTUP PROBE running                    │
                │  /startupz                                │
                │                                           │
                │  Liveness + Readiness are paused here.    │
                │  Fails for too long → restart container.  │
                └──────────────────────┬───────────────────┘
                                       │ first success
                                       ▼
            ┌──────────────────────────┴──────────────────────┐
            │                                                  │
            ▼                                                  ▼
┌─────────────────────────┐                  ┌─────────────────────────┐
│  LIVENESS PROBE          │                 │  READINESS PROBE         │
│  /livez                  │                 │  /readyz                 │
│                          │                 │                          │
│  "Am I stuck?"           │                 │  "Can I serve traffic?"  │
│                          │                 │                          │
│  Fail → RESTART pod      │                 │  Fail → remove from      │
│                          │                 │         Service          │
│  No traffic effect.      │                 │  No restart.             │
└──────────────────────────┘                 └──────────────────────────┘
```

Read that diagram a couple of times. The most common probe bug in production comes from confusing the right column with the left.

---

## 2. Application Side — What Each Endpoint Should Check

This is where most teams get into trouble. The endpoints all look similar — they all return 200 or not-200 — but what you check inside each one is very different.

Here is the rule of thumb, with examples.

### `/startupz` — Has my one-time init finished?

Things like: config loaded, migrations applied, caches warmed, HTTP server actually listening. After the first success, this endpoint is never called again, so it is fine to do slightly more work here than you would in liveness.

A real example: an app that loads a 200 MB ML model into memory before serving requests. `/startupz` should return 200 only once the model is loaded.

### `/livez` — Am I alive enough to be worth keeping?

Things like: the event loop is responsive, the HTTP handler can return a response, an internal heartbeat ticked recently.

**Do not check your database here.** Do not check Redis. Do not check the downstream payment API. If the database is down and your liveness probe fails because of it, Kubernetes will restart your pod over and over while the database stays down. Restarts will not fix the database. They will just make your logs noisier and your incident more painful.

Liveness should only check things that a restart would actually fix.

### `/readyz` — Can I serve a real request right now?

This is where the dependency checks belong. Can I reach the database? Is my connection pool not exhausted? Is the cache responding? Did I finish loading the data I need to handle requests?

If any of those answers is no, return 503. Kubernetes will pull you out of the Service. When the answer flips back to yes, return 200, and traffic starts flowing again.

### A clean Go example

This is what a healthy implementation looks like in Go. The same shape works in any language — Spring Boot does the same thing under `/actuator/health/liveness` and `/actuator/health/readiness`, just with more annotations.

```go
package main

import (
	"context"
	"database/sql"
	"net/http"
	"sync/atomic"
	"time"

	_ "github.com/lib/pq"
)

var (
	startupComplete atomic.Bool // flipped to true once init finishes
	db              *sql.DB
)

func main() {
	// Background init — migrations, cache warm-up, etc.
	go func() {
		runMigrations()
		warmCache()
		startupComplete.Store(true)
	}()

	var err error
	db, err = sql.Open("postgres", "postgres://...")
	if err != nil {
		panic(err)
	}

	http.HandleFunc("/startupz", startupzHandler)
	http.HandleFunc("/livez", livezHandler)
	http.HandleFunc("/readyz", readyzHandler)

	http.ListenAndServe(":8080", nil)
}

// /startupz — is one-time init done?
func startupzHandler(w http.ResponseWriter, r *http.Request) {
	if !startupComplete.Load() {
		http.Error(w, "still starting", http.StatusServiceUnavailable)
		return
	}
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("ok"))
}

// /livez — am I responsive? No external checks here.
func livezHandler(w http.ResponseWriter, r *http.Request) {
	// If this handler runs at all, the process is alive
	// and the HTTP server is responsive. That is the whole point.
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("ok"))
}

// /readyz — can I serve a real request? Check dependencies here.
func readyzHandler(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()

	if err := db.PingContext(ctx); err != nil {
		http.Error(w, "db not reachable", http.StatusServiceUnavailable)
		return
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte("ok"))
}

func runMigrations() { /* ... */ }
func warmCache()     { /* ... */ }
```

Notice how short `/livez` is. That is on purpose. The dumber and faster your liveness handler, the better it does its job.

### Which probes does my app actually need?

A question I get all the time. The short answer: **no, not every app needs all three probes.** Adding probes you do not need is one of the easiest ways to make your deployment less reliable, not more.

Here is the way to think about it. Each probe answers a different question, and you only need it if that question matters for your app:

- **Do I need readiness?** Ask: does my app receive traffic through a Kubernetes Service? If yes, you almost always want readiness. If no (background worker, cron job), you probably do not.
- **Do I need liveness?** Ask: can my process get stuck in a way that a restart would actually fix? Deadlocks, frozen event loops, exhausted thread pools. If yes, liveness helps. If your app crashes cleanly when it has problems, liveness adds little — Kubernetes already restarts crashed containers.
- **Do I need startup?** Ask: does my app take long enough to start that a liveness probe would kill it during boot? If startup is under 10 seconds, you do not need a startup probe — just bump the `initialDelaySeconds` on liveness. If startup is 30 seconds or more, you almost certainly want one.

### Real-world examples, by app type

This table is the cheat sheet I wish someone had given me when I was starting out.

| App type                          | Startup           | Liveness          | Readiness         | Why                                                                                  |
|-----------------------------------|-------------------|-------------------|-------------------|--------------------------------------------------------------------------------------|
| Spring Boot / JVM web app         | Yes               | Yes               | Yes               | JVM is slow to boot; readiness for the DB; liveness for stuck threads. (This repo.)  |
| Go or Rust HTTP API               | Usually no        | Optional          | Yes               | Boots in under a second. Liveness only if you have known deadlock risks.             |
| Node.js / Python web app          | Sometimes         | Yes               | Yes               | Boot time varies; event loop can get blocked, so liveness pulls its weight.          |
| ML model server (TensorFlow, etc.) | Yes               | Yes               | Yes               | Loading a model into GPU memory can take minutes. Readiness reports "model loaded".  |
| Static frontend (Nginx, React build) | No              | Optional          | Yes               | Boots instantly. Readiness is enough so the Service waits for Nginx to bind.         |
| Background worker / queue consumer | No              | Yes (exec)        | No                | No Service traffic, so no readiness. Liveness checks a heartbeat file the worker touches. |
| Batch job / Kubernetes Job        | No                | No                | No                | The job runs to completion. Probes do not apply.                                     |
| Cron job                          | No                | No                | No                | Same — runs and exits.                                                               |
| Database (Postgres, MySQL)        | Yes               | Be careful        | Yes               | Slow start after a crash. Liveness can cause data loss if it restarts mid-recovery.  |
| Kafka / Cassandra / stateful cluster | Yes            | Be careful        | Yes               | Cluster join time is long. Restarting a node mid-join can wedge the whole cluster.   |
| Redis / in-memory cache           | Optional          | Yes               | Yes               | Fast to boot. Liveness catches the rare hang.                                        |
| Proxy / sidecar (Envoy, nginx)    | Optional          | Yes               | Yes               | Should be ready before the main container starts taking traffic.                     |
| Single-replica admin tool         | No                | No                | Sometimes         | Restart loops on admin tools are usually worse than the original problem.            |

### A few things worth calling out

**Workers without HTTP do not need readiness.** A queue consumer that pulls jobs from RabbitMQ has no HTTP server and is not behind a Service. There is no traffic to route. Skip readiness. Use a liveness `exec` probe instead — your worker writes a timestamp to `/tmp/heartbeat` every loop, and the probe checks that the file was touched recently:

```yaml
livenessProbe:
  exec:
    command:
      - sh
      - -c
      - test $(($(date +%s) - $(stat -c %Y /tmp/heartbeat))) -lt 30
  periodSeconds: 10
  failureThreshold: 3
```

**Liveness on databases is dangerous.** Postgres replaying its WAL after an unclean shutdown can take minutes. If your liveness probe fails during that window and Kubernetes restarts the container, you start the replay over. Repeat enough times and you can corrupt data. For databases, prefer a generous startup probe and no liveness probe at all, or a very forgiving one.

**Batch jobs and cron jobs do not get probes.** A `Job` or `CronJob` resource runs a container to completion. There is no long-running endpoint to probe. Probes are for long-running services.

**Start with the minimum.** When in doubt, add readiness first. It is the safest probe — failure just stops traffic, no restarts. Add liveness only after you know the specific failure mode you are trying to catch. Add startup only if liveness is killing your app during boot.

### Decision tree

```
                ┌────────────────────────────────────┐
                │  Does my app run forever            │
                │  (long-running service)?            │
                └────────────────┬───────────────────┘
                                 │
                  ┌──────────────┴──────────────┐
                 NO                              YES
                  │                              │
                  ▼                              ▼
        ┌─────────────────┐         ┌─────────────────────────────┐
        │  Batch / Job /  │         │  Does it receive traffic     │
        │  CronJob.       │         │  through a Service?          │
        │  No probes.     │         └─────────────┬───────────────┘
        └─────────────────┘                       │
                                     ┌────────────┴────────────┐
                                    YES                         NO
                                     │                          │
                                     ▼                          ▼
                          ┌──────────────────┐      ┌──────────────────────┐
                          │  Add readiness.  │      │  No readiness needed │
                          │  Always.         │      │  (background worker) │
                          └────────┬─────────┘      └──────────┬───────────┘
                                   │                            │
                                   └──────────────┬─────────────┘
                                                  │
                                                  ▼
                                  ┌──────────────────────────────────┐
                                  │  Can the process hang/deadlock    │
                                  │  in a way a restart would fix?   │
                                  └─────────────┬────────────────────┘
                                                │
                                  ┌─────────────┴─────────────┐
                                 YES                          NO
                                  │                            │
                                  ▼                            ▼
                          ┌────────────────┐         ┌──────────────────┐
                          │ Add liveness.  │         │ Skip liveness.   │
                          │ Keep it cheap. │         │ Crashes already  │
                          └───────┬────────┘         │ trigger restart. │
                                  │                  └──────────────────┘
                                  ▼
                  ┌──────────────────────────────────┐
                  │  Does the app take long enough    │
                  │  to start that liveness would     │
                  │  kill it during boot?             │
                  └─────────────┬────────────────────┘
                                │
                   ┌────────────┴────────────┐
                  YES                         NO
                   │                          │
                   ▼                          ▼
            ┌────────────────┐     ┌──────────────────────┐
            │ Add startup    │     │ Skip startup. Just   │
            │ probe.         │     │ use a small          │
            └────────────────┘     │ initialDelaySeconds. │
                                   └──────────────────────┘
```

Walk through this tree once for every new deployment. It takes 10 seconds and saves you from 80% of probe mistakes.

### The common mistakes — drawn out

Almost every probe bug fits one of these shapes:

```
┌──────────────────────────────────────────────────────────────────┐
│  MISTAKE 1 — Liveness checks the database                         │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│   livezHandler() {                                                │
│       db.Ping()  ◀─── BAD                                         │
│   }                                                               │
│                                                                   │
│   What happens: DB has a 30s blip → liveness fails →              │
│   Kubernetes restarts pod → pod comes back up → DB still down →   │
│   liveness fails again → restart again → CrashLoopBackOff.        │
│                                                                   │
│   Fix: move the DB check to /readyz. Leave /livez trivial.        │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  MISTAKE 2 — No startup probe on a slow-starting app              │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│   JVM takes 45s to boot.                                          │
│   Liveness probe starts hitting at t=0s with no delay.            │
│   First three checks fail (app not listening yet).                │
│   Kubernetes restarts the container.                              │
│   Same thing happens. Forever.                                    │
│                                                                   │
│   Fix: add a startupProbe (see k8s/app.yaml — failureThreshold:30 │
│   × periodSeconds:10 = 5 min budget). Liveness will not run       │
│   until startup succeeds once.                                    │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  MISTAKE 3 — Readiness is the same endpoint as liveness           │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│   livenessProbe.httpGet.path:  /health                            │
│   readinessProbe.httpGet.path: /health   ◀─── same handler        │
│                                                                   │
│   If /health checks the DB, you have Mistake 1.                   │
│   If /health does not check the DB, readiness is useless —        │
│   it will always pass even when you cannot serve a request.       │
│                                                                   │
│   Fix: two endpoints, two responsibilities.                       │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  MISTAKE 4 — Timeout too short, probe too aggressive              │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│   timeoutSeconds: 1                                               │
│   failureThreshold: 1                                             │
│   periodSeconds: 2                                                │
│                                                                   │
│   One slow GC pause → liveness times out → restart.               │
│   You will spend a week chasing "random" restarts.                │
│                                                                   │
│   Fix: give probes room to breathe. 3-5s timeout, 3 failures      │
│   before reacting, is a sane default.                             │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

If you remember nothing else from this guide, remember Mistake 1. It is responsible for more 3am pages than the other three combined.

### How the Java app in this repo lines up

Spring Boot ships these endpoints for you. The YAML in `k8s/app.yaml` uses:

- `startupProbe` → `/actuator/health/liveness` (cheap, just confirms the app is up)
- `livenessProbe` → `/actuator/health/liveness` (same cheap check, no DB)
- `readinessProbe` → `/actuator/health/readiness` (Spring Boot includes the DB health indicator here automatically)

That mapping follows the rules above. The liveness path does not touch the database. The readiness path does. If Postgres goes down, the pod stops getting traffic but does not restart — and once Postgres comes back, the pod is healthy again with no human in the loop.

---

## 3. Kubernetes Side — How the Kubelet Actually Runs Probes

Now flip to the other side of the wire. When you write `livenessProbe:` in your YAML, who is the one calling that endpoint, and how?

The answer is the **kubelet** — the Kubernetes agent that runs on every node. Inside the kubelet there is a component called the **prober manager**, and it is the one in charge of running every probe for every container on that node.

Here is what happens under the hood.

### The flow

When a pod is scheduled to a node, the kubelet reads its spec and sees the probes. For each probe on each container, the prober manager creates a small goroutine called a **worker**. The worker is a tiny loop. It sleeps for `periodSeconds`, runs the probe, records the result, and sleeps again.

There is one worker per (container × probe type). A pod with all three probe types on one container gets three workers. A pod with two containers and three probes each gets six workers. They all run independently.

When a worker runs a probe, it does not call your app directly from some abstract Kubernetes brain. It goes through the **container runtime** (containerd, CRI-O, etc.) for `exec` probes, or it makes a network call from the node's network namespace for `httpGet` and `tcpSocket` probes. For HTTP, that means the kubelet on the node hits your pod IP on the configured port.

After each run, the worker writes the result — success, failure, or unknown — into the **results cache** inside the kubelet. The cache is what the rest of the kubelet reads from when it decides whether to restart a container (liveness), update Service endpoints (readiness), or stop running the startup probe (startup).

This split is important. The worker that calls your app is not the same component that decides what to do about the result. The worker only writes to the cache. Other parts of the kubelet read from the cache and act on it.

### The diagram

```
                    ┌──────────────────────────────────────────┐
                    │              kubelet (on node)            │
                    │                                           │
                    │   ┌──────────────────────────────────┐    │
                    │   │       Prober Manager              │   │
                    │   │                                   │   │
                    │   │   For each container × probe:     │   │
                    │   │   spawn one Worker goroutine      │   │
                    │   │                                   │   │
                    │   └──┬────────────┬────────────┬─────┘    │
                    │      │            │            │           │
                    │      ▼            ▼            ▼           │
                    │  ┌────────┐  ┌────────┐  ┌────────┐        │
                    │  │Worker  │  │Worker  │  │Worker  │        │
                    │  │startup │  │liveness│  │readyz  │        │
                    │  └───┬────┘  └───┬────┘  └───┬────┘        │
                    │      │           │           │              │
                    │      │ loop every periodSeconds             │
                    │      │           │           │              │
                    │      ▼           ▼           ▼              │
                    │  ┌────────────────────────────────────┐    │
                    │  │       Results Cache                 │   │
                    │  │   (latest status per probe)         │   │
                    │  └──┬─────────────────┬────────────┬──┘    │
                    │     │                 │            │        │
                    │     │ read            │ read       │ read   │
                    │     ▼                 ▼            ▼        │
                    │  ┌─────────┐    ┌─────────┐  ┌──────────┐   │
                    │  │Restart  │    │Service  │  │Startup   │   │
                    │  │decision │    │endpoint │  │gating    │   │
                    │  │(liveness│    │update   │  │(unblock  │   │
                    │  │ failed) │    │(readyz) │  │ others)  │   │
                    │  └─────────┘    └─────────┘  └──────────┘   │
                    │                                              │
                    │   ┌──────────────────────────────────┐      │
                    │   │   Container Runtime Interface     │     │
                    │   │   (containerd, CRI-O)             │     │
                    │   │   used for exec probes            │     │
                    │   └──────────────────────────────────┘      │
                    │                                              │
                    └────────────────┬─────────────────────────────┘
                                     │
                                     │ http GET pod-ip:8080/livez
                                     │ http GET pod-ip:8080/readyz
                                     │ http GET pod-ip:8080/startupz
                                     ▼
                          ┌─────────────────────┐
                          │   Your Pod           │
                          │   (the Spring Boot   │
                          │    or Go app)        │
                          └─────────────────────┘
```

### A few things this picture makes obvious

The kubelet calls your pod from the same node. The traffic does not leave the node, does not go through your Service, does not touch kube-proxy. That is why probes work even when your Service is broken, and it is why a misconfigured NetworkPolicy that blocks node-to-pod traffic will silently break every probe on that node.

The worker loop is independent per probe. A slow `/readyz` does not delay `/livez`. They are different goroutines hitting different endpoints on their own schedules.

The results cache is what the rest of the kubelet reads. If you `kubectl describe pod` and see "Liveness probe failed", that text is coming from the cache. The cache only knows what the worker last wrote. If your worker is slow because your timeout is high, the cache will be stale.

For `exec` probes the kubelet asks the container runtime to run a command inside the container. That goes over the CRI socket on the node. For `httpGet` and `tcpSocket`, the kubelet just opens a network connection from the node's network namespace to the pod IP. No runtime involvement.

---

## 4. Try It Yourself

The repo already has everything you need to play with this. Two ideas to try, in order.

**1. Confirm the readiness/liveness split is working.**

Deploy the app:

```bash
kubectl apply -f k8s/postgres.yaml
kubectl apply -f k8s/app.yaml
kubectl get pods -w
```

Wait for the pod to be `Running` and `1/1` ready. Now break the database on purpose:

```bash
kubectl scale statefulset postgres --replicas=0
```

Watch what happens:

```bash
kubectl get pods -w
```

The Spring Boot pod stays `Running` but goes to `0/1` — readiness is failing because the DB is gone. It does **not** restart. The `RESTARTS` column stays at zero. That is the behavior you want.

Now bring the database back:

```bash
kubectl scale statefulset postgres --replicas=1
```

Within a few seconds, the pod flips back to `1/1` on its own. No human intervention.

**2. Watch the probes happen in real time.**

```bash
kubectl describe pod -l app=hello-spring | grep -A2 -E "Liveness|Readiness|Startup"
```

You will see the actual `httpGet` paths, periods, and thresholds the kubelet is using. If you tail the pod logs, you can see the probe requests landing every few seconds:

```bash
kubectl logs -l app=hello-spring -f | grep -E "actuator/health"
```

Every few seconds, you will see `/actuator/health/liveness` and `/actuator/health/readiness` come in. That is the kubelet on the node, in its worker goroutines, calling your app.

---

## Quick Reference

| Probe        | Question                          | On failure                      | Check inside                          |
|--------------|-----------------------------------|---------------------------------|---------------------------------------|
| Startup      | Has init finished?                | Restart after threshold         | One-time setup, model loaded, etc.    |
| Liveness     | Am I stuck?                       | Restart container               | Only things a restart would fix       |
| Readiness    | Can I serve traffic right now?    | Remove from Service endpoints   | Dependencies — DB, cache, downstream  |

If you keep that table in your head when you write your next probe, you will not write Mistake 1.

---

## References

- [Kubernetes docs — Configure liveness, readiness and startup probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [Kubernetes docs — Pod lifecycle: probes](https://kubernetes.io/docs/concepts/workloads/pods/probes/)
- [KubeOps blog — Kubernetes Probes](https://kubeops.net/blog/kubernetes-probes)
- `k8s/app.yaml` in this repo for a working probe config on a real Spring Boot app
