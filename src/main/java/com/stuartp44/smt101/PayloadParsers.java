package com.stuartp44.smt101;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PayloadParsers {
    private static final Pattern JSON_MEMBER = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*(\\\"(?:\\\\.|[^\\\"])*\\\"|true|false|null|-?(?:\\d+(?:\\.\\d+)?|\\.\\d+))", Pattern.CASE_INSENSITIVE);

    private PayloadParsers() {
    }

    static Double parseNumeric(String payload, String[] preferredKeys) {
        String text = normalize(payload);
        if (text.isEmpty()) {
            return null;
        }
        Map<String, String> members = parseJsonObject(text);
        if (!members.isEmpty()) {
            for (String key : preferredKeys) {
                Double parsed = parseDoubleToken(members.get(key.toLowerCase(Locale.US)));
                if (parsed != null) {
                    return parsed;
                }
            }
            Double parsed = parseDoubleToken(members.get("state"));
            if (parsed != null) {
                return parsed;
            }
            parsed = parseDoubleToken(members.get("value"));
            if (parsed != null) {
                return parsed;
            }
            if (members.size() == 1) {
                return parseDoubleToken(members.values().iterator().next());
            }
            return null;
        }
        return parseDoubleToken(text);
    }

    static Boolean parseBoolean(String payload, String[] preferredKeys) {
        String text = normalize(payload);
        if (text.isEmpty()) {
            return null;
        }
        Map<String, String> members = parseJsonObject(text);
        if (!members.isEmpty()) {
            for (String key : preferredKeys) {
                Boolean parsed = parseBooleanToken(members.get(key.toLowerCase(Locale.US)));
                if (parsed != null) {
                    return parsed;
                }
            }
            Boolean parsed = parseBooleanToken(members.get("state"));
            if (parsed != null) {
                return parsed;
            }
            parsed = parseBooleanToken(members.get("value"));
            if (parsed != null) {
                return parsed;
            }
            if (members.size() == 1) {
                return parseBooleanToken(members.values().iterator().next());
            }
            return null;
        }
        return parseBooleanToken(text);
    }

    private static Map<String, String> parseJsonObject(String payload) {
        Map<String, String> members = new LinkedHashMap<String, String>();
        if (!(payload.startsWith("{") && payload.endsWith("}"))) {
            return members;
        }
        Matcher matcher = JSON_MEMBER.matcher(payload);
        while (matcher.find()) {
            members.put(matcher.group(1).toLowerCase(Locale.US), unquote(matcher.group(2)));
        }
        return members;
    }

    private static Double parseDoubleToken(String token) {
        if (token == null) {
            return null;
        }
        String normalized = normalize(token);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            double value = Double.parseDouble(normalized);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Boolean parseBooleanToken(String token) {
        if (token == null) {
            return null;
        }
        String normalized = normalize(token).toLowerCase(Locale.US);
        if (normalized.isEmpty()) {
            return null;
        }
        if ("true".equals(normalized) || "on".equals(normalized) || "1".equals(normalized)
                || "yes".equals(normalized) || "high".equals(normalized)
                || "open".equals(normalized) || "pressed".equals(normalized)
                || "active".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized) || "off".equals(normalized) || "0".equals(normalized)
                || "no".equals(normalized) || "low".equals(normalized)
                || "closed".equals(normalized) || "released".equals(normalized)
                || "inactive".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private static String unquote(String token) {
        String text = normalize(token);
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
            text = text.replace("\\\"", "\"");
            text = text.replace("\\\\", "\\");
        }
        return text;
    }
}
