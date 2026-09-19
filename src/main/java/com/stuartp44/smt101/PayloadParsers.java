package com.stuartp44.smt101;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class PayloadParsers {
    private PayloadParsers() {
    }

    static Double parseNumeric(String payload, String[] preferredKeys) {
        String text = normalize(payload);
        if (text.isEmpty()) {
            return null;
        }
        Map<String, String> members = parseJsonObject(text);
        if (members == null) {
            return null;
        }
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
        if (members == null) {
            return null;
        }
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
        if (!(payload.startsWith("{") && payload.endsWith("}"))) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> members = new LinkedHashMap<String, String>();
        int index = skipWhitespace(payload, 1);
        if (index == payload.length() - 1) {
            return members;
        }
        while (index < payload.length() - 1) {
            ParsedString key = parseJsonString(payload, index);
            if (key == null) {
                return null;
            }
            index = skipWhitespace(payload, key.nextIndex);
            if (index >= payload.length() || payload.charAt(index) != ':') {
                return null;
            }
            index = skipWhitespace(payload, index + 1);
            ParsedToken value = parseJsonValue(payload, index);
            if (value == null) {
                return null;
            }
            members.put(key.value.toLowerCase(Locale.US), value.value);
            index = skipWhitespace(payload, value.nextIndex);
            if (index == payload.length() - 1) {
                return members;
            }
            if (payload.charAt(index) != ',') {
                return null;
            }
            index = skipWhitespace(payload, index + 1);
        }
        return null;
    }

    private static ParsedToken parseJsonValue(String payload, int start) {
        if (start >= payload.length()) {
            return null;
        }
        char current = payload.charAt(start);
        if (current == '"') {
            ParsedString parsedString = parseJsonString(payload, start);
            return parsedString == null ? null : new ParsedToken(parsedString.value, parsedString.nextIndex);
        }
        if (payload.startsWith("true", start)) {
            return new ParsedToken("true", start + 4);
        }
        if (payload.startsWith("false", start)) {
            return new ParsedToken("false", start + 5);
        }
        if (payload.startsWith("null", start)) {
            return new ParsedToken("null", start + 4);
        }
        return parseNumberToken(payload, start);
    }

    private static ParsedToken parseNumberToken(String payload, int start) {
        int index = start;
        if (payload.charAt(index) == '-') {
            index++;
        }
        boolean digitsBeforeDecimal = false;
        while (index < payload.length() && Character.isDigit(payload.charAt(index))) {
            digitsBeforeDecimal = true;
            index++;
        }
        boolean digitsAfterDecimal = false;
        if (index < payload.length() && payload.charAt(index) == '.') {
            index++;
            while (index < payload.length() && Character.isDigit(payload.charAt(index))) {
                digitsAfterDecimal = true;
                index++;
            }
        }
        if (!digitsBeforeDecimal && !digitsAfterDecimal) {
            return null;
        }
        if (!digitsBeforeDecimal && digitsAfterDecimal && payload.charAt(start) != '.') {
            return null;
        }
        return new ParsedToken(payload.substring(start, index), index);
    }

    private static ParsedString parseJsonString(String payload, int start) {
        if (start >= payload.length() || payload.charAt(start) != '"') {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        int index = start + 1;
        while (index < payload.length()) {
            char current = payload.charAt(index);
            if (current == '\\') {
                index++;
                if (index >= payload.length()) {
                    return null;
                }
                char escaped = payload.charAt(index);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        builder.append(escaped);
                        break;
                    case 'b':
                        builder.append('\b');
                        break;
                    case 'f':
                        builder.append('\f');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 't':
                        builder.append('\t');
                        break;
                    default:
                        return null;
                }
            } else if (current == '"') {
                return new ParsedString(builder.toString(), index + 1);
            } else {
                builder.append(current);
            }
            index++;
        }
        return null;
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

    private static int skipWhitespace(String payload, int start) {
        int index = start;
        while (index < payload.length() && Character.isWhitespace(payload.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private static final class ParsedString {
        private final String value;
        private final int nextIndex;

        private ParsedString(String value, int nextIndex) {
            this.value = value;
            this.nextIndex = nextIndex;
        }
    }

    private static final class ParsedToken {
        private final String value;
        private final int nextIndex;

        private ParsedToken(String value, int nextIndex) {
            this.value = value;
            this.nextIndex = nextIndex;
        }
    }
}
