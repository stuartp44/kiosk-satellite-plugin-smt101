package com.stuartp44.smt101;

import java.util.Map;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Smt101LightEffectsTest {
    @Test
    void framesStayWithinValidChannelRanges() {
        Smt101RgbLight.State base = new Smt101RgbLight.State(true, 0.8, 0.2, 0.4, 0.6);
        for (String name : Smt101LightEffects.NAMES) {
            assertTrue(Smt101LightEffects.supports(name));
            for (int elapsed = 0; elapsed < 30_000; elapsed += 137) {
                Map<String, Object> frame = Smt101LightEffects.frame(name, base, elapsed);
                if (Boolean.FALSE.equals(frame.get("on"))) {
                    assertEquals(Collections.<String, Object>singletonMap("on", Boolean.FALSE), frame);
                    continue;
                }
                for (String channel : new String[] {"brightness", "red", "green", "blue"}) {
                    double value = ((Number) frame.get(channel)).doubleValue();
                    assertTrue(Double.isFinite(value) && value >= 0 && value <= 1, name + channel);
                }
            }
        }
    }

    @Test
    void warningColorsAlternateOnAndOffEveryHalfSecond() {
        for (String name : new String[] {"Warning Red", "Warning Amber", "Warning Yellow",
                "Warning Green", "Warning Cyan", "Warning Blue", "Warning Magenta", "Warning White"}) {
            Smt101RgbLight.State base = Smt101RgbLight.initialState();
            assertEquals(true, Smt101LightEffects.frame(name, base, 499).get("on"));
            assertEquals(false, Smt101LightEffects.frame(name, base, 500).get("on"));
            assertEquals(false, Smt101LightEffects.frame(name, base, 999).get("on"));
            assertEquals(true, Smt101LightEffects.frame(name, base, 1000).get("on"));
        }
        assertEquals(1.0, Smt101LightEffects.frame("Warning Red",
                Smt101RgbLight.initialState(), 0).get("red"));
        assertEquals(0.5, Smt101LightEffects.frame("Warning Amber",
                Smt101RgbLight.initialState(), 0).get("green"));
        assertEquals(1.0, Smt101LightEffects.frame("Warning Blue",
                Smt101RgbLight.initialState(), 0).get("blue"));
    }

    @Test
    void breathePreservesColorAndRainbowChangesColor() {
        Smt101RgbLight.State base = new Smt101RgbLight.State(true, 0.8, 0.2, 0.4, 0.6);
        Map<String, Object> low = Smt101LightEffects.frame("Breathe", base, 0);
        Map<String, Object> high = Smt101LightEffects.frame("Breathe", base, 2000);
        assertEquals(0.08, ((Number) low.get("brightness")).doubleValue(), 0.00001);
        assertEquals(0.8, high.get("brightness"));
        assertEquals(0.2, high.get("red"));
        assertNotEquals(Smt101LightEffects.frame("Rainbow", base, 0),
                Smt101LightEffects.frame("Rainbow", base, 4000));
        assertFalse(Smt101LightEffects.supports("unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> Smt101LightEffects.frame("unknown", base, 0));
        assertThrows(IllegalArgumentException.class,
                () -> Smt101LightEffects.frame("Warning unknown", base, 500));
    }
}
