package com.stuartp44.smt101;

import java.util.Map;

final class Smt101Config {
    private static final boolean DEFAULT_ENABLE_EMBEDDED_MQTT_BROKER = true;

    private final boolean embeddedMqttBrokerEnabled;
    private final String mqttTopicPrefix;
    private final boolean mqttTopicPrefixValid;

    private Smt101Config(
            boolean embeddedMqttBrokerEnabled,
            String mqttTopicPrefix,
            boolean mqttTopicPrefixValid) {
        this.embeddedMqttBrokerEnabled = embeddedMqttBrokerEnabled;
        this.mqttTopicPrefix = mqttTopicPrefix;
        this.mqttTopicPrefixValid = mqttTopicPrefixValid;
    }

    static Smt101Config fromSettings(Map<String, Object> settings) {
        String configuredPrefix = getString(settings, "mqttClientId");
        String normalizedPrefix = normalizeTopicPrefix(configuredPrefix);
        return new Smt101Config(
                getBoolean(settings, "enableEmbeddedMqttBroker", DEFAULT_ENABLE_EMBEDDED_MQTT_BROKER),
                normalizedPrefix,
                configuredPrefix.isEmpty() || normalizedPrefix != null);
    }

    Smt101ResolvedConfig resolve() {
        return new Smt101ResolvedConfig(
                embeddedMqttBrokerEnabled,
                mqttTopicPrefix,
                mqttTopicPrefixValid);
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

    private static String getString(Map<String, Object> settings, String key) {
        if (settings == null) {
            return "";
        }
        Object value = settings.get(key);
        return value instanceof String ? ((String) value).trim() : "";
    }

    static String normalizeTopicPrefix(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.indexOf('\u0000') >= 0
                        || normalized.indexOf('+') >= 0
                        || normalized.indexOf('#') >= 0
                ? null
                : normalized;
    }
}
