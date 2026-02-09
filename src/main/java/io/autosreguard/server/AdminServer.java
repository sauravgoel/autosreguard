package io.autosreguard.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.autosreguard.core.Json;
import io.autosreguard.remediation.AuditLog;
import io.autosreguard.remediation.AuditRecord;
import io.autosreguard.remediation.RemediationEngine;
import io.autosreguard.runtime.AutoSREGuard;
import io.autosreguard.slo.SloStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * Minimal admin/observability HTTP API on the JDK's built-in server.
 *
 * <pre>
 *   GET  /healthz                              liveness
 *   GET  /metrics                              Prometheus scrape
 *   GET  /v1/slo                               current SLO statuses (firing first)
 *   GET  /v1/audit                             recent remediation audit records
 *   POST /v1/remediations/{id}/approve?by=     approve a proposal early
 *   POST /v1/remediations/{id}/veto?by=&amp;reason=  veto a proposal
 *   POST /v1/remediations/{id}/revert?by=      undo an executed action
 * </pre>
 *
 * <h2>Security</h2>
 * Binds to loopback by default. The mutating endpoints change production; expose them only
 * behind an authenticating proxy that sets a verified identity, and map that identity to
 * {@code by}. Never expose this port publicly.
 */
public final class AdminServer implements AutoCloseable {

    private final HttpServer server;
    private final AutoSREGuard guard;
    private final AuditLog audit;

    public AdminServer(String bindHost, int port, AutoSREGuard guard, AuditLog audit) throws IOException {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "autosreguard-admin");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/healthz", ex -> respond(ex, 200, "text/plain", "ok\n"));
        server.createContext("/metrics", ex -> respond(ex, 200, "text/plain; version=0.0.4",
                guard.metrics().scrape()));
        server.createContext("/v1/slo", ex -> respond(ex, 200, "application/json", sloJson()));
        server.createContext("/v1/audit", ex -> respond(ex, 200, "application/json", auditJson()));
        server.createContext("/v1/remediations/", this::handleRemediation);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private String sloJson() {
        List<Map<String, Object>> out = guard.lastStatuses().stream().map(AdminServer::toMap).toList();
        return Json.write(out);
    }

    private static Map<String, Object> toMap(SloStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slo", s.slo().name());
        m.put("scope", s.scope().label());
        m.put("severity", s.severity().name());
        m.put("errorRate", s.errorRateInHorizon());
        m.put("budgetRemaining", s.budgetRemaining());
        m.put("burnRate", s.longBurnRate());
        m.put("maskedByAggregate", s.maskedByAggregate());
        m.put("rule", s.firingRule());
        return m;
    }

    private String auditJson() {
        StringBuilder sb = new StringBuilder("[");
        List<AuditRecord> recs = audit.recent();
        for (int i = 0; i < recs.size(); i++) {
            sb.append(recs.get(i).toJson());
            if (i < recs.size() - 1) {
                sb.append(',');
            }
        }
        return sb.append(']').toString();
    }

    private void handleRemediation(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "POST required\n");
            return;
        }
        // Path: /v1/remediations/{id}/{verb}
        String[] parts = ex.getRequestURI().getPath().split("/");
        if (parts.length != 5) {
            respond(ex, 404, "text/plain", "not found\n");
            return;
        }
        String id = parts[3];
        String verb = parts[4];
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String by = q.get("by");
        if (by == null || by.isBlank()) {
            respond(ex, 400, "text/plain", "'by' is required: every human action is attributed\n");
            return;
        }
        RemediationEngine engine = guard.engine();
        try {
            switch (verb) {
                case "approve" -> engine.approve(id, by);
                case "veto" -> engine.veto(id, by, q.getOrDefault("reason", ""));
                case "revert" -> engine.revert(id, by);
                default -> {
                    respond(ex, 404, "text/plain", "unknown action " + verb + "\n");
                    return;
                }
            }
            respond(ex, 200, "application/json",
                    audit.latest(id).map(AuditRecord::toJson).orElse("{}"));
        } catch (IllegalArgumentException e) {
            respond(ex, 404, "text/plain", e.getMessage() + "\n");
        } catch (Exception e) {
            respond(ex, 500, "text/plain", "failed: " + e.getMessage() + "\n");
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static void respond(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(1);
    }
}
