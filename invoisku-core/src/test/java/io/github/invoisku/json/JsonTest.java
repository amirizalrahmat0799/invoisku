package io.github.invoisku.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void minifiesKeepingKeyOrderAndNumberText() {
        String text = "{ \"b\" : [ 1, 2.50, -0.1, 1.0E+2 ],\n \"a\": {\"_\": \"x\", \"n\": null, \"t\": true} }";
        assertEquals("{\"b\":[1,2.50,-0.1,100],\"a\":{\"_\":\"x\",\"n\":null,\"t\":true}}", Json.minify(text));
    }

    @Test
    void numbersAreBigDecimal() {
        Map<String, Object> m = Json.parseObject("{\"amount\": 1436.50}");
        assertEquals(new BigDecimal("1436.50"), m.get("amount"));
    }

    @Test
    void escapesOnlyWhatJsonRequires() {
        String s = Json.write(List.of("a\"b\\c\n\u0001 / é ü 中"));
        assertEquals("[\"a\\\"b\\\\c\\n\\u0001 / é ü 中\"]", s);
        assertEquals("a\"b\\c\n\u0001 / é ü 中", ((List<?>) Json.parse(s)).get(0));
    }

    @Test
    void readsUnicodeEscapes() {
        assertEquals("&<", Json.parse("\"\\u0026\\u003c\""));
    }

    @Test
    void rejectsBadInputWithPosition() {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse("{\"a\":1,}"));
        assertEquals(7, e.position());
        assertThrows(JsonException.class, () -> Json.parse("[01]"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
        assertThrows(JsonException.class, () -> Json.parse("\"unterminated"));
        assertThrows(JsonException.class, () -> Json.parse("[1] x"));
        assertThrows(JsonException.class, () -> Json.parse("[".repeat(500)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deepCopyIsIndependent() {
        Map<String, Object> m = Json.parseObject("{\"a\":[{\"b\":1}]}");
        Map<String, Object> copy = Json.deepCopy(m);
        ((Map<String, Object>) ((List<?>) copy.get("a")).get(0)).put("b", "changed");
        assertTrue(Json.write(m).contains("\"b\":1"));
    }
}
