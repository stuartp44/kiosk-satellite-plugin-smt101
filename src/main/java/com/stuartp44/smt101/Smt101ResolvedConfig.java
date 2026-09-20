package com.stuartp44.smt101;

final class Smt101ResolvedConfig {
    private final boolean embeddedMqttBrokerEnabled;
    private final String mqttTopicPrefix;
    private final boolean mqttTopicPrefixValid;

    Smt101ResolvedConfig(
            boolean embeddedMqttBrokerEnabled,
            String mqttTopicPrefix,
            boolean mqttTopicPrefixValid) {
        this.embeddedMqttBrokerEnabled = embeddedMqttBrokerEnabled;
        this.mqttTopicPrefix = mqttTopicPrefix;
        this.mqttTopicPrefixValid = mqttTopicPrefixValid;
    }

    boolean isEmbeddedMqttBrokerEnabled() {
        return embeddedMqttBrokerEnabled;
    }

    String getMqttTopicPrefix() {
        return mqttTopicPrefix;
    }

    boolean isMqttTopicPrefixValid() {
        return mqttTopicPrefixValid;
    }
}
