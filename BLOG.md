# Kubernetes Health Probes with Spring Boot & PostgreSQL

> A complete guide to building a production-ready Spring Boot application with Kubernetes liveness, readiness, and startup probes — deployed on DigitalOcean Kubernetes.

---

## Table of Contents

1. [What Are Kubernetes Probes?](#1-what-are-kubernetes-probes)
2. [Project Overview](#2-project-overview)
3. [System Architecture](#3-system-architecture)
4. [Application Architecture — Layer by Layer](#4-application-architecture--layer-by-layer)
5. [The Dockerfile Explained](#5-the-dockerfile-explained)
6. [Kubernetes Manifests Explained](#6-kubernetes-manifests-explained)
7. [Step-by-Step Deployment](#7-step-by-step-deployment)
8. [Testing the Probes](#8-testing-the-probes)
9. [Troubleshooting Common Issues](#9-troubleshooting-common-issues)

---

## 1. What Are Kubernetes Probes?

When your application runs inside Kubernetes, the platform needs to know the health of your container at all times. It cannot see inside your app — so your app must **expose health check endpoints** that Kubernetes calls periodically.

Kubernetes provides three types of probes:

### Startup Probe
- **Question asked:** "Has the application finished starting up?"
- **What happens while waiting:** Kubernetes does not send traffic and does not restart the pod
- **What happens on failure:** After maximum attempts are exhausted, Kubernetes kills and restarts the container
- **Why it matters:** Java applications (JVM) take 30–60 seconds to start. Without a startup probe, Kubernetes might kill the pod before it is ready

### Liveness Probe
- **Question asked:** "Is the application still alive and responsive?"
- **What happens on success:** Nothing — Kubernetes leaves the pod alone
- **What happens on failure:** Kubernetes **restarts the container**
- **Why it matters:** Detects deadlocks, out-of-memory states, or infinite loops where the app is running but stuck

### Readiness Probe
- **Question asked:** "Is the application ready to serve user traffic right now?"
- **What happens on success:** Pod receives traffic from the load balancer
- **What happens on failure:** Pod is **removed from the Service endpoints** — no traffic is sent until it recovers
- **Why it matters:** If the database goes down, the app should not receive requests it cannot fulfill

### The Key Difference — Liveness vs Readiness

| Scenario | Liveness | Readiness |
|----------|----------|-----------|
| App is frozen / deadlocked | FAIL → restart | FAIL → no traffic |
| Database is down | PASS (keep running) | FAIL → no traffic |
| App just started (JVM warmup) | startup probe handles | startup probe handles |

This separation is critical. You **do not** want Kubernetes to restart your pod every time the database has a brief outage — you just want to stop sending traffic until the database recovers.

---

## 2. Project Overview

This project demonstrates a Spring Boot application that:

- Exposes `/actuator/health/liveness` for liveness and startup probes
- Exposes `/actuator/health/readiness` for readiness probe (includes database check)
- Connects to PostgreSQL and logs every connection event
- **Gracefully handles database downtime** — stays running, stops receiving traffic, auto-recovers
- Provides a live status dashboard at `/status`

**Stack:**
- Java 25 + Spring Boot 4.0.6
- PostgreSQL 17
- Docker (multi-platform build)
- Kubernetes (DigitalOcean DOKS)

---

## 3. System Architecture

```
                          ┌─────────────────────────────────────┐
                          │         Kubernetes Cluster           │
                          │                                      │
  User Request            │   ┌──────────────────────┐          │
──────────────────────────┼──▶│  hello-spring-service │          │
                          │   │  (NodePort :32000)    │          │
                          │   └──────────┬───────────┘          │
                          │              │                       │
                          │              ▼                       │
                          │   ┌──────────────────────┐          │
                          │   │   hello-spring Pod    │          │
                          │   │   (Spring Boot :8080) │          │
                          │   │                       │          │
                          │   │  Startup Probe  ───▶ /actuator/health/liveness  │
                          │   │  Liveness Probe ───▶ /actuator/health/liveness  │
                          │   │  Readiness Probe───▶ /actuator/health/readiness │
                          │   └──────────┬───────────┘          │
                          │              │ JDBC                  │
                          │              ▼                       │
                          │   ┌──────────────────────┐          │
                          │   │   postgres-0 Pod      │          │
                          │   │   (PostgreSQL 17)     │          │
                          │   │   StatefulSet         │          │
                          │   └──────────────────────┘          │
                          └─────────────────────────────────────┘
```

**Traffic flow:**
1. User hits NodePort `:32000`
2. Service routes to `hello-spring` pod (only if readiness probe is UP)
3. App queries PostgreSQL via `postgres-service` DNS
4. Kubernetes checks all three probes every few seconds in the background

---

## 4. Application Architecture — Layer by Layer

### Layer 1: Entry Point

**`HelloApplication.java`**
```java
@SpringBootApplication
@EnableScheduling
public class HelloApplication {
    public static void main(String[] args) {
        SpringApplication.run(HelloApplication.class, args);
    }
}
```

Standard Spring Boot entry point. `@EnableScheduling` activates the background DB health check scheduler.

---

### Layer 2: Web Controller

**`HelloController.java`** exposes three endpoints:

| Endpoint | Returns | Purpose |
|----------|---------|---------|
| `GET /` | HTML page | Hello World UI with link to status |
| `GET /status` | HTML dashboard | Live probe status + connection log |
| `GET /api/status` | JSON | `{"databaseConnected": true}` |
| `GET /api/logs` | JSON array | Last 20 connection log entries |

---

### Layer 3: Database Service

**`DatabaseConnectionService.java`** — the most important class.

```
Application starts
      │
      ▼
onApplicationReady()
      │
      ▼
tryConnectAndLog()
      ├── SUCCESS → create table, insert log, set databaseConnected = true
      └── FAIL    → log warning, set databaseConnected = false (app keeps running)
      
Every 5 seconds (after initial 10s delay):
refreshDatabaseStatus()
      ├── if databaseConnected = false → retry tryConnectAndLog()
      └── if databaseConnected = true  → quick connection test
                                              ├── SUCCESS → do nothing
                                              └── FAIL    → set databaseConnected = false
```

**Key design decisions:**

1. **`initialization-fail-timeout: -1`** in HikariCP config — connection pool does NOT fail app startup if DB is unreachable. Without this, Spring Boot throws an exception at startup and the pod crashes.

2. **`volatile boolean databaseConnected`** — the `volatile` keyword ensures that reads/writes from multiple threads (web request thread vs scheduler thread) always see the latest value.

3. **Separate liveness from readiness** — liveness endpoint only reports `livenessState` (is JVM alive?). Readiness endpoint reports `readinessState` + `db` (is DB connected?). This means a DB outage does NOT trigger pod restarts.

---

### Layer 4: Health Configuration

**`application.yml`** — the actuator configuration:

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true       # Enables /liveness and /readiness URLs
      group:
        liveness:
          include: livenessState        # Only checks: is JVM alive?
        readiness:
          include: readinessState,db    # Checks: JVM alive AND DB connected
```

Spring Boot's built-in `db` health indicator automatically tests the database connection when `spring-boot-starter-jdbc` is on the classpath. No custom code needed for DB health check — just configure which group includes it.

---

### Layer 5: Connection Log Table

Every time the app connects to the database, it writes a record:

```sql
CREATE TABLE connection_log (
    id        BIGSERIAL PRIMARY KEY,
    message   VARCHAR(255) NOT NULL,
    timestamp TIMESTAMP NOT NULL
);
```

| Scenario | Message written |
|----------|----------------|
| First ever connection | `application connected` |
| Restart (1 previous row) | `restarted 1` |
| Restart (5 previous rows) | `restarted 5` |
| DB was down, now recovered | `restarted N` |

You can view this table live at `http://your-app/status` or via:

```bash
kubectl exec -it postgres-0 -- psql -U postgres -d hellodb -c "SELECT * FROM connection_log;"
```

---

## 5. The Dockerfile Explained

```dockerfile
# ─── Stage 1: Build ──────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src

# Install Maven (not included in temurin image)
RUN apk add --no-cache maven

# Compile and package — skip tests for faster build
RUN mvn clean package -DskipTests

# ─── Stage 2: Runtime ────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Copy only the final JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Why Multi-Stage Build?

| | Single Stage | Multi-Stage |
|--|-------------|-------------|
| Image size | ~800MB (JDK + Maven + source) | ~180MB (JRE + JAR only) |
| Attack surface | Large (build tools exposed) | Small (runtime only) |
| Best practice | No | Yes |

**Stage 1** uses `eclipse-temurin:25-jdk-alpine`:
- `jdk` = full Java Development Kit needed to compile code
- `alpine` = minimal Linux base image (~5MB vs ~150MB for Ubuntu)

**Stage 2** uses `eclipse-temurin:25-jre-alpine`:
- `jre` = Java Runtime Environment only — no compiler, no dev tools
- Final image contains only what is needed to run the JAR

### Build for Multiple CPU Architectures

To support both Intel (amd64) and Apple Silicon / ARM servers (arm64):

```bash
# Create a multi-platform builder
docker buildx create --use --name multibuilder

# Build and push both architectures in one command
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t youruser/yourapp:1.0.0 \
  --push \
  .
```

---

## 6. Kubernetes Manifests Explained

### `postgres.yaml` — Three Resources

**Secret** — stores database credentials:
```yaml
apiVersion: v1
kind: Secret
type: Opaque
stringData:
  POSTGRES_USER: postgres
  POSTGRES_PASSWORD: postgres123
  POSTGRES_DB: hellodb
```

> **Note:** For production, use a secrets manager (DigitalOcean Secrets, HashiCorp Vault, or Sealed Secrets) instead of plaintext in YAML.

**Headless Service** — stable DNS for StatefulSet:
```yaml
kind: Service
spec:
  clusterIP: None   # Headless — no load balancing, direct pod DNS
```

With `clusterIP: None`, the service provides DNS resolution directly to pod IPs. The pod is accessible at `postgres-0.postgres-service` within the cluster.

**StatefulSet** — runs PostgreSQL with persistent storage:
```yaml
kind: StatefulSet
spec:
  serviceName: postgres-service
```

StatefulSet is used instead of Deployment because:
- Pods get stable, predictable names (`postgres-0`, `postgres-1`)
- Each pod gets its own persistent volume
- Pods start and stop in order (important for database clustering)

**PGDATA subdirectory fix** — required for cloud providers:
```yaml
env:
  - name: PGDATA
    value: /var/lib/postgresql/data/pgdata
```

Cloud PVCs (DigitalOcean, AWS EBS) create a `lost+found` directory at the volume root. PostgreSQL refuses to initialize in a non-empty directory. Setting `PGDATA` to a subdirectory avoids this conflict.

---

### `app.yaml` — Three Resources

**ConfigMap** — non-sensitive configuration:
```yaml
kind: ConfigMap
data:
  DB_URL: "jdbc:postgresql://postgres-service:5432/hellodb"
  DB_USERNAME: "postgres"
```

The hostname `postgres-service` is the Kubernetes Service name — this resolves via internal cluster DNS.

**Deployment** — runs the Spring Boot app with all three probes:

```yaml
# Give JVM up to 5 minutes to start (30 attempts × 10s)
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 30

# Restart pod if app becomes unresponsive
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  periodSeconds: 10
  failureThreshold: 3    # 3 failures × 10s = 30s before restart

# Remove from load balancer if DB is disconnected
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  periodSeconds: 5
  failureThreshold: 3    # 3 failures × 5s = 15s before removing from traffic
```

**Service** — exposes the app externally:
```yaml
kind: Service
spec:
  type: NodePort
  ports:
    - port: 80        # Service port
      targetPort: 8080  # Container port
      nodePort: 32000   # External access port
```

---

## 7. Step-by-Step Deployment

### Prerequisites

- Kubernetes cluster running (DigitalOcean, Minikube, Kind, etc.)
- `kubectl` configured and pointing to your cluster
- Docker image pushed to Docker Hub

### Step 1 — Verify Cluster Connection

```bash
kubectl cluster-info
# Should show: Kubernetes control plane is running at https://...
```

### Step 2 — Deploy PostgreSQL

```bash
kubectl apply -f k8s/postgres.yaml
```

Wait for PostgreSQL to be fully ready:

```bash
kubectl wait --for=condition=ready pod -l app=postgres --timeout=120s

# Verify
kubectl get pods
# Expected: postgres-0   1/1   Running   0   30s
```

### Step 3 — Deploy the Application

```bash
kubectl apply -f k8s/app.yaml
```

Watch the startup sequence:

```bash
kubectl get pods -w
```

You will see this progression:
```
NAME                           READY   STATUS    RESTARTS
hello-spring-xxxxx             0/1     Running   0        ← startup probe checking
hello-spring-xxxxx             0/1     Running   0        ← liveness UP, readiness still checking
hello-spring-xxxxx             1/1     Running   0        ← all probes UP, receiving traffic
```

### Step 4 — Access the Application

**Option A — Port-forward (development):**
```bash
kubectl port-forward svc/hello-spring-service 8080:80
# Open http://localhost:8080
```

**Option B — NodePort (direct node access):**
```bash
# Get node external IP
kubectl get nodes -o wide

# Access via: http://<NODE-IP>:32000
```

**Option C — Get service URL:**
```bash
kubectl get svc hello-spring-service
```

### Step 5 — Verify Everything Works

```bash
# Check pod status
kubectl get pods

# Check probes from inside the pod
kubectl exec -it deployment/hello-spring -- wget -qO- localhost:8080/actuator/health/liveness
kubectl exec -it deployment/hello-spring -- wget -qO- localhost:8080/actuator/health/readiness

# Check connection log in database
kubectl exec -it postgres-0 -- psql -U postgres -d hellodb -c "SELECT * FROM connection_log;"

# View app logs
kubectl logs -f deployment/hello-spring
```

---

## 8. Testing the Probes

### Test 1 — Verify Liveness

```bash
curl http://localhost:8080/actuator/health/liveness
```

Expected:
```json
{"status":"UP","components":{"livenessState":{"status":"UP"}}}
```

### Test 2 — Verify Readiness (with DB)

```bash
curl http://localhost:8080/actuator/health/readiness
```

Expected:
```json
{
  "status": "UP",
  "components": {
    "db": {"status": "UP", "details": {"database": "PostgreSQL"}},
    "readinessState": {"status": "UP"}
  }
}
```

### Test 3 — Simulate Database Outage

Kill the PostgreSQL pod and watch readiness fail while liveness stays up:

```bash
# Delete postgres pod (StatefulSet will recreate it)
kubectl delete pod postgres-0

# In another terminal — watch readiness flip DOWN then back UP
watch -n2 'kubectl exec deployment/hello-spring -- wget -qO- localhost:8080/actuator/health/readiness 2>&1'

# Watch endpoints disappear (no traffic) then return
kubectl get endpoints hello-spring-service -w
```

**Expected behavior:**
1. `postgres-0` deleted
2. Readiness probe returns `DOWN` within ~15 seconds
3. `hello-spring-service` endpoints become empty (no traffic)
4. `postgres-0` recreates and starts
5. App scheduler detects DB is back (within 5 seconds)
6. Readiness returns `UP`
7. Endpoints return — traffic resumes

### Test 4 — View Live Status Dashboard

Open `http://localhost:8080/status` in browser.

You will see:
- **Application** badge — always green (app is running)
- **Database** badge — green when DB connected, red when down
- **Liveness** badge — always green (reflects JVM health)
- **Readiness** badge — green/red based on DB state
- **Connection Log table** — auto-refreshes every 5 seconds

### Test 5 — Check Probe Events in Kubernetes

```bash
# See probe failure events
kubectl get events --sort-by='.lastTimestamp' | grep -i probe

# Describe pod for detailed probe status
kubectl describe pod -l app=hello-spring
```

Look for:
```
Liveness:   http-get http://:8080/actuator/health/liveness delay=0s timeout=5s period=10s
Readiness:  http-get http://:8080/actuator/health/readiness delay=0s timeout=3s period=5s
Startup:    http-get http://:8080/actuator/health/liveness delay=30s timeout=5s period=10s
```

---

## 9. Troubleshooting Common Issues

### Pod in CrashLoopBackOff — PostgreSQL

**Symptom:**
```
postgres-0   0/1   CrashLoopBackOff
```

**Logs show:**
```
initdb: error: directory "/var/lib/postgresql/data" exists but is not empty
It contains a lost+found directory
```

**Cause:** Cloud PVC has `lost+found` at root, PostgreSQL refuses to initialize.

**Fix:** Set `PGDATA` to a subdirectory in `postgres.yaml`:
```yaml
env:
  - name: PGDATA
    value: /var/lib/postgresql/data/pgdata
```

---

### Readiness Probe — Context Deadline Exceeded

**Symptom:**
```
Readiness probe failed: context deadline exceeded (Client.Timeout exceeded while awaiting headers)
```

**Cause:** App cannot reach PostgreSQL — usually wrong hostname in `DB_URL`.

**Fix:** Ensure `DB_URL` uses the exact Kubernetes Service name:
```yaml
DB_URL: "jdbc:postgresql://postgres-service:5432/hellodb"
#                          ^^^^^^^^^^^^^^^^ must match Service metadata.name
```

---

### UnsupportedClassVersionError on Startup

**Symptom:**
```
UnsupportedClassVersionError: class file version 69.0, runtime recognizes up to 65.0
```

**Cause:** JAR compiled with Java 25 (class version 69), but runtime Java is 21 (class version 65).

**Fix:**
```bash
# Install matching Java version
brew install --cask temurin@25

# Set as active Java
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
java -jar target/hello-spring-1.0.0.jar
```

---

### Pod Stuck in 0/1 Running (Startup Probe Waiting)

**This is normal behavior.** The startup probe has `initialDelaySeconds: 30` and `failureThreshold: 30` — meaning Kubernetes will wait up to 5 minutes for the JVM to finish starting.

Watch progress:
```bash
kubectl describe pod -l app=hello-spring | grep -A 5 "Startup"
kubectl logs -f deployment/hello-spring
```

---

## Summary

| Component | Purpose |
|-----------|---------|
| **Startup Probe** | Give JVM time to warm up (up to 5 minutes) |
| **Liveness Probe** | Detect and restart frozen/deadlocked app |
| **Readiness Probe** | Stop traffic when DB is unavailable |
| **`PGDATA` subdirectory** | Fix for cloud provider PVC `lost+found` conflict |
| **`initialization-fail-timeout: -1`** | Allow app to start without database |
| **`@Scheduled` health check** | Auto-recover readiness when DB comes back |
| **Headless Service** | Stable DNS for PostgreSQL StatefulSet |
| **Multi-stage Dockerfile** | Smaller, more secure container image |

The core pattern: **liveness and readiness serve different purposes and must check different things.** Readiness checks dependencies (DB). Liveness checks only the application itself. This separation prevents unnecessary pod restarts during database maintenance or transient failures.

---

*Built with Spring Boot 4.0.6 · Java 25 · PostgreSQL 17 · Kubernetes*
