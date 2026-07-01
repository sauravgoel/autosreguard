package io.autosreguard.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantIdTest {

    @Test
    void normalisesCaseAndWhitespaceSoSeriesDoNotSplit() {
        assertEquals(TenantId.of("acme-corp"), TenantId.of("  ACME-Corp "));
        assertEquals("acme-corp", TenantId.of("Acme-Corp").toString());
    }

    @Test
    void rejectsBlankIds() {
        assertThrows(IllegalArgumentException.class, () -> TenantId.of("   "));
        assertThrows(NullPointerException.class, () -> TenantId.of(null));
    }
}
