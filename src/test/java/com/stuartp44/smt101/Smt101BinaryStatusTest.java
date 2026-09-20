package com.stuartp44.smt101;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Smt101BinaryStatusTest {
    @Test
    void recognizesLiveSwitchAndDoorTopicsWithAnyPrefix() {
        Smt101BinaryStatus.Input switch1 =
                Smt101BinaryStatus.match("office/switch1/status");
        assertEquals("switch1", switch1.key);
        assertEquals("office", switch1.topicPrefix);
        assertTrue(switch1.writable);
        Smt101BinaryStatus.Input switch2 =
                Smt101BinaryStatus.match("floor/panel/switch2/status");
        assertEquals("switch2", switch2.key);
        assertEquals("floor/panel", switch2.topicPrefix);
        assertTrue(switch2.writable);
        assertEquals("door1", Smt101BinaryStatus.match("office/door1/status").key);
        assertEquals("door", Smt101BinaryStatus.match("office/door2/status").deviceClass);
        assertFalse(Smt101BinaryStatus.match("office/door1/status").writable);
        assertNull(Smt101BinaryStatus.match("office/switch1/set"));
        assertEquals("office/switch1/set", Smt101BinaryStatus.commandTopic(switch1));
        assertNull(Smt101BinaryStatus.commandTopic(
                Smt101BinaryStatus.match("office/door1/status")));
    }

    @Test
    void parsesOnAndOffJsonStates() {
        assertTrue(Smt101BinaryStatus.parseState("{\"state\":\"ON\"}").booleanValue());
        assertFalse(Smt101BinaryStatus.parseState("{\"state\":\"OFF\"}").booleanValue());
        assertEquals("ON", Smt101BinaryStatus.commandPayload(true));
        assertEquals("OFF", Smt101BinaryStatus.commandPayload(false));
        assertNull(Smt101BinaryStatus.parseState("{\"value\":1}"));
        assertNull(Smt101BinaryStatus.parseState(null));
    }
}
