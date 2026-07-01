package io.autosreguard.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSREGuardConfigTest {

    @Test
    void defaultsAreSafe() {
        AutoSREGuardConfig c = AutoSREGuardConfig.defaults();
        assertTrue(c.dryRun(), "a fresh install must observe, not act");
        assertEquals("127.0.0.1", c.adminBindHost());
        assertEquals(9464, c.adminPort());
        assertEquals(0.85, c.autoThreshold(), 1e-9);
        assertEquals(Duration.ofMinutes(3), c.vetoWindow());
    }

    @Test
    void environmentOverridesProperties() {
        Properties p = new Properties();
        p.setProperty("remediation.dry-run", "true");
        p.setProperty("admin.port", "1000");
        AutoSREGuardConfig c = AutoSREGuardConfig.of(p, Map.of("AUTOSREGUARD_REMEDIATION_DRY_RUN", "false"));
        assertEquals(false, c.dryRun());
        assertEquals(1000, c.adminPort());
    }

    @Test
    void parsesShorthandAndIsoDurations() {
        assertEquals(Duration.ofMillis(500), AutoSREGuardConfig.parseDuration("500ms"));
        assertEquals(Duration.ofSeconds(30), AutoSREGuardConfig.parseDuration("30s"));
        assertEquals(Duration.ofMinutes(5), AutoSREGuardConfig.parseDuration("5m"));
        assertEquals(Duration.ofHours(6), AutoSREGuardConfig.parseDuration("6h"));
        assertEquals(Duration.ofDays(1), AutoSREGuardConfig.parseDuration("1d"));
        assertEquals(Duration.ofMinutes(90), AutoSREGuardConfig.parseDuration("PT1H30M"));
    }

    @Test
    void badValuesFailLoudlyWithTheKeyName() {
        Properties p = new Properties();
        p.setProperty("admin.port", "http");
        p.setProperty("remediation.cooldown", "5 minutes");
        AutoSREGuardConfig c = AutoSREGuardConfig.of(p, Map.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::adminPort);
        assertTrue(e.getMessage().contains("admin.port"));
        assertThrows(IllegalArgumentException.class, c::cooldown);
    }

    @Test
    void loadsFromAPropertiesFile() throws Exception {
        Path f = Files.createTempFile("autosreguard", ".properties");
        Files.writeString(f, "control.interval=5s\nremediation.auto-threshold=0.9\n");
        AutoSREGuardConfig c = AutoSREGuardConfig.load(f);
        assertEquals(Duration.ofSeconds(5), c.controlLoopInterval());
        assertEquals(0.9, c.autoThreshold(), 1e-9);
        Files.deleteIfExists(f);
    }

    @Test
    void envKeysAreDerivedMechanically() {
        assertEquals("AUTOSREGUARD_SLO_ENTERPRISE_TTFT_THRESHOLD_MS", AutoSREGuardConfig.envKey("slo.enterprise.ttft.threshold-ms"));
    }
}
