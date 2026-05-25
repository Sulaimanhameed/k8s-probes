package com.example.hello.controller;

import com.example.hello.service.EmployeeService;
import com.example.hello.service.DatabaseConnectionService;
import com.example.hello.service.ProbeSimulatorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class HelloController {

    private final EmployeeService employeeService;
    private final DatabaseConnectionService databaseConnectionService;
    private final ProbeSimulatorService probeSimulatorService;

    @Autowired
    public HelloController(EmployeeService employeeService,
                           DatabaseConnectionService databaseConnectionService,
                           ProbeSimulatorService probeSimulatorService) {
        this.employeeService = employeeService;
        this.databaseConnectionService = databaseConnectionService;
        this.probeSimulatorService = probeSimulatorService;
    }

    // JSON API for Live Dashboard Polling
    @GetMapping(value = "/api/dashboard/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getDashboardStatus() {
        return Map.of(
            "livenessBroken", probeSimulatorService.isLivenessBroken(),
            "readinessBroken", probeSimulatorService.isReadinessBroken(),
            "databaseConnected", databaseConnectionService.isDatabaseConnected(),
            "employeeCount", employeeService.getAll().size(),
            "logs", databaseConnectionService.getConnectionLogs()
        );
    }

    // Simulator Endpoint - Liveness Simulation Toggle
    @PostMapping(value = "/api/simulator/liveness/toggle")
    public Map<String, Object> toggleLiveness() {
        if (probeSimulatorService.isLivenessBroken()) {
            probeSimulatorService.restoreLiveness();
        } else {
            probeSimulatorService.breakLiveness();
        }
        return Map.of("success", true, "livenessBroken", probeSimulatorService.isLivenessBroken());
    }

    // Simulator Endpoint - Readiness Simulation Toggle
    @PostMapping(value = "/api/simulator/readiness/toggle")
    public Map<String, Object> toggleReadiness() {
        if (probeSimulatorService.isReadinessBroken()) {
            probeSimulatorService.restoreReadiness();
        } else {
            probeSimulatorService.breakReadiness();
        }
        return Map.of("success", true, "readinessBroken", probeSimulatorService.isReadinessBroken());
    }

    // Simulator Endpoint - Restore Auto-Healing
    @PostMapping(value = "/api/simulator/reset")
    public Map<String, Object> resetAll() {
        probeSimulatorService.restoreAll();
        return Map.of("success", true);
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        List<Map<String, String>> employees = employeeService.getAll();

        StringBuilder recordRows = new StringBuilder();
        if (employees.isEmpty()) {
            recordRows.append("<tr><td colspan='5' class='empty'>No onboarded engineers in standard registry.</td></tr>");
        } else {
            for (Map<String, String> e : employees) {
                String mode = esc(e.get("workMode"));
                String modeClass = "badge-default";
                if ("Remote".equalsIgnoreCase(mode)) modeClass = "badge-remote";
                else if ("Hybrid".equalsIgnoreCase(mode)) modeClass = "badge-hybrid";
                else if ("On-site".equalsIgnoreCase(mode)) modeClass = "badge-onsite";

                recordRows.append("<tr>")
                    .append("<td><div class='dev-name'>").append(esc(e.get("name"))).append("</div><div class='dev-title'>").append(esc(e.get("jobTitle"))).append("</div></td>")
                    .append("<td><span class='badge ").append(modeClass).append("'>").append(mode).append("</span></td>")
                    .append("<td><code class='tech-pill'>").append(esc(e.get("primarySkill"))).append("</code></td>")
                    .append("<td>").append(esc(e.get("officeLocation"))).append("</td>")
                    .append("<td><a href='mailto:").append(esc(e.get("corporateEmail"))).append("' class='mail-link'>").append(esc(e.get("corporateEmail"))).append("</a></td>")
                    .append("</tr>");
            }
        }

        String topHtml = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Tech Registry &amp; DevOnboard Portal</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Fira+Code:wght@400;500;600&family=Inter:wght@300;400;500;600;700;800&display=swap" rel="stylesheet">
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
                        background: radial-gradient(circle at 50% 0%, #12141d 0%, #08090d 60%, #030305 100%);
                        color: #cbd5e1;
                        min-height: 100vh;
                        overflow-x: hidden;
                    }
                    ::-webkit-scrollbar { width: 8px; }
                    ::-webkit-scrollbar-track { background: #08090d; }
                    ::-webkit-scrollbar-thumb { background: #1e293b; border-radius: 4px; }
                    ::-webkit-scrollbar-thumb:hover { background: #334155; }

                    /* Nav */
                    nav {
                        display: grid;
                        grid-template-columns: 1fr auto 1fr;
                        align-items: center;
                        padding: 1rem 3rem;
                        position: sticky;
                        top: 0;
                        background: rgba(8, 9, 13, 0.85);
                        backdrop-filter: blur(12px);
                        -webkit-backdrop-filter: blur(12px);
                        border-bottom: 1px solid rgba(255,255,255,0.05);
                        z-index: 100;
                    }
                    .nav-brand {
                        font-family: 'Fira Code', monospace;
                        font-weight: 600;
                        font-size: 1.05rem;
                        color: #00f2fe;
                        text-decoration: none;
                        display: flex;
                        align-items: center;
                    }
                    .nav-brand span { color: #fff; }
                    .nav-links {
                        display: flex;
                        gap: 0.25rem;
                        background: rgba(255,255,255,0.03);
                        border: 1px solid rgba(255,255,255,0.06);
                        border-radius: 9999px;
                        padding: 0.25rem 0.4rem;
                    }
                    .nav-links button {
                        color: #94a3b8;
                        background: none;
                        border: none;
                        padding: 0.4rem 1.1rem;
                        border-radius: 9999px;
                        font-size: 0.82rem;
                        font-weight: 500;
                        cursor: pointer;
                        transition: all 0.2s ease;
                        font-family: inherit;
                    }
                    .nav-links button.active, .nav-links button:hover { color: #fff; background: rgba(255,255,255,0.08); }
                    .nav-cta {
                        justify-self: end;
                        background: linear-gradient(135deg, #00f2fe 0%, #4facfe 100%);
                        color: #030405;
                        font-weight: 700;
                        font-size: 0.82rem;
                        padding: 0.5rem 1.2rem;
                        border-radius: 9999px;
                        border: none;
                        cursor: pointer;
                        box-shadow: 0 0 15px rgba(0,242,254,0.2);
                        transition: all 0.2s;
                        font-family: inherit;
                        white-space: nowrap;
                    }
                    .nav-cta:hover { transform: translateY(-1px); box-shadow: 0 0 25px rgba(0,242,254,0.4); }

                    /* Hero */
                    .hero {
                        text-align: center;
                        padding: 7rem 1rem 5rem;
                    }
                    .hero-title {
                        font-size: 3.2rem;
                        font-weight: 800;
                        color: #f8fafc;
                        line-height: 1.15;
                        margin-bottom: 1rem;
                    }
                    .hero-title .accent { color: #00f2fe; }
                    .hero-sub {
                        font-size: 1rem;
                        color: #64748b;
                        max-width: 480px;
                        margin: 0 auto 2.5rem;
                        line-height: 1.7;
                    }
                    .hero-btn {
                        background: linear-gradient(135deg, #00f2fe 0%, #4facfe 100%);
                        color: #030405;
                        font-weight: 700;
                        font-size: 0.95rem;
                        padding: 0.9rem 2.4rem;
                        border-radius: 9999px;
                        border: none;
                        cursor: pointer;
                        box-shadow: 0 0 20px rgba(0,242,254,0.25);
                        transition: all 0.2s;
                        font-family: inherit;
                    }
                    .hero-btn:hover { transform: translateY(-2px); box-shadow: 0 0 35px rgba(0,242,254,0.4); }

                    /* Modal overlay */
                    .modal-overlay {
                        display: none;
                        position: fixed;
                        inset: 0;
                        background: rgba(0,0,0,0.75);
                        backdrop-filter: blur(4px);
                        -webkit-backdrop-filter: blur(4px);
                        z-index: 200;
                        align-items: center;
                        justify-content: center;
                        padding: 1.5rem;
                    }
                    .modal-overlay.open { display: flex; }

                    /* Modal box */
                    .modal {
                        background: #0d1117;
                        border: 1px solid rgba(255,255,255,0.08);
                        border-radius: 16px;
                        box-shadow: 0 30px 80px rgba(0,0,0,0.7), 0 0 0 1px rgba(0,242,254,0.05);
                        width: 100%;
                        max-height: 90vh;
                        overflow-y: auto;
                        animation: modal-in 0.2s ease;
                    }
                    .modal-sm { max-width: 520px; }
                    .modal-lg { max-width: 980px; }
                    @keyframes modal-in {
                        from { opacity: 0; transform: translateY(16px) scale(0.97); }
                        to   { opacity: 1; transform: translateY(0) scale(1); }
                    }
                    .modal-header {
                        padding: 1.25rem 1.5rem;
                        border-bottom: 1px solid rgba(255,255,255,0.05);
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        position: sticky;
                        top: 0;
                        background: #0d1117;
                        z-index: 1;
                    }
                    .modal-title {
                        font-family: 'Fira Code', monospace;
                        font-size: 0.9rem;
                        font-weight: 700;
                        color: #f8fafc;
                    }
                    .modal-close {
                        background: rgba(255,255,255,0.04);
                        border: 1px solid rgba(255,255,255,0.08);
                        color: #94a3b8;
                        width: 30px;
                        height: 30px;
                        border-radius: 6px;
                        cursor: pointer;
                        font-size: 1rem;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        transition: all 0.15s;
                    }
                    .modal-close:hover { color: #fff; background: rgba(255,255,255,0.08); }
                    .modal-body { padding: 1.5rem; }

                    /* Table */
                    table { width: 100%; border-collapse: collapse; text-align: left; }
                    th {
                        font-size: 0.68rem;
                        text-transform: uppercase;
                        letter-spacing: 0.08em;
                        color: #475569;
                        padding: 0.75rem 1rem;
                        border-bottom: 1px solid rgba(255,255,255,0.06);
                        font-family: 'Fira Code', monospace;
                    }
                    td {
                        padding: 0.85rem 1rem;
                        font-size: 0.82rem;
                        border-bottom: 1px solid rgba(255,255,255,0.03);
                        color: #cbd5e1;
                    }
                    tr:hover td { background: rgba(255,255,255,0.01); }
                    tr:last-child td { border-bottom: none; }
                    .badge {
                        padding: 0.2rem 0.55rem;
                        border-radius: 9999px;
                        font-size: 0.68rem;
                        font-weight: 600;
                        text-transform: uppercase;
                        display: inline-block;
                    }
                    .badge-remote { background: rgba(16,185,129,0.08); color: #34d399; border: 1px solid rgba(16,185,129,0.15); }
                    .badge-hybrid { background: rgba(245,158,11,0.08); color: #fbbf24; border: 1px solid rgba(245,158,11,0.15); }
                    .badge-onsite { background: rgba(59,130,246,0.08); color: #60a5fa; border: 1px solid rgba(59,130,246,0.15); }
                    .badge-default { background: rgba(148,163,184,0.08); color: #94a3b8; border: 1px solid rgba(148,163,184,0.15); }
                    .tech-pill {
                        font-family: 'Fira Code', monospace;
                        color: #a5f3fc;
                        background: rgba(6,182,212,0.06);
                        padding: 0.1rem 0.35rem;
                        border-radius: 4px;
                        border: 1px solid rgba(6,182,212,0.15);
                        font-size: 0.78rem;
                    }
                    .dev-name { font-weight: 600; color: #f8fafc; }
                    .dev-title { font-size: 0.72rem; color: #64748b; margin-top: 0.1rem; }
                    .mail-link { color: #38bdf8; text-decoration: none; }
                    .mail-link:hover { text-decoration: underline; color: #00f2fe; }
                    .empty { color: #64748b; text-align: center; padding: 3rem 0 !important; font-style: italic; }

                    /* Form */
                    .field { display: flex; flex-direction: column; gap: 0.35rem; margin-bottom: 1rem; }
                    .field label { font-size: 0.68rem; font-weight: 600; color: #94a3b8; text-transform: uppercase; letter-spacing: 0.08em; }
                    .field input, .field select {
                        background: rgba(255,255,255,0.02);
                        border: 1px solid rgba(255,255,255,0.06);
                        border-radius: 8px;
                        padding: 0.55rem 0.85rem;
                        font-size: 0.82rem;
                        color: #f8fafc;
                        outline: none;
                        transition: all 0.15s;
                        font-family: inherit;
                    }
                    .field input:focus, .field select:focus {
                        border-color: #00f2fe;
                        background: rgba(255,255,255,0.04);
                        box-shadow: 0 0 8px rgba(0,242,254,0.15);
                    }
                    .field input::placeholder { color: #475569; }
                    .field-row { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
                    .radio-group { display: flex; gap: 1rem; padding: 0.4rem 0; }
                    .radio-group label { display: flex; align-items: center; gap: 0.3rem; font-size: 0.8rem; color: #cbd5e1; cursor: pointer; text-transform: none; letter-spacing: normal; }
                    .radio-group input[type=radio] { accent-color: #00f2fe; width: 14px; height: 14px; cursor: pointer; }
                    .submit-btn {
                        width: 100%;
                        margin-top: 0.5rem;
                        background: linear-gradient(135deg, #00f2fe 0%, #4facfe 100%);
                        color: #030405;
                        border: none;
                        border-radius: 8px;
                        padding: 0.7rem;
                        font-size: 0.85rem;
                        font-weight: 700;
                        cursor: pointer;
                        transition: all 0.2s;
                        font-family: inherit;
                        box-shadow: 0 0 15px rgba(0,242,254,0.15);
                    }
                    .submit-btn:hover { transform: translateY(-1px); box-shadow: 0 0 20px rgba(0,242,254,0.3); }

                    @media(max-width: 640px) {
                        nav { padding: 1rem 1.2rem; }
                        .hero-title { font-size: 2.1rem; }
                        .field-row { grid-template-columns: 1fr; }
                        .modal-lg { max-width: 100%; }
                    }
                </style>
            </head>
            <body>

            <nav>
                <a href="/" class="nav-brand">&lt;Tech<span>Registry</span> /&gt;</a>
                <div class="nav-links">
                    <button class="active" onclick="void(0)">Home</button>
                    <button onclick="openModal('onboard-modal')">Onboard</button>
                    <button onclick="openModal('engineers-modal')">Engineers</button>
                </div>
                <button class="nav-cta" onclick="openModal('onboard-modal')">Onboard Dev</button>
            </nav>

            <div class="hero">
                <h1 class="hero-title">Staff Registry &amp;<br><span class="accent">Dev</span>Onboard Portal</h1>
                <p class="hero-sub">Register new incoming developers and engineers in our database system. Built with persistent storage and self-healing Kubernetes integration.</p>
                <button class="hero-btn" onclick="openModal('onboard-modal')">Onboard Developer &rarr;</button>
            </div>

            <!-- Onboard Modal -->
            <div class="modal-overlay" id="onboard-modal" onclick="overlayClose(event, 'onboard-modal')">
                <div class="modal modal-sm">
                    <div class="modal-header">
                        <div class="modal-title">Onboard New Team Member</div>
                        <button class="modal-close" onclick="closeModal('onboard-modal')">&#x2715;</button>
                    </div>
                    <div class="modal-body">
                        <form method="POST" action="/submit">
                            <div class="field">
                                <label>Full Name</label>
                                <input type="text" name="name" placeholder="Enter employee name" required />
                            </div>
                            <div class="field-row">
                                <div class="field">
                                    <label>Work Mode</label>
                                    <div class="radio-group">
                                        <label><input type="radio" name="workMode" value="Remote" required /> Remote</label>
                                        <label><input type="radio" name="workMode" value="Hybrid" /> Hybrid</label>
                                    </div>
                                </div>
                                <div class="field">
                                    <label>Years of Exp</label>
                                    <input type="number" name="yearsOfExperience" placeholder="Exp" min="0" required />
                                </div>
                            </div>
                            <div class="field-row">
                                <div class="field">
                                    <label>Start / Hire Date</label>
                                    <input type="date" name="hireDate" required />
                                </div>
                                <div class="field">
                                    <label>Primary Skill / Tech</label>
                                    <input type="text" name="primarySkill" list="technologies" placeholder="Search stack…" required />
                                    <datalist id="technologies">
                                        <option value="Java"><option value="Spring Boot"><option value="Golang">
                                        <option value="Python"><option value="Rust"><option value="TypeScript">
                                        <option value="Kubernetes"><option value="Docker"><option value="PostgreSQL">
                                    </datalist>
                                </div>
                            </div>
                            <div class="field-row">
                                <div class="field">
                                    <label>Slack Handle</label>
                                    <input type="text" name="slackUsername" placeholder="@handle" />
                                </div>
                                <div class="field">
                                    <label>Corporate Email</label>
                                    <input type="email" name="corporateEmail" placeholder="employee@corp.com" required />
                                </div>
                            </div>
                            <div class="field-row">
                                <div class="field">
                                    <label>Base Office</label>
                                    <input type="text" name="officeLocation" placeholder="Office location" required />
                                </div>
                                <div class="field">
                                    <label>Job Title</label>
                                    <select name="jobTitle" required>
                                        <option value="">Select Role</option>
                                        <optgroup label="Engineering">
                                            <option>Junior Software Engineer</option>
                                            <option>Software Engineer</option>
                                            <option>Senior Software Engineer</option>
                                            <option>Staff Engineer</option>
                                        </optgroup>
                                        <optgroup label="Operations &amp; DevOps">
                                            <option>SRE / DevOps Engineer</option>
                                            <option>Cloud Infrastructure Architect</option>
                                        </optgroup>
                                    </select>
                                </div>
                            </div>
                            <button type="submit" class="submit-btn">Onboard Staff &rarr;</button>
                        </form>
                    </div>
                </div>
            </div>

            <!-- Engineers Modal -->
            <div class="modal-overlay" id="engineers-modal" onclick="overlayClose(event, 'engineers-modal')">
                <div class="modal modal-lg">
                    <div class="modal-header">
                        <div class="modal-title">Onboarded Staff Database Directory</div>
                        <button class="modal-close" onclick="closeModal('engineers-modal')">&#x2715;</button>
                    </div>
                    <div class="modal-body" style="padding:0;">
                        <table>
                            <thead>
                                <tr>
                                    <th>Developer / Role</th>
                                    <th>Work Mode</th>
                                    <th>Tech Stack</th>
                                    <th>Base Location</th>
                                    <th>Company Email</th>
                                </tr>
                            </thead>
                            <tbody>
                                """ + recordRows.toString() + """
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

            <script>
                function openModal(id) {
                    document.getElementById(id).classList.add('open');
                    document.body.style.overflow = 'hidden';
                }
                function closeModal(id) {
                    document.getElementById(id).classList.remove('open');
                    document.body.style.overflow = '';
                }
                function overlayClose(e, id) {
                    if (e.target === e.currentTarget) closeModal(id);
                }
                document.addEventListener('keydown', function(e) {
                    if (e.key === 'Escape') {
                        document.querySelectorAll('.modal-overlay.open').forEach(function(m) {
                            closeModal(m.id);
                        });
                    }
                });
                // Auto-open engineers modal after successful onboard
                const params = new URLSearchParams(window.location.search);
                if (params.get('registered') === '1') openModal('engineers-modal');
            </script>
            </body>
            </html>
            """;

        return topHtml;
    }

    @PostMapping(value = "/submit", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> submit(
            @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "") String workMode,
            @RequestParam(defaultValue = "") String yearsOfExperience,
            @RequestParam(defaultValue = "") String hireDate,
            @RequestParam(defaultValue = "") String officeLocation,
            @RequestParam(defaultValue = "") String primarySkill,
            @RequestParam(defaultValue = "") String slackUsername,
            @RequestParam(defaultValue = "") String corporateEmail,
            @RequestParam(defaultValue = "") String jobTitle) {
        try {
            employeeService.save(name, workMode, yearsOfExperience, hireDate,
                    officeLocation, primarySkill, slackUsername, corporateEmail, jobTitle);
        } catch (Exception e) {
            // continue
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "/?registered=1")
                .build();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
