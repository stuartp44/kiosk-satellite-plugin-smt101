package com.stuartp44.smt101;

final class GeteventReading {
    enum Type {
        TEMPERATURE,
        HUMIDITY
    }

    private final Type type;
    private final double value;

    GeteventReading(Type type, double value) {
        this.type = type;
        this.value = value;
    }

    Type getType() {
        return type;
    }

    double getValue() {
        return value;
    }
}
