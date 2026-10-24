package com.stuartp44.smt101;

import java.util.LinkedHashMap;
import java.util.Map;

final class Smt101LightEffects {
    static final long WARNING_HOLD_MS = 500L;
    static final String[] NAMES = {
        "Rainbow", "Breathe", "Candle", "Warning Red", "Warning Amber", "Warning Yellow",
        "Warning Green", "Warning Cyan", "Warning Blue", "Warning Magenta", "Warning White"
    };

    private Smt101LightEffects() {
    }

    static boolean supports(String name) {
        for (String candidate : NAMES) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Object> frame(String name, Smt101RgbLight.State base, long elapsedMs) {
        Map<String, Object> command = new LinkedHashMap<String, Object>();
        command.put("on", Boolean.TRUE);
        double brightness = base.brightness;
        double red = base.red;
        double green = base.green;
        double blue = base.blue;
        if ("Rainbow".equals(name)) {
            double hue = (elapsedMs % 12_000L) / 2000.0d;
            int sector = (int) hue;
            double fraction = hue - sector;
            double[] channels;
            switch (sector) {
                case 0: channels = new double[] {1, fraction, 0}; break;
                case 1: channels = new double[] {1 - fraction, 1, 0}; break;
                case 2: channels = new double[] {0, 1, fraction}; break;
                case 3: channels = new double[] {0, 1 - fraction, 1}; break;
                case 4: channels = new double[] {fraction, 0, 1}; break;
                default: channels = new double[] {1, 0, 1 - fraction}; break;
            }
            red = channels[0];
            green = channels[1];
            blue = channels[2];
        } else if ("Breathe".equals(name)) {
            brightness *= 0.1d + 0.9d * (1 - Math.cos(elapsedMs * Math.PI / 2000.0d)) / 2;
        } else if ("Candle".equals(name)) {
            red = 1;
            green = 0.32d;
            blue = 0.03d;
            brightness *= 0.65d + 0.35d * (0.5d + 0.5d * Math.sin(elapsedMs * 0.017d)
                    * Math.sin(elapsedMs * 0.031d));
        } else if (name.startsWith("Warning ") && supports(name)) {
            command.put("on", Boolean.valueOf((elapsedMs / WARNING_HOLD_MS) % 2 == 0));
            if (Boolean.FALSE.equals(command.get("on"))) {
                return command;
            }
            red = ("Warning Green".equals(name) || "Warning Cyan".equals(name)
                    || "Warning Blue".equals(name)) ? 0 : 1;
            green = "Warning Amber".equals(name) ? 0.5d
                    : ("Warning Yellow".equals(name) || "Warning Green".equals(name)
                        || "Warning Cyan".equals(name) || "Warning White".equals(name)) ? 1 : 0;
            blue = ("Warning Cyan".equals(name) || "Warning Blue".equals(name)
                    || "Warning Magenta".equals(name) || "Warning White".equals(name)) ? 1 : 0;
        } else {
            throw new IllegalArgumentException("Unknown light effect: " + name);
        }
        command.put("brightness", Double.valueOf(brightness));
        command.put("red", Double.valueOf(red));
        command.put("green", Double.valueOf(green));
        command.put("blue", Double.valueOf(blue));
        return command;
    }
}
