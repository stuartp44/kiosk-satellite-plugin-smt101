package com.stuartp44.smt101;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeteventReadingParserTest {
    private final GeteventReadingParser parser = new GeteventReadingParser();
    private final Smt101ResolvedConfig config = new Smt101ResolvedConfig("getevent -l", 8, 7);

    @Test
    void parsesHumidityLine() {
        GeteventReading reading = parser.parseLine("/dev/input/event8: EV_ABS 001d 00001194", config);
        assertNotNull(reading);
        assertEquals(GeteventReading.Type.HUMIDITY, reading.getType());
        assertEquals(45.0d, reading.getValue(), 0.0001d);
    }

    @Test
    void parsesTemperatureLine() {
        GeteventReading reading = parser.parseLine("/dev/input/event7: EV_ABS ABS_THROTTLE 0000092e", config);
        assertNotNull(reading);
        assertEquals(GeteventReading.Type.TEMPERATURE, reading.getType());
        assertEquals(23.5d, reading.getValue(), 0.0001d);
    }

    @Test
    void ignoresDifferentEventDevice() {
        assertNull(parser.parseLine("/dev/input/event6: EV_ABS ABS_THROTTLE 0000092e", config));
    }

    @Test
    void ignoresMalformedHexValue() {
        assertNull(parser.parseLine("/dev/input/event8: EV_ABS 001d not-hex", config));
    }
}
