package com.stuartp44.smt101;

final class Smt101MessageProcessor {
    ProcessedMessage process(Smt101Config config, String topic, String payload) {
        Smt101Topic matchedTopic = Smt101Topic.match(config, topic);
        if (matchedTopic == null) {
            return ProcessedMessage.unmatched();
        }
        if (matchedTopic.isNumeric()) {
            Double value = PayloadParsers.parseNumeric(payload, matchedTopic.getPreferredKeys());
            return value == null ? ProcessedMessage.invalid(matchedTopic) : ProcessedMessage.numeric(matchedTopic, value);
        }
        Boolean value = PayloadParsers.parseBoolean(payload, matchedTopic.getPreferredKeys());
        return value == null ? ProcessedMessage.invalid(matchedTopic) : ProcessedMessage.binary(matchedTopic, value);
    }
}
