package com.stuartp44.smt101;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Smt101ConfigTest {
    @Test
    void enablesEmbeddedBrokerByDefault() {
        Smt101Config config = Smt101Config.fromSettings(null);
        Smt101ResolvedConfig resolved = config.resolve();
        assertFalse(resolved.isAutomaticMqttRecoveryEnabled());
        assertFalse(resolved.isReenableMqttEnabled());
    }

    @Test
    void automaticRecoveryIsOptIn() {
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("enableAutomaticMqttRecovery", Boolean.TRUE);
        assertTrue(Smt101Config.fromSettings(settings).resolve().isAutomaticMqttRecoveryEnabled());
        settings.put("enableAutomaticMqttRecovery", "false");
        assertFalse(Smt101Config.fromSettings(settings).resolve().isAutomaticMqttRecoveryEnabled());
    }

    @Test
    void reenableDisabledMqttIsIndependentlyOptIn() {
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("reenableDisabledMqtt", "true");
        Smt101ResolvedConfig resolved = Smt101Config.fromSettings(settings).resolve();
        assertTrue(resolved.isReenableMqttEnabled());
        assertFalse(resolved.isAutomaticMqttRecoveryEnabled());
        settings.put("reenableDisabledMqtt", Boolean.FALSE);
        assertFalse(Smt101Config.fromSettings(settings).resolve().isReenableMqttEnabled());
    }

}
