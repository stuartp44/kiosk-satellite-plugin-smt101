package com.stuartp44.smt101;

import java.util.Map;

final class Smt101Config {
    private final boolean automaticMqttRecoveryEnabled;
    private final boolean reenableMqttEnabled;

    private Smt101Config(
            boolean automaticMqttRecoveryEnabled,
            boolean reenableMqttEnabled) {
        this.automaticMqttRecoveryEnabled = automaticMqttRecoveryEnabled;
        this.reenableMqttEnabled = reenableMqttEnabled;
    }

    static Smt101Config fromSettings(Map<String, Object> settings) {
        return new Smt101Config(
                getBoolean(settings, "enableAutomaticMqttRecovery", false),
                getBoolean(settings, "reenableDisabledMqtt", false));
    }

    Smt101ResolvedConfig resolve() {
        return new Smt101ResolvedConfig(
                automaticMqttRecoveryEnabled,
                reenableMqttEnabled);
    }

    private static boolean getBoolean(Map<String, Object> settings, String key, boolean fallback) {
        if (settings == null) {
            return fallback;
        }
        Object value = settings.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return fallback;
    }

}
