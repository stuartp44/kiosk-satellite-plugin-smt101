package com.stuartp44.smt101;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Smt101BinaryStatus {
    private static final Pattern STATE = Pattern.compile(
            "\"state\"\\s*:\\s*\"(ON|OFF)\"", Pattern.CASE_INSENSITIVE);

    private Smt101BinaryStatus() {
    }

    static Input match(String topic) {
        if (topic == null) {
            return null;
        }
        String normalized = topic.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("/switch1/status")) {
            return new Input(
                    "switch1",
                    "Switch 1",
                    "",
                    topic.substring(0, topic.length() - "/switch1/status".length()),
                    true);
        }
        if (normalized.endsWith("/switch2/status")) {
            return new Input(
                    "switch2",
                    "Switch 2",
                    "",
                    topic.substring(0, topic.length() - "/switch2/status".length()),
                    true);
        }
        if (normalized.endsWith("/door1/status")) {
            return new Input("door1", "Door 1", "door", "", false);
        }
        if (normalized.endsWith("/door2/status")) {
            return new Input("door2", "Door 2", "door", "", false);
        }
        return null;
    }

    static Boolean parseState(String payload) {
        if (payload == null) {
            return null;
        }
        Matcher matcher = STATE.matcher(payload);
        if (!matcher.find()) {
            return null;
        }
        return Boolean.valueOf("ON".equalsIgnoreCase(matcher.group(1)));
    }

    static String commandTopic(Input input) {
        return input == null || !input.writable || input.topicPrefix.isEmpty()
                ? null
                : input.topicPrefix + "/" + input.key + "/set";
    }

    static String commandPayload(boolean on) {
        return on ? "ON" : "OFF";
    }

    static final class Input {
        final String key;
        final String name;
        final String deviceClass;
        final String topicPrefix;
        final boolean writable;

        Input(String key, String name, String deviceClass, String topicPrefix, boolean writable) {
            this.key = key;
            this.name = name;
            this.deviceClass = deviceClass;
            this.topicPrefix = topicPrefix;
            this.writable = writable;
        }
    }
}
