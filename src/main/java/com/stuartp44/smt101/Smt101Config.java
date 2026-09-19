package com.stuartp44.smt101;

import java.util.Map;

final class Smt101Config {
    private static final int DEFAULT_HUMIDITY_EVENT_DEVICE = 8;
    private static final int DEFAULT_TEMPERATURE_EVENT_DEVICE = 7;
    private static final String DEFAULT_GETEVENT_COMMAND = "getevent -l";
    private static final String DEFAULT_HUMIDITY_PROPERTY = "com.gulukai.hum";
    private static final String DEFAULT_TEMPERATURE_PROPERTY = "com.gulukai.ths";

    private final boolean enableTemperatureHumidity;
    private final String geteventCommand;
    private final int humidityEventDevice;
    private final int temperatureEventDevice;
    private final String humidityPropertyName;
    private final String temperaturePropertyName;

    private Smt101Config(
            boolean enableTemperatureHumidity,
            String geteventCommand,
            int humidityEventDevice,
            int temperatureEventDevice,
            String humidityPropertyName,
            String temperaturePropertyName) {
        this.enableTemperatureHumidity = enableTemperatureHumidity;
        this.geteventCommand = geteventCommand;
        this.humidityEventDevice = humidityEventDevice;
        this.temperatureEventDevice = temperatureEventDevice;
        this.humidityPropertyName = humidityPropertyName;
        this.temperaturePropertyName = temperaturePropertyName;
    }

    static Smt101Config fromSettings(Map<String, Object> settings) {
        return new Smt101Config(
                getBoolean(settings, "enableTemperatureHumidity", true),
                getString(settings, "geteventCommand", DEFAULT_GETEVENT_COMMAND),
                normalizedEventDevice(getInteger(settings, "humidityEventDevice", DEFAULT_HUMIDITY_EVENT_DEVICE), DEFAULT_HUMIDITY_EVENT_DEVICE),
                normalizedEventDevice(getInteger(settings, "temperatureEventDevice", DEFAULT_TEMPERATURE_EVENT_DEVICE), DEFAULT_TEMPERATURE_EVENT_DEVICE),
                getString(settings, "humidityPropertyName", DEFAULT_HUMIDITY_PROPERTY),
                getString(settings, "temperaturePropertyName", DEFAULT_TEMPERATURE_PROPERTY));
    }

    boolean isTemperatureHumidityEnabled() {
        return enableTemperatureHumidity;
    }

    Smt101ResolvedConfig resolve(SystemPropertyReader propertyReader) {
        return new Smt101ResolvedConfig(
                geteventCommand,
                resolveEventDevice(propertyReader, humidityPropertyName, humidityEventDevice),
                resolveEventDevice(propertyReader, temperaturePropertyName, temperatureEventDevice));
    }

    private int resolveEventDevice(SystemPropertyReader propertyReader, String propertyName, int fallback) {
        if (propertyReader == null || propertyName.isEmpty()) {
            return fallback;
        }
        try {
            String value = propertyReader.read(propertyName);
            if (value == null || value.trim().isEmpty()) {
                return fallback;
            }
            return normalizedEventDevice(Integer.parseInt(value.trim()), fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int normalizedEventDevice(int value, int fallback) {
        return value >= 0 ? value : fallback;
    }

    private static String getString(Map<String, Object> settings, String key, String fallback) {
        if (settings == null) {
            return fallback;
        }
        Object value = settings.get(key);
        String text = value == null ? fallback : String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static boolean getBoolean(Map<String, Object> settings, String key, boolean fallback) {
        if (settings == null) {
            return fallback;
        }
        Object value = settings.get(key);
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
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
