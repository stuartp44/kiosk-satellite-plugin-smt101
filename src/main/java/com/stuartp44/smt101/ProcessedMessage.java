package com.stuartp44.smt101;

final class ProcessedMessage {
    private final Smt101Topic topic;
    private final Double numericValue;
    private final Boolean binaryValue;
    private final boolean matched;
    private final boolean valid;

    private ProcessedMessage(Smt101Topic topic, Double numericValue, Boolean binaryValue, boolean matched, boolean valid) {
        this.topic = topic;
        this.numericValue = numericValue;
        this.binaryValue = binaryValue;
        this.matched = matched;
        this.valid = valid;
    }

    static ProcessedMessage unmatched() {
        return new ProcessedMessage(null, null, null, false, false);
    }

    static ProcessedMessage invalid(Smt101Topic topic) {
        return new ProcessedMessage(topic, null, null, true, false);
    }

    static ProcessedMessage numeric(Smt101Topic topic, Double value) {
        return new ProcessedMessage(topic, value, null, true, true);
    }

    static ProcessedMessage binary(Smt101Topic topic, Boolean value) {
        return new ProcessedMessage(topic, null, value, true, true);
    }

    Smt101Topic getTopic() {
        return topic;
    }

    Double getNumericValue() {
        return numericValue;
    }

    Boolean getBinaryValue() {
        return binaryValue;
    }

    boolean isMatched() {
        return matched;
    }

    boolean isValid() {
        return valid;
    }
}
