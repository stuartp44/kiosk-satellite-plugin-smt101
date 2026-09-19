package com.stuartp44.smt101;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Smt101MessageProcessorTest {
    private final Smt101MessageProcessor processor = new Smt101MessageProcessor();

    @Test
    void routesTemperatureTopicWithPlainPayload() {
        ProcessedMessage message = processor.process(config("smt101", true, true), "smt101/sensor/temperature", "23.5");
        assertTrue(message.isMatched());
        assertTrue(message.isValid());
        assertEquals(Smt101Topic.TEMPERATURE, message.getTopic());
        assertEquals(23.5d, message.getNumericValue().doubleValue(), 0.0001d);
    }

    @Test
    void routesLightTopicWithJsonPayload() {
        ProcessedMessage message = processor.process(config("panel/smt101", true, true), "panel/smt101/sensor/light", "{\"lux\":120}");
        assertEquals(Smt101Topic.LIGHT, message.getTopic());
        assertEquals(120.0d, message.getNumericValue().doubleValue(), 0.0001d);
    }

    @Test
    void routesInputTopicWithBooleanPayload() {
        ProcessedMessage message = processor.process(config("/custom/prefix/", true, true), "custom/prefix/input2/state", "{\"state\":\"OFF\"}");
        assertEquals(Smt101Topic.INPUT2, message.getTopic());
        assertEquals(Boolean.FALSE, message.getBinaryValue());
    }

    @Test
    void ignoresDisabledEntityGroups() {
        ProcessedMessage environment = processor.process(config("smt101", false, true), "smt101/sensor/humidity", "45");
        ProcessedMessage input = processor.process(config("smt101", true, false), "smt101/input1/state", "ON");
        assertFalse(environment.isMatched());
        assertFalse(input.isMatched());
    }

    @Test
    void rejectsMalformedMappedPayload() {
        ProcessedMessage message = processor.process(config("smt101", true, true), "smt101/input1/state", "{\"state\":\"invalid\"}");
        assertTrue(message.isMatched());
        assertFalse(message.isValid());
        assertEquals(Smt101Topic.INPUT1, message.getTopic());
        assertNull(message.getBinaryValue());
    }

    @Test
    void ignoresUnknownTopic() {
        ProcessedMessage message = processor.process(config("smt101", true, true), "smt101/relay1/state", "ON");
        assertFalse(message.isMatched());
        assertFalse(message.isValid());
        assertNull(message.getTopic());
        assertNull(message.getNumericValue());
        assertNull(message.getBinaryValue());
    }

    private static Smt101Config config(String topicPrefix, boolean environmentSensors, boolean inputs) {
        Map<String, Object> settings = new HashMap<String, Object>();
        settings.put("topicPrefix", topicPrefix);
        settings.put("enableEnvironmentSensors", Boolean.valueOf(environmentSensors));
        settings.put("enableInputs", Boolean.valueOf(inputs));
        Smt101Config config = Smt101Config.fromSettings(settings);
        assertNotNull(config);
        return config;
    }
}
