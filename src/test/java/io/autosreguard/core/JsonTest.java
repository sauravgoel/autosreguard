package io.autosreguard.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {

    @Test
    void writesNestedStructuresInInsertionOrder() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "rem-000001");
        m.put("ok", true);
        m.put("count", 3);
        m.put("tags", List.of("a", "b"));
        m.put("missing", null);
        assertEquals("{\"name\":\"rem-000001\",\"ok\":true,\"count\":3,\"tags\":[\"a\",\"b\"],\"missing\":null}",
                Json.write(m));
    }

    @Test
    void escapesControlCharactersAndQuotes() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", Json.write("a\"b\\c\nd\u0001"));
    }

    @Test
    void nonFiniteDoublesBecomeNullBecauseJsonHasNoNaN() {
        assertEquals("[null,null]", Json.write(Arrays.asList(Double.NaN, Double.POSITIVE_INFINITY)));
    }

    @Test
    void doublesUseLocaleIndependentFormatting() {
        // Six significant digits, always with '.' regardless of the JVM default locale.
        assertEquals("0.500000", Json.write(0.5));
    }
}
