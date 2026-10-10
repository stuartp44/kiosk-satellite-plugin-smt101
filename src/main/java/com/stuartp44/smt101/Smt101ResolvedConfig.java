package com.stuartp44.smt101;

final class Smt101ResolvedConfig {
    private final boolean embeddedMqttBrokerEnabled;
    private final boolean automaticMqttRecoveryEnabled;
    private final boolean reenableMqttEnabled;
    private final String mqttTopicPrefix;
    private final boolean mqttTopicPrefixValid;

    Smt101ResolvedConfig(
            boolean embeddedMqttBrokerEnabled,
            boolean automaticMqttRecoveryEnabled,
            boolean reenableMqttEnabled,
            String mqttTopicPrefix,
            boolean mqttTopicPrefixValid) {
        this.embeddedMqttBrokerEnabled = embeddedMqttBrokerEnabled;
        this.automaticMqttRecoveryEnabled = automaticMqttRecoveryEnabled;
        this.reenableMqttEnabled = reenableMqttEnabled;
        this.mqttTopicPrefix = mqttTopicPrefix;
        this.mqttTopicPrefixValid = mqttTopicPrefixValid;
    }

    boolean isEmbeddedMqttBrokerEnabled() {
        return embeddedMqttBrokerEnabled;
    }

    boolean isAutomaticMqttRecoveryEnabled() {
        return automaticMqttRecoveryEnabled;
    }

    boolean isReenableMqttEnabled() {
        return reenableMqttEnabled;
    }

    String getMqttTopicPrefix() {
        return mqttTopicPrefix;
    }

    boolean isMqttTopicPrefixValid() {
        return mqttTopicPrefixValid;
    }
}
