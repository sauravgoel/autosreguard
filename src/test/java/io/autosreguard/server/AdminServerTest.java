package io.autosreguard.server;

import io.autosreguard.config.AutoSREGuardConfig;
import io.autosreguard.core.Clocks;
import io.autosreguard.diagnosis.ChangeLog;
import io.autosreguard.diagnosis.ServiceGraph;
import io.autosreguard.remediation.AuditLog;
import io.autosreguard.remediation.FleetState;
import io.autosreguard.runtime.AutoSREGuard;
import io.autosreguard.runtime.AutoSREGuardFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminServerTest {

    private AdminServer server;
    private AutoSREGuard guard;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        FleetState fleet = new FleetState() {
            @Override
            public int replicas(String pool) {
                return 1;
            }

            @Override
            public double sheddingFraction(String service) {
                return 0;
            }

            @Override
            public int maxReplicas(String pool) {
                return 1;
            }
        };
        AuditLog audit = AuditLog.inMemory(10);
        guard = AutoSREGuardFactory.create(AutoSREGuardConfig.of(new Properties(), Map.of()),
                Clocks.manual(Instant.parse("2026-04-17T00:00:00Z")), new ServiceGraph(), new ChangeLog(10), fleet,
                a -> { }, n -> { }, audit);
        guard.runCycle();
        server = new AdminServer("127.0.0.1", 0, guard, audit);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
        guard.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthAndMetricsEndpointsRespond() throws Exception {
        assertEquals(200, get("/healthz").statusCode());
        HttpResponse<String> metrics = get("/metrics");
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("autosreguard_control_cycles_total 1"), metrics.body());
    }

    @Test
    void sloAndAuditEndpointsReturnJsonArrays() throws Exception {
        assertTrue(get("/v1/slo").body().startsWith("["));
        assertEquals("[]", get("/v1/audit").body().trim());
    }

    @Test
    void humanActionsMustBeAttributed() throws Exception {
        assertEquals(400, post("/v1/remediations/rem-000001/approve").statusCode());
    }

    @Test
    void unknownProposalsAndWrongMethodsAreRejected() throws Exception {
        assertEquals(404, post("/v1/remediations/rem-424242/approve?by=alice").statusCode());
        assertEquals(405, get("/v1/remediations/rem-000001/approve?by=alice").statusCode());
        assertEquals(404, post("/v1/remediations/rem-000001/explode?by=alice").statusCode());
    }
}
