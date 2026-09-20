package com.stuartp44.smt101;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Smt101ConfigTest {
    @Test
    void enablesEmbeddedBrokerByDefault() {
        Smt101Config config = Smt101Config.fromSettings(null);
        Smt101ResolvedConfig resolved = config.resolve();
        assertTrue(resolved.isEmbeddedMqttBrokerEnabled());
        assertNull(resolved.getMqttTopicPrefix());
        assertTrue(resolved.isMqttTopicPrefixValid());
    }

    @Test
    void normalizesAnOptionalConfiguredTopicPrefix() {
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("mqttClientId", " /ground-floor/office/ ");

        Smt101ResolvedConfig resolved = Smt101Config.fromSettings(settings).resolve();

        assertEquals("ground-floor/office", resolved.getMqttTopicPrefix());
        assertTrue(resolved.isMqttTopicPrefixValid());
    }

    @Test
    void rejectsMqttWildcardsInConfiguredTopicPrefix() {
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("mqttClientId", "office/+");

        Smt101ResolvedConfig resolved = Smt101Config.fromSettings(settings).resolve();

        assertNull(resolved.getMqttTopicPrefix());
        assertFalse(resolved.isMqttTopicPrefixValid());
    }
}
