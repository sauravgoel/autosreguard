package io.autosreguard.slo;

import io.autosreguard.core.Segment;
import io.autosreguard.core.TenantId;

import java.util.Objects;

/**
 * What an SLO evaluation is about: a whole segment, or one tenant inside it.
 *
 * <p>Sealed so that exhaustive {@code switch} statements over scopes stay compile-checked
 * if we ever add, say, a per-region scope.
 */
public sealed interface Scope permits Scope.SegmentScope, Scope.TenantScope {

    Segment segment();

    /** Human/metrics-friendly label, e.g. {@code segment=enterprise} or {@code tenant=acme}. */
    String label();

    record SegmentScope(Segment segment) implements Scope {
        public SegmentScope {
            Objects.requireNonNull(segment, "segment");
        }

        @Override
        public String label() {
            return "segment=" + segment.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    record TenantScope(Segment segment, TenantId tenant) implements Scope {
        public TenantScope {
            Objects.requireNonNull(segment, "segment");
            Objects.requireNonNull(tenant, "tenant");
        }

        @Override
        public String label() {
            return "tenant=" + tenant.value();
        }
    }
}
