package com.stuartp44.smt101;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Smt101RgbLight {
    private static final Pattern STATE = Pattern.compile(
            "\"state\"\\s*:\\s*\"(ON|OFF)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRIGHTNESS = Pattern.compile(
            "\"brightness\"\\s*:\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RGB = Pattern.compile(
            "\"rgb\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]",
            Pattern.CASE_INSENSITIVE);

    enum StatusType {
        POWER,
        BRIGHTNESS,
        RGB
    }

    private Smt101RgbLight() {
    }

    static State initialState() {
        return new State(false, 1.0d, 1.0d, 1.0d, 1.0d);
    }

    static StatusTopic matchStatusTopic(String topic) {
        if (topic == null) {
            return null;
        }
        String normalized = topic.toLowerCase(Locale.ROOT);
        StatusType type;
        String suffix;
        if (normalized.endsWith("/light/status")) {
            type = StatusType.POWER;
            suffix = "/light/status";
        } else if (normalized.endsWith("/brightness/status")) {
            type = StatusType.BRIGHTNESS;
            suffix = "/brightness/status";
        } else if (normalized.endsWith("/rgb/status")) {
            type = StatusType.RGB;
            suffix = "/rgb/status";
        } else {
            return null;
        }
        return new StatusTopic(topic.substring(0, topic.length() - suffix.length()), type);
    }

    static State applyStatus(State current, StatusType type, String payload) {
        State fallback = current == null ? initialState() : current;
        if (payload == null || type == null) {
            return null;
        }
        if (type == StatusType.POWER) {
            Matcher matcher = STATE.matcher(payload);
            return matcher.find()
                    ? new State(
                            "ON".equalsIgnoreCase(matcher.group(1)),
                            fallback.brightness,
                            fallback.red,
                            fallback.green,
                            fallback.blue)
                    : null;
        }
        if (type == StatusType.BRIGHTNESS) {
            Matcher matcher = BRIGHTNESS.matcher(payload);
            if (!matcher.find()) {
                return null;
            }
            Double value = parseRange(matcher.group(1), 100.0d);
            return value == null
                    ? null
                    : new State(fallback.on, value.doubleValue(), fallback.red, fallback.green, fallback.blue);
        }
        Matcher matcher = RGB.matcher(payload);
        if (!matcher.find()) {
            return null;
        }
        Double red = parseRange(matcher.group(1), 255.0d);
        Double green = parseRange(matcher.group(2), 255.0d);
        Double blue = parseRange(matcher.group(3), 255.0d);
        return red == null || green == null || blue == null
                ? null
                : new State(
                        fallback.on,
                        fallback.brightness,
                        red.doubleValue(),
                        green.doubleValue(),
                        blue.doubleValue());
    }

    static State applyCommand(State current, Map<String, Object> command) {
        State fallback = current == null ? initialState() : current;
        return new State(
                booleanValue(command.get("on"), fallback.on),
                channelValue(command.get("brightness"), fallback.brightness),
                channelValue(command.get("red"), fallback.red),
                channelValue(command.get("green"), fallback.green),
                channelValue(command.get("blue"), fallback.blue));
    }

    static List<Publication> commandPublications(
            String topicPrefix, Map<String, Object> command, State requested) {
        List<Publication> publications = new ArrayList<Publication>();
        if (topicPrefix == null || topicPrefix.isEmpty() || command == null || requested == null) {
            return publications;
        }
        if (command.containsKey("on")) {
            publications.add(new Publication(
                    topicPrefix + "/light/switch",
                    requested.on ? "ON" : "OFF"));
        }
        if (command.containsKey("brightness")) {
            publications.add(new Publication(
                    topicPrefix + "/brightness/set",
                    Integer.toString(percentageValue(requested.brightness))));
        }
        if (command.containsKey("red") || command.containsKey("green") || command.containsKey("blue")) {
            publications.add(new Publication(
                    topicPrefix + "/rgb/set",
                    channelValue(requested.red)
                            + "," + channelValue(requested.green)
                            + "," + channelValue(requested.blue)));
        }
        return publications;
    }

    static final class StatusTopic {
        final String prefix;
        final StatusType type;

        StatusTopic(String prefix, StatusType type) {
            this.prefix = prefix;
            this.type = type;
        }
    }

    static final class Publication {
        final String topic;
        final String payload;

        Publication(String topic, String payload) {
            this.topic = topic;
            this.payload = payload;
        }
    }

    static final class State {
        final boolean on;
        final double brightness;
        final double red;
        final double green;
        final double blue;

        State(boolean on, double brightness, double red, double green, double blue) {
            this.on = on;
            this.brightness = brightness;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        Map<String, Object> toEntityState() {
            Map<String, Object> state = new LinkedHashMap<String, Object>();
            state.put("on", Boolean.valueOf(on));
            state.put("brightness", Double.valueOf(brightness));
            state.put("red", Double.valueOf(red));
            state.put("green", Double.valueOf(green));
            state.put("blue", Double.valueOf(blue));
            state.put("effect", "None");
            return state;
        }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }

    private static double channelValue(Object value, double fallback) {
        if (!(value instanceof Number)) {
            return fallback;
        }
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number >= 0.0d && number <= 1.0d ? number : fallback;
    }

    private static Double parseRange(String text, double maximum) {
        try {
            double value = Double.parseDouble(text);
            return value >= 0.0d && value <= maximum
                    ? Double.valueOf(value / maximum)
                    : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int percentageValue(double channel) {
        return (int) Math.round(channel * 100.0d);
    }

    private static int channelValue(double channel) {
        return (int) Math.round(channel * 255.0d);
    }
}
