package com.stuartp44.smt101;

final class Smt101ResolvedConfig {
    private final String geteventCommand;
    private final int humidityEventDevice;
    private final int temperatureEventDevice;

    Smt101ResolvedConfig(String geteventCommand, int humidityEventDevice, int temperatureEventDevice) {
        this.geteventCommand = geteventCommand;
        this.humidityEventDevice = humidityEventDevice;
        this.temperatureEventDevice = temperatureEventDevice;
    }

    String getGeteventCommand() {
        return geteventCommand;
    }

    int getHumidityEventDevice() {
        return humidityEventDevice;
    }

    int getTemperatureEventDevice() {
        return temperatureEventDevice;
    }
}
