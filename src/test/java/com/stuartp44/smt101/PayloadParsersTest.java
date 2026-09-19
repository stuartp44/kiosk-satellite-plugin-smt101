package com.stuartp44.smt101;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadParsersTest {
    @Test
    void parsesPlainNumericPayload() {
        Double value = PayloadParsers.parseNumeric("23.5", new String[]{"temperature"});
        assertEquals(23.5d, value.doubleValue(), 0.0001d);
    }

    @Test
    void parsesJsonNumericPayloadByPreferredKey() {
        Double value = PayloadParsers.parseNumeric("{\"humidity\":45}", new String[]{"humidity"});
        assertEquals(45.0d, value.doubleValue(), 0.0001d);
    }

    @Test
    void parsesJsonNumericPayloadByFallbackKey() {
        Double value = PayloadParsers.parseNumeric("{\"value\":120}", new String[]{"lux"});
        assertEquals(120.0d, value.doubleValue(), 0.0001d);
    }

    @Test
    void rejectsMalformedNumericPayload() {
        assertNull(PayloadParsers.parseNumeric("{\"temperature\":\"warm\"}", new String[]{"temperature"}));
    }

    @Test
    void rejectsMalformedBracedNumericPayload() {
        assertNull(PayloadParsers.parseNumeric("{\"lux\":120 garbage}", new String[]{"lux"}));
    }

    @Test
    void parsesBooleanLikeStrings() {
        assertTrue(PayloadParsers.parseBoolean("ON", new String[]{"state"}));
        assertEquals(Boolean.FALSE, PayloadParsers.parseBoolean("0", new String[]{"state"}));
    }

    @Test
    void parsesJsonBooleanPayload() {
        assertEquals(Boolean.TRUE, PayloadParsers.parseBoolean("{\"state\":\"true\"}", new String[]{"state"}));
        assertEquals(Boolean.FALSE, PayloadParsers.parseBoolean("{\"input\":\"OFF\"}", new String[]{"input"}));
    }

    @Test
    void parsesUnicodeEscapedJsonStrings() {
        assertEquals(Boolean.TRUE, PayloadParsers.parseBoolean("{\"state\":\"\\u004f\\u004e\"}", new String[]{"state"}));
    }

    @Test
    void rejectsMalformedBracedBooleanPayload() {
        assertNull(PayloadParsers.parseBoolean("{\"state\":\"ON\" garbage}", new String[]{"state"}));
    }

    @Test
    void rejectsMalformedBooleanPayload() {
        assertNull(PayloadParsers.parseBoolean("maybe", new String[]{"state"}));
    }
}
