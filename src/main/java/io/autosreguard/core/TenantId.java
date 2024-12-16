package io.autosreguard.core;

import java.util.Objects;

/**
 * Strongly-typed tenant identifier.
 *
 * <p>We deliberately avoid passing raw {@code String}s around: mixing up a tenant id with a
 * model name or region in a per-tenant SLO pipeline is exactly the kind of silent bug that
 * makes a dashboard lie.
 *
 * @param value non-blank identifier, normalized to lower case
 */
public record TenantId(String value) {

    public TenantId {
        Objects.requireNonNull(value, "tenant id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("tenant id must not be blank");
        }
        value = value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
