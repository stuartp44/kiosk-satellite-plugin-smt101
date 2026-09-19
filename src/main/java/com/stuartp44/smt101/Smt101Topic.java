package com.stuartp44.smt101;

enum Smt101Topic {
    TEMPERATURE("temperature", "sensor/temperature", true, new String[]{"temperature", "value"}),
    HUMIDITY("humidity", "sensor/humidity", true, new String[]{"humidity", "value"}),
    LIGHT("light", "sensor/light", true, new String[]{"lux", "illuminance", "light", "value"}),
    INPUT1("input1", "input1/state", false, new String[]{"state", "value", "input", "on"}),
    INPUT2("input2", "input2/state", false, new String[]{"state", "value", "input", "on"});

    private final String entityKey;
    private final String suffix;
    private final boolean numeric;
    private final String[] preferredKeys;

    Smt101Topic(String entityKey, String suffix, boolean numeric, String[] preferredKeys) {
        this.entityKey = entityKey;
        this.suffix = suffix;
        this.numeric = numeric;
        this.preferredKeys = preferredKeys;
    }

    String getEntityKey() {
        return entityKey;
    }

    boolean isNumeric() {
        return numeric;
    }

    String[] getPreferredKeys() {
        return preferredKeys;
    }

    String topicFor(Smt101Config config) {
        return config.topic(suffix);
    }

    static Smt101Topic match(Smt101Config config, String topic) {
        if (topic == null) {
            return null;
        }
        for (Smt101Topic candidate : values()) {
            if (!candidate.isEnabled(config)) {
                continue;
            }
            if (candidate.topicFor(config).equals(topic)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isEnabled(Smt101Config config) {
        return numeric ? config.isEnvironmentSensorsEnabled() : config.isInputsEnabled();
    }
}
