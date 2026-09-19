package com.stuartp44.smt101;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Smt101ConfigTest {
    @Test
    void usesPropertyValuesWhenAvailable() {
        Map<String, Object> settings = new HashMap<String, Object>();
        settings.put("temperatureEventDevice", Integer.valueOf(7));
        settings.put("humidityEventDevice", Integer.valueOf(8));
        Smt101Config config = Smt101Config.fromSettings(settings);
        Smt101ResolvedConfig resolved = config.resolve(new SystemPropertyReader() {
            @Override
            public String read(String propertyName) {
                if ("com.gulukai.ths".equals(propertyName)) {
                    return "11";
                }
                if ("com.gulukai.hum".equals(propertyName)) {
                    return "12";
                }
                return "";
            }
        });
        assertEquals(11, resolved.getTemperatureEventDevice());
        assertEquals(12, resolved.getHumidityEventDevice());
    }

    @Test
    void fallsBackWhenPropertyLookupFails() {
        Smt101Config config = Smt101Config.fromSettings(null);
        Smt101ResolvedConfig resolved = config.resolve(new SystemPropertyReader() {
            @Override
            public String read(String propertyName) throws Exception {
                throw new Exception("boom");
            }
        });
        assertEquals(7, resolved.getTemperatureEventDevice());
        assertEquals(8, resolved.getHumidityEventDevice());
        assertEquals("getevent -l", resolved.getGeteventCommand());
    }

    @Test
    void parsesBooleanSetting() {
        Map<String, Object> settings = new HashMap<String, Object>();
        settings.put("enableTemperatureHumidity", Boolean.FALSE);
        Smt101Config config = Smt101Config.fromSettings(settings);
        assertFalse(config.isTemperatureHumidityEnabled());
        Smt101Config defaultConfig = Smt101Config.fromSettings(null);
        assertTrue(defaultConfig.isTemperatureHumidityEnabled());
    }
}
