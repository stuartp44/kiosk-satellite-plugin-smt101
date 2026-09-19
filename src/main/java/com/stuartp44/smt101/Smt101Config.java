package com.stuartp44.smt101;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Smt101Config {
    private static final int DEFAULT_PLAIN_PORT = 1883;
    private static final int DEFAULT_TLS_PORT = 8883;

    private final String mqttHost;
    private final int mqttPort;
    private final String mqttUsername;
    private final String mqttPassword;
    private final boolean mqttTls;
    private final String mqttClientId;
    private final String topicPrefix;
    private final boolean enableEnvironmentSensors;
    private final boolean enableInputs;

    private Smt101Config(
            String mqttHost,
            int mqttPort,
            String mqttUsername,
            String mqttPassword,
            boolean mqttTls,
            String mqttClientId,
            String topicPrefix,
            boolean enableEnvironmentSensors,
            boolean enableInputs) {
        this.mqttHost = mqttHost;
        this.mqttPort = mqttPort;
        this.mqttUsername = mqttUsername;
        this.mqttPassword = mqttPassword;
        this.mqttTls = mqttTls;
        this.mqttClientId = mqttClientId;
        this.topicPrefix = topicPrefix;
        this.enableEnvironmentSensors = enableEnvironmentSensors;
        this.enableInputs = enableInputs;
    }

    static Smt101Config fromSettings(Map<String, Object> settings) {
        boolean tls = getBoolean(settings, "mqttTls", false);
        int port = getInteger(settings, "mqttPort", tls ? DEFAULT_TLS_PORT : DEFAULT_PLAIN_PORT);
        if (port < 1 || port > 65535) {
            port = tls ? DEFAULT_TLS_PORT : DEFAULT_PLAIN_PORT;
        }
        return new Smt101Config(
                getString(settings, "mqttHost", ""),
                port,
                getString(settings, "mqttUsername", ""),
                getString(settings, "mqttPassword", ""),
                tls,
                getString(settings, "mqttClientId", ""),
                normalizeTopicPrefix(getString(settings, "topicPrefix", "smt101")),
                getBoolean(settings, "enableEnvironmentSensors", true),
                getBoolean(settings, "enableInputs", true));
    }

    String getMqttHost() {
        return mqttHost;
    }

    int getMqttPort() {
        return mqttPort;
    }

    String getMqttUsername() {
        return mqttUsername;
    }

    String getMqttPassword() {
        return mqttPassword;
    }

    boolean isMqttTls() {
        return mqttTls;
    }

    String getMqttClientId() {
        return mqttClientId;
    }

    String getTopicPrefix() {
        return topicPrefix;
    }

    boolean isEnvironmentSensorsEnabled() {
        return enableEnvironmentSensors;
    }

    boolean isInputsEnabled() {
        return enableInputs;
    }

    boolean hasBrokerHost() {
        return !mqttHost.isEmpty();
    }

    String brokerUri() {
        return (mqttTls ? "ssl://" : "tcp://") + mqttHost + ":" + mqttPort;
    }

    String topic(String suffix) {
        return topicPrefix + "/" + suffix;
    }

    String[] subscriptionTopics() {
        List<String> topics = new ArrayList<String>();
        if (enableEnvironmentSensors) {
            topics.add(topic("sensor/temperature"));
            topics.add(topic("sensor/humidity"));
            topics.add(topic("sensor/light"));
        }
        if (enableInputs) {
            topics.add(topic("input1/state"));
            topics.add(topic("input2/state"));
        }
        return topics.toArray(new String[0]);
    }

    private static String normalizeTopicPrefix(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? "smt101" : normalized;
    }

    private static String getString(Map<String, Object> settings, String key, String fallback) {
        if (settings == null) {
            return fallback;
        }
        Object value = settings.get(key);
        return value == null ? fallback : String.valueOf(value).trim();
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
            return Boolean.parseBoolean((String) value);
        }
        return fallback;
    }

    private static int getInteger(Map<String, Object> settings, String key, int fallback) {
        if (settings == null) {
            return fallback;
        }
        Object value = settings.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
