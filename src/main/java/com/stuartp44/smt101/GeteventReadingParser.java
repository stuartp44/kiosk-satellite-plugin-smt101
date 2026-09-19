package com.stuartp44.smt101;

final class GeteventReadingParser {
    GeteventReading parseLine(String line, Smt101ResolvedConfig config) {
        Double humidity = parseEventValue(line, config.getHumidityEventDevice(), "001d");
        if (humidity != null) {
            return new GeteventReading(GeteventReading.Type.HUMIDITY, humidity.doubleValue());
        }
        Double temperature = parseEventValue(line, config.getTemperatureEventDevice(), "ABS_THROTTLE");
        if (temperature != null) {
            return new GeteventReading(GeteventReading.Type.TEMPERATURE, temperature.doubleValue());
        }
        return null;
    }

    private Double parseEventValue(String line, int eventDevice, String marker) {
        if (line == null) {
            return null;
        }
        String eventToken = "event" + eventDevice;
        if (!line.contains(eventToken) || !line.contains("EV_ABS")) {
            return null;
        }
        int markerIndex = line.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        String tail = line.substring(markerIndex + marker.length()).trim();
        String hexToken = leadingHexToken(tail);
        if (hexToken == null) {
            return null;
        }
        try {
            int rawValue = Integer.parseInt(hexToken, 16);
            return Double.valueOf(rawValue / 100.0d);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String leadingHexToken(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        String token = text.substring(0, end);
        for (int index = 0; index < token.length(); index++) {
            if (!isHexCharacter(token.charAt(index))) {
                return null;
            }
        }
        return token;
    }

    private boolean isHexCharacter(char value) {
        return (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }
}
