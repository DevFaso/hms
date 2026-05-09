package com.example.hms.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the small JSON writer that backs {@link SplunkHecAppender}. The writer is
 * intentionally tiny but it is the only thing standing between us and a malformed HEC payload,
 * so each escape rule has a focused assertion.
 */
class JsonTest {

    @Test
    void writesNullLiteralForNull() {
        assertThat(Json.write(null)).isEqualTo("null");
    }

    @Test
    void writesBooleanLiterals() {
        assertThat(Json.write(true)).isEqualTo("true");
        assertThat(Json.write(false)).isEqualTo("false");
    }

    @Test
    void writesNumberLiterals_integerAndDouble() {
        assertThat(Json.write(42)).isEqualTo("42");
        assertThat(Json.write(1.5)).isEqualTo("1.5");
    }

    @Test
    void writesQuotedStringWithBasicEscapes() {
        assertThat(Json.write("hello")).isEqualTo("\"hello\"");
        assertThat(Json.write("she said \"hi\"")).isEqualTo("\"she said \\\"hi\\\"\"");
        assertThat(Json.write("a\\b")).isEqualTo("\"a\\\\b\"");
    }

    @Test
    void escapesControlCharactersInStrings() {
        assertThat(Json.write("\n")).isEqualTo("\"\\n\"");
        assertThat(Json.write("\r")).isEqualTo("\"\\r\"");
        assertThat(Json.write("\t")).isEqualTo("\"\\t\"");
        assertThat(Json.write("\b")).isEqualTo("\"\\b\"");
        assertThat(Json.write("\f")).isEqualTo("\"\\f\"");
    }

    @Test
    void escapesLowAsciiAsUnicodeEscape() {
        // U+0001 is below 0x20 and has no shortcut → emit as 
        assertThat(Json.write("")).isEqualTo("\"\\u0001\"");
    }

    @Test
    void writesMapsWithStringKeysAndMixedValueTypes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "ada");
        map.put("age", 36);
        map.put("admin", true);
        map.put("note", null);

        assertThat(Json.write(map))
            .isEqualTo("{\"name\":\"ada\",\"age\":36,\"admin\":true,\"note\":null}");
    }

    @Test
    void writesNestedMaps() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("k", "v");
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("nested", inner);

        assertThat(Json.write(outer)).isEqualTo("{\"nested\":{\"k\":\"v\"}}");
    }

    @Test
    void rendersUnknownTypesAsToString() {
        // Anything that isn't null/Boolean/Number/Map/String falls through to String.valueOf
        // and is then quoted. This guarantees we never emit invalid JSON for an unexpected type.
        Object opaque = new Object() {
            @Override
            public String toString() {
                return "opaque-value";
            }
        };
        assertThat(Json.write(opaque)).isEqualTo("\"opaque-value\"");
    }
}
