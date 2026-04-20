package io.autosreguard.demo;

import io.autosreguard.config.AutoSREGuardConfig;
import io.autosreguard.core.Segment;
import io.autosreguard.core.TenantId;
import io.autosreguard.detection.EwmaAnomalyDetector;
import io.autosreguard.detection.Signals;
import io.autosreguard.diagnosis.ChangeLog;
import io.autosreguard.diagnosis.ServiceGraph;
import io.autosreguard.gpu.GpuTelemetry;
import io.autosreguard.remediation.AuditLog;
import io.autosreguard.remediation.Notifier;
import io.autosreguard.runtime.AutoSREGuard;
import io.autosreguard.runtime.AutoSREGuardFactory;
import io.autosreguard.server.AdminServer;
import io.autosreguard.slo.InferenceOutcome;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Command-line entrypoint.
 *
 * <pre>
 *   java -jar autosreguard.jar                         # list scenarios
 *   java -jar autosreguard.jar --scenario=all          # run every scenario (used as CI smoke test)
 *   java -jar autosreguard.jar --scenario=bad-deploy   # run one
 *   java -jar autosreguard.jar --serve [--config=path] # long-running control loop + admin API
 * </pre>
 *
 * <p>{@code --serve} wires the real runtime ({@link AutoSREGuardFactory}) to a synthetic traffic
 * generator and a {@link SimulatedFleet}, so the admin API, metrics endpoint and audit log can
 * be explored end to end. In a real deployment you would replace the generator with your
 * telemetry pipeline and the fleet with executors for your orchestrator.
 */
public final class SimulationMain {

    private static final Map<String, Scenario> SCENARIOS = new LinkedHashMap<>();

    static {
        for (Scenario s : List.of(new HiddenTenantScenario(), new BadDeployScenario(), new GpuDegradationScenario(),
                new PredictiveScalingScenario(), new GracefulDegradationScenario(), new CanaryScenario(),
                new ShadowScenario())) {
            SCENARIOS.put(s.name(), s);
        }
    }

    private SimulationMain() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = null;
        String config = null;
        boolean serve = false;
        for (String a : args) {
            if (a.startsWith("--scenario=")) {
                scenario = a.substring("--scenario=".length());
            } else if (a.startsWith("--config=")) {
                config = a.substring("--config=".length());
            } else if (a.equals("--serve")) {
                serve = true;
            } else if (a.equals("--help") || a.equals("-h")) {
                usage(System.out);
                return;
            } else {
                System.err.println("unknown argument: " + a);
                usage(System.err);
                System.exit(2);
            }
        }
        if (serve) {
            serve(config == null ? AutoSREGuardConfig.defaults() : AutoSREGuardConfig.load(Path.of(config)));
            return;
        }
        if (scenario == null) {
            usage(System.out);
            return;
        }
        Console console = new Console(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        if (scenario.equals("all")) {
            for (Scenario s : SCENARIOS.values()) {
                s.run(console);
            }
            return;
        }
        Scenario s = SCENARIOS.get(scenario);
        if (s == null) {
            System.err.println("unknown scenario: " + scenario);
            usage(System.err);
            System.exit(2);
        }
        s.run(console);
    }

    private static void usage(PrintStream out) {
        out.println("AutoSREGuard: SRE tooling for AI inference");
        out.println();
        out.println("usage:");
        out.println("  --scenario=<name>|all     run a deterministic demo scenario");
        out.println("  --serve [--config=path]   run the control loop and admin API against simulated traffic");
        out.println();
        out.println("scenarios: " + String.join(", ", SCENARIOS.keySet()));
    }

    // ---- serve mode ----------------------------------------------------------------------

    private static void serve(AutoSREGuardConfig cfg) throws Exception {
        Clock clock = Clock.systemUTC();
        ServiceGraph graph = new ServiceGraph()
                .dependsOn("api-gateway", "inference-router")
                .dependsOn("inference-router", "pool-llama-70b")
                .dependsOn("inference-router", "pool-llama-8b");
        ChangeLog changes = new ChangeLog(10_000);
        SimulatedFleet fleet = new SimulatedFleet();
        AuditLog audit = AuditLog.jsonLines(Path.of(cfg.auditFile()), 1_000);
        AutoSREGuard guard = AutoSREGuardFactory.create(cfg, clock, graph, changes, fleet, fleet,
                Notifier.logging(), audit);
        for (String component : List.of("api-gateway", "inference-router", "pool-llama-70b", "pool-llama-8b")) {
            guard.registerDetector(EwmaAnomalyDetector.builder(component, Signals.ERROR_RATE)
                    .minSigma(0.002).build());
        }

        AdminServer admin = new AdminServer(cfg.adminBindHost(), cfg.adminPort(), guard, audit);
        admin.start();
        guard.start(cfg.controlLoopInterval());

        ScheduledExecutorService traffic = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "autosreguard-synthetic-traffic");
            t.setDaemon(true);
            return t;
        });
        SplittableRandom rnd = new SplittableRandom();
        traffic.scheduleAtFixedRate(() -> generateSecond(guard, rnd, clock.instant()), 0, 1, TimeUnit.SECONDS);

        System.out.printf("AutoSREGuard serving admin API on http://%s:%d  (dry-run=%s, audit=%s)%n",
                cfg.adminBindHost(), admin.port(), cfg.dryRun(), cfg.auditFile());
        System.out.println("  GET  /healthz  /metrics  /v1/slo  /v1/audit");
        System.out.println("  POST /v1/remediations/{id}/approve|veto|revert?by=<you>&reason=<why>");

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            traffic.shutdownNow();
            admin.close();
            guard.close();
            try {
                audit.close();
            } catch (Exception e) {
                System.err.println("failed to close audit log: " + e);
            }
            stop.countDown();
        }, "autosreguard-shutdown"));
        stop.await();
    }

    /**
     * One second of synthetic traffic: three segments, a few tenants each, a small background
     * error rate, and healthy GPU telemetry. Enough to populate metrics and SLO endpoints.
     */
    private static void generateSecond(AutoSREGuard guard, SplittableRandom rnd, Instant now) {
        try {
            for (int i = 0; i < 50; i++) {
                Segment seg = Segment.values()[rnd.nextInt(Segment.values().length)];
                TenantId tenant = TenantId.of(seg.name().toLowerCase() + "-tenant-" + rnd.nextInt(5));
                String model = seg == Segment.CONSUMER ? "llama-8b" : "llama-70b";
                boolean ok = rnd.nextDouble() > 0.0005;
                guard.recordOutcome(ok
                        ? InferenceOutcome.success(now, tenant, seg, model, "us-east",
                                200 + rnd.nextDouble() * 300, 15 + rnd.nextDouble() * 15, 200)
                        : InferenceOutcome.failure(now, tenant, seg, model, "us-east"));
            }
            for (String c : List.of("api-gateway", "inference-router", "pool-llama-70b", "pool-llama-8b")) {
                guard.observeSignal(c, Signals.ERROR_RATE, now, rnd.nextDouble() * 0.001);
            }
            for (int n = 1; n <= 4; n++) {
                guard.observeGpu(new GpuTelemetry(String.format("gpu-node-%02d", n), now, 0, 0,
                        60 + rnd.nextDouble() * 10, 0.0, 0.6 + rnd.nextDouble() * 0.2, 0));
            }
        } catch (RuntimeException e) {
            // Never let the generator die silently; a scheduled task that throws is cancelled.
            System.err.println("traffic generator error: " + e);
        }
    }
}
