package io.autosreguard.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Typed, validated configuration loaded from a {@code .properties} file with environment
 * variable overrides.
 *
 * <p>Override rule: property {@code slo.enterprise.availability} can be overridden by env var
 * {@code AUTOSREGUARD_SLO_ENTERPRISE_AVAILABILITY}. This keeps container deployments
 * twelve-factor without a config library.
 *
 * <p>Every getter validates and fails fast with the key name in the message. A typo in a
 * reliability threshold should crash the process at startup, not surface during an incident.
 */
public final class AutoSREGuardConfig {

    private final Properties props;
    private final Map<String, String> env;

    private AutoSREGuardConfig(Properties props, Map<String, String> env) {
        this.props = props;
        this.env = env;
    }

    public static AutoSREGuardConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return new AutoSREGuardConfig(p, System.getenv());
    }

    /** Built-in defaults only (plus env overrides). */
    public static AutoSREGuardConfig defaults() {
        return new AutoSREGuardConfig(new Properties(), System.getenv());
    }

    /** For tests: explicit properties and environment. */
    public static AutoSREGuardConfig of(Properties props, Map<String, String> env) {
        return new AutoSREGuardConfig(Objects.requireNonNull(props), Objects.requireNonNull(env));
    }

    static String envKey(String key) {
        return "AUTOSREGUARD_" + key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private String raw(String key) {
        String e = env.get(envKey(key));
        return e != null ? e.trim() : props.getProperty(key) == null ? null : props.getProperty(key).trim();
    }

    public String string(String key, String def) {
        String v = raw(key);
        return v == null || v.isEmpty() ? def : v;
    }

    public double dbl(String key, double def) {
        String v = raw(key);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("config " + key + ": not a number: '" + v + "'", e);
        }
    }

    public int integer(String key, int def) {
        String v = raw(key);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("config " + key + ": not an integer: '" + v + "'", e);
        }
    }

    /**
     * Durations accept ISO-8601 ({@code PT5M}) or a shorthand: {@code 500ms}, {@code 30s},
     * {@code 5m}, {@code 6h}, {@code 1d}.
     */
    public Duration duration(String key, Duration def) {
        String v = raw(key);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return parseDuration(v);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("config " + key + ": bad duration '" + v + "'", e);
        }
    }

    static Duration parseDuration(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("p")) {
            return Duration.parse(s.toUpperCase(Locale.ROOT));
        }
        if (s.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2)));
        }
        long n = Long.parseLong(s.substring(0, s.length() - 1));
        return switch (s.charAt(s.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("unknown unit in " + v);
        };
    }

    // ---- Typed accessors for the knobs the runtime uses --------------------------------

    public int adminPort() { return integer("admin.port", 9464); }
    /** Loopback by default: the admin API can approve and revert production changes. */
    public String adminBindHost() { return string("admin.bind-host", "127.0.0.1"); }
    public Duration controlLoopInterval() { return duration("control.interval", Duration.ofSeconds(15)); }
    public Duration sloBucketWidth() { return duration("slo.bucket-width", Duration.ofMinutes(1)); }
    public long sloMinEvents() { return integer("slo.min-events", 100); }
    public double autoThreshold() { return dbl("remediation.auto-threshold", 0.85); }
    public double suggestThreshold() { return dbl("remediation.suggest-threshold", 0.6); }
    public Duration vetoWindow() { return duration("remediation.veto-window", Duration.ofMinutes(3)); }
    public Duration cooldown() { return duration("remediation.cooldown", Duration.ofMinutes(5)); }
    public int maxActionsPer10m() { return integer("remediation.max-actions-per-10m", 10); }
    public String auditFile() { return string("audit.file", "autosreguard-audit.jsonl"); }
    public boolean dryRun() { return Boolean.parseBoolean(string("remediation.dry-run", "true")); }
}
