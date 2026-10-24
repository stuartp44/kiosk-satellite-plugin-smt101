package com.stuartp44.smt101;

final class Smt101ResolvedConfig {
    private final boolean automaticMqttRecoveryEnabled;
    private final boolean reenableMqttEnabled;

    Smt101ResolvedConfig(
            boolean automaticMqttRecoveryEnabled,
            boolean reenableMqttEnabled) {
        this.automaticMqttRecoveryEnabled = automaticMqttRecoveryEnabled;
        this.reenableMqttEnabled = reenableMqttEnabled;
    }

    boolean isAutomaticMqttRecoveryEnabled() {
        return automaticMqttRecoveryEnabled;
    }

    boolean isReenableMqttEnabled() {
        return reenableMqttEnabled;
    }

}
