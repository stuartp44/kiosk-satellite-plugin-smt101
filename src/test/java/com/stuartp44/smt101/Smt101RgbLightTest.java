package com.stuartp44.smt101;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Smt101RgbLightTest {
    @Test
    void recognizesLiveStatusTopicsAndLearnsPrefix() {
        Smt101RgbLight.StatusTopic power =
                Smt101RgbLight.matchStatusTopic("office/light/status");
        assertEquals("office", power.prefix);
        assertEquals(Smt101RgbLight.StatusType.POWER, power.type);
        assertEquals(
                Smt101RgbLight.StatusType.BRIGHTNESS,
                Smt101RgbLight.matchStatusTopic("office/brightness/status").type);
        assertEquals(
                Smt101RgbLight.StatusType.RGB,
                Smt101RgbLight.matchStatusTopic("floor/office/rgb/status").type);
        assertNull(Smt101RgbLight.matchStatusTopic("office/backlight/status"));
    }

    @Test
    void combinesSeparateLiveStatusPayloads() {
        Smt101RgbLight.State state = Smt101RgbLight.initialState();
        state = Smt101RgbLight.applyStatus(
                state, Smt101RgbLight.StatusType.POWER, "{\"state\":\"ON\"}");
        state = Smt101RgbLight.applyStatus(
                state, Smt101RgbLight.StatusType.BRIGHTNESS, "{\"brightness\":20}");
        state = Smt101RgbLight.applyStatus(
                state, Smt101RgbLight.StatusType.RGB, "{\"rgb\":[255,64,0]}");

        assertTrue(state.on);
        assertEquals(0.2d, state.brightness, 0.0001d);
        assertEquals(1.0d, state.red, 0.0001d);
        assertEquals(64.0d / 255.0d, state.green, 0.0001d);
        assertEquals(0.0d, state.blue, 0.0001d);
    }

    @Test
    void createsSeparateCommandsForSuppliedEntityFields() {
        Map<String, Object> command = new LinkedHashMap<String, Object>();
        command.put("on", Boolean.TRUE);
        command.put("brightness", Double.valueOf(0.2d));
        command.put("red", Double.valueOf(1.0d));
        command.put("green", Double.valueOf(0.25d));
        command.put("blue", Double.valueOf(0.0d));
        Smt101RgbLight.State requested =
                Smt101RgbLight.applyCommand(Smt101RgbLight.initialState(), command);

        List<Smt101RgbLight.Publication> publications =
                Smt101RgbLight.commandPublications("office", command, requested);

        assertEquals(3, publications.size());
        assertEquals("office/light/switch", publications.get(0).topic);
        assertEquals("ON", publications.get(0).payload);
        assertEquals("office/brightness/set", publications.get(1).topic);
        assertEquals("20", publications.get(1).payload);
        assertEquals("office/rgb/set", publications.get(2).topic);
        assertEquals("255,64,0", publications.get(2).payload);
    }

    @Test
    void rejectsMalformedOrOutOfRangeStatus() {
        Smt101RgbLight.State state = Smt101RgbLight.initialState();
        assertNull(Smt101RgbLight.applyStatus(
                state, Smt101RgbLight.StatusType.POWER, "{\"state\":\"INVALID\"}"));
        assertNull(Smt101RgbLight.applyStatus(
                state, Smt101RgbLight.StatusType.BRIGHTNESS, "{\"brightness\":101}"));
        assertNull(Smt101RgbLight.applyStatus(
                state, Smt101RgbLight.StatusType.RGB, "{\"rgb\":[256,0,0]}"));
        assertFalse(state.on);
    }
}
