package com.stuartp44.smt101;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import me.jxl.kiosk.plugins.PluginHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Smt101PluginTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private FakeHost host;
    private Smt101Plugin plugin;

    @BeforeEach
    void startWithLocalBroker() throws Exception {
        host = new FakeHost();
        plugin = new Smt101Plugin(new Smt101Plugin.Clock() {
            @Override
            public long nowMillis() {
                return now.get();
            }
        }, new Smt101MqttRecovery(), 0);
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("enableEmbeddedMqttBroker", Boolean.FALSE);
        plugin.start(host, settings);
        assertTrue(host.configured.await(2, TimeUnit.SECONDS));
        assertTrue(host.diagnosticPublished.await(2, TimeUnit.SECONDS));
        assertTrue(host.brokerStarted.await(2, TimeUnit.SECONDS));
        assertTrue(host.published.isEmpty());
        assertEquals("Detecting device capabilities, please wait.", host.lastStatus);
        assertFalse(host.lastStatusError);
    }

    @AfterEach
    void stop() throws Exception {
        plugin.stop();
        assertTrue(host.removedSensors.contains("distance"));
    }

    @Test
    void recoveryProgressCannotClearStartupConnectivityError() throws Exception {
        plugin.stop();
        AtomicReference<Smt101MqttRecovery.Callback> callback = new AtomicReference<>();
        Smt101MqttRecovery recovery = new Smt101MqttRecovery(new Smt101MqttRecovery.Backend() {
            @Override
            public void check(boolean reenableDisabledMqtt, Smt101MqttRecovery.Callback result) {
                callback.set(result);
            }

            @Override
            public void stop() {
            }
        });
        host = new FakeHost();
        plugin = new Smt101Plugin(() -> now.get(), recovery, 0);
        plugin.start(host, Collections.<String, Object>singletonMap("enableAutomaticMqttRecovery", true));
        assertTrue(host.brokerStarted.await(2, TimeUnit.SECONDS));
        plugin.refreshEntityStates();
        assertTrue(callback.get() != null);
        now.addAndGet(60_000L);
        plugin.refreshMqttPayloadDiagnostic();
        String setupError = host.lastStatus;
        assertTrue(host.lastStatusError);
        callback.get().complete(Smt101MqttRecovery.Result.RESTARTED, null);
        assertEquals(setupError, host.lastStatus);
        assertTrue(host.lastStatusError);
        assertEquals(1, host.logsContaining("waiting for MQTT publications").size());
        now.addAndGet(Smt101MqttRecovery.RETRY_INTERVAL_MS);
        plugin.refreshEntityStates();
        callback.get().complete(Smt101MqttRecovery.Result.REENABLED, null);
        assertEquals(setupError, host.lastStatus);
        assertTrue(host.lastStatusError);
        plugin.handleMqttMessage("office/sensors/temperature", "{\"temperature\":21}");
        assertFalse(host.lastStatusError);
        assertEquals("Detected capabilities on this device: Temperature.", host.lastStatus);
        assertEquals(1, host.logsContaining("recovery completed").size());
    }

    @Test
    void startupTimeoutRequiresPublicationsAndRecoversOnDetectedCapability() throws Exception {
        try (Socket client = startLocalBrokerClient(false)) {
            assertTrue(client.isConnected());
            now.addAndGet(59_999L);
            plugin.refreshMqttPayloadDiagnostic();
            assertFalse(host.lastStatusError);
            now.incrementAndGet();
            plugin.refreshMqttPayloadDiagnostic();
            assertTrue(host.lastStatusError);
            assertTrue(host.lastStatus.contains("Have you configured the built-in MQTT client settings?"));
            assertTrue(host.lastStatus.contains(
                    "https://github.com/stuartp44/kiosk-satellite-plugin-smt101/blob/main/README.md#tablet-mqtt-settings"));
            assertFalse(host.lastStatus.contains("Detected capabilities"));
            plugin.refreshMqttPayloadDiagnostic();
            assertEquals(1, host.logsContaining("within 60 seconds").size());
            plugin.handleMqttMessage("office/sensors/temperature", "{\"temperature\":21}");
            assertEquals("Detected capabilities on this device: Temperature.", host.lastStatus);
            assertFalse(host.lastStatusError);
            now.addAndGet(60_000L);
            plugin.refreshMqttPayloadDiagnostic();
            assertFalse(host.lastStatusError);

            host.brokerStarted = new CountDownLatch(1);
            plugin.configure(Collections.<String, Object>emptyMap());
            assertTrue(host.brokerStarted.await(2, TimeUnit.SECONDS));
            now.addAndGet(60_000L);
            plugin.refreshMqttPayloadDiagnostic();
            assertTrue(host.lastStatusError);
            assertEquals(2, host.logsContaining("within 60 seconds").size());
        }
    }

    @Test
    void anyPublicationPreventsStartupTimeoutAndStopDisarmsIt() throws Exception {
        plugin.handleMqttMessage("office/unsupported", "message");
        now.addAndGet(60_000L);
        plugin.refreshMqttPayloadDiagnostic();
        assertFalse(host.lastStatusError);
        assertTrue(host.logsContaining("within 60 seconds").isEmpty());
        host.brokerStarted = new CountDownLatch(1);
        plugin.configure(Collections.<String, Object>emptyMap());
        assertTrue(host.brokerStarted.await(2, TimeUnit.SECONDS));
        plugin.stop();
        now.addAndGet(60_000L);
        plugin.refreshMqttPayloadDiagnostic();
        assertTrue(host.logsContaining("within 60 seconds").isEmpty());
    }

    @Test
    void lightEffectsAdvertiseRunAndStopOnNoneAndUnload() throws Exception {
        try (Socket client = startLocalBrokerClient(true)) {
            assertTrue(client.isConnected());
            plugin.handleMqttMessage("office/brightness/status", "{\"brightness\":70}");
            assertEquals(11, host.lightEffects.length);
            plugin.onEvent("light.rgb_backlight",
                    Collections.<String, Object>singletonMap("effect", "Breathe"));
            awaitEffect("Breathe");
            assertEffectDisplayState();
            assertTrue(host.lightUpdates > 1);
            plugin.onEvent("light.rgb_backlight",
                    Collections.<String, Object>singletonMap("effect", "None"));
            awaitEffect("None");
            int stoppedAt = host.lightUpdates;
            Thread.sleep(600);
            assertEquals(stoppedAt, host.lightUpdates);
            plugin.onEvent("light.rgb_backlight",
                    Collections.<String, Object>singletonMap("effect", "Rainbow"));
            awaitEffect("Rainbow");
            assertEffectDisplayState();
            plugin.onEvent("light.rgb_backlight",
                    Collections.<String, Object>singletonMap("on", Boolean.FALSE));
            awaitEffect("None");
            assertEquals(Boolean.FALSE, host.lastLightState.get("on"));
            stoppedAt = host.lightUpdates;
            Thread.sleep(600);
            assertEquals(stoppedAt, host.lightUpdates);
            plugin.onEvent("light.rgb_backlight",
                    Collections.<String, Object>singletonMap("effect", "Candle"));
            awaitEffect("Candle");
            assertEffectDisplayState();
            host.brokerStarted = new CountDownLatch(1);
            plugin.configure(Collections.<String, Object>emptyMap());
            assertTrue(host.brokerStarted.await(2, TimeUnit.SECONDS));
            plugin.handleMqttMessage("office/brightness/status", "{\"brightness\":70}");
            awaitEffect("None");
            stoppedAt = host.lightUpdates;
            Thread.sleep(600);
            assertEquals(stoppedAt, host.lightUpdates);
        }
        try (Socket client = connectLocalBrokerClient(true)) {
            assertTrue(client.isConnected());
            plugin.handleMqttMessage("office/brightness/status", "{\"brightness\":70}");
            plugin.onEvent("light.rgb_backlight",
                    Collections.<String, Object>singletonMap("effect", "Warning Red"));
            awaitEffect("Warning Red");
            plugin.stop();
            int stoppedAt = host.lightUpdates;
            Thread.sleep(600);
            assertEquals(stoppedAt, host.lightUpdates);
        }
    }

    @Test
    void manualOffCancelsNextWarningPhase() throws Exception {
        try (Socket client = startLocalBrokerClient(true)) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            plugin.handleMqttMessage("office/brightness/status", "{\"brightness\":70}");
            plugin.onEvent("light.rgb_backlight",
                    Collections.<String, Object>singletonMap("effect", "Warning Blue"));
            assertMqttPublication(in, "office/light/switch", "OFF");
            assertMqttPublication(in, "office/light/switch", "ON");
            assertMqttPublication(in, "office/brightness/set", "70");
            assertMqttPublication(in, "office/rgb/set", "0,0,255");
            awaitEffect("Warning Blue");
            plugin.onEvent("light.rgb_backlight",
                    Collections.<String, Object>singletonMap("on", Boolean.FALSE));
            assertMqttPublication(in, "office/light/switch", "OFF");
            awaitEffect("None");
            assertEquals(Boolean.FALSE, host.lastLightState.get("on"));
            client.setSoTimeout(700);
            assertThrows(SocketTimeoutException.class, () -> in.readUnsignedByte());
        }
    }

    @Test
    void rainbowSendsOnlyColorChangesAndKeepsManualCommandLogs() throws Exception {
        try (Socket client = startLocalBrokerClient(true)) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            plugin.handleMqttMessage("office/brightness/status", "{\"brightness\":70}");
            plugin.onEvent("light.rgb_backlight",
                    Collections.<String, Object>singletonMap("effect", "Rainbow"));
            assertMqttPublication(in, "office/light/switch", "ON");
            assertMqttPublication(in, "office/brightness/set", "70");
            assertMqttPublication(in, "office/rgb/set", "255,0,0");
            awaitEffect("Rainbow");
            for (int frame = 0; frame < 3; frame++) {
                assertEquals(0x30, in.readUnsignedByte());
                int length = in.readUnsignedByte();
                String topic = in.readUTF();
                byte[] payload = new byte[length - 2 - topic.getBytes(StandardCharsets.UTF_8).length];
                in.readFully(payload);
                assertEquals("office/rgb/set", topic);
            }
            int logCount = host.logsContaining("Embedded MQTT PUBLISH").size();
            for (int message = 0; message < 10_000; message++) {
                plugin.handleMqttMessage("office/rgb/status", "{\"rgb\":[255,0,0]}");
            }
            assertEquals(logCount, host.logsContaining("Embedded MQTT PUBLISH").size());
            plugin.handleMqttMessage("office/rgb/status", "invalid");
            assertEquals(1, host.logsContaining("Invalid RGB status").size());
            assertTrue(host.logsContaining("Embedded MQTT command topic=").isEmpty());
            plugin.onEvent("light.rgb_backlight",
                    Collections.<String, Object>singletonMap("on", Boolean.FALSE));
            awaitEffect("None");
            assertEquals(1, host.logsContaining("RGB effect started: Rainbow").size());
            assertEquals(1, host.logsContaining("RGB effect stopped: Rainbow").size());
            assertEquals(1, host.logsContaining("Embedded MQTT command topic=").size());
        }
    }

    @Test
    void identicalRoundedEffectFramesAreSkippedAndRestartResendsInitialState() throws Exception {
        try (Socket client = startLocalBrokerClient(true)) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            plugin.handleMqttMessage("office/brightness/status", "{\"brightness\":0}");
            for (int session = 0; session < 3; session++) {
                plugin.onEvent("light.rgb_backlight",
                        Collections.<String, Object>singletonMap("effect", "Breathe"));
                assertMqttPublication(in, "office/light/switch", "ON");
                assertMqttPublication(in, "office/brightness/set", "0");
                assertMqttPublication(in, "office/rgb/set", "255,255,255");
                awaitEffect("Breathe");
                client.setSoTimeout(700);
                assertThrows(SocketTimeoutException.class, () -> in.readUnsignedByte());
                plugin.onEvent("light.rgb_backlight",
                        Collections.<String, Object>singletonMap("effect", "None"));
                awaitEffect("None");
                client.setSoTimeout(2000);
            }
        }
    }

    @Test
    void warningEffectsSendOnlyPowerOffDuringTheDarkPhase() throws Exception {
        String[] names = {"Warning Red", "Warning Amber", "Warning Yellow", "Warning Green",
            "Warning Cyan", "Warning Blue", "Warning Magenta", "Warning White"};
        String[] colors = {"255,0,0", "255,128,0", "255,255,0", "0,255,0",
            "0,255,255", "0,0,255", "255,0,255", "255,255,255"};
        for (int index = 0; index < names.length; index++) {
            try (Socket client = startLocalBrokerClient(true)) {
                client.setSoTimeout(4000);
                DataInputStream in = new DataInputStream(client.getInputStream());
                plugin.handleMqttMessage("office/brightness/status", "{\"brightness\":70}");
                plugin.onEvent("light.rgb_backlight",
                        Collections.<String, Object>singletonMap("effect", names[index]));
                assertMqttPublication(in, "office/light/switch", "OFF");
                long initialOffAt = System.nanoTime();
                assertMqttPublication(in, "office/light/switch", "ON");
                assertTrue(System.nanoTime() - initialOffAt >= TimeUnit.MILLISECONDS.toNanos(450));
                assertMqttPublication(in, "office/brightness/set", "70");
                assertMqttPublication(in, "office/rgb/set", colors[index]);
                long colorAt = System.nanoTime();
                awaitEffect(names[index]);
                Map<String, Object> logicalState = new LinkedHashMap<String, Object>(host.lastLightState);
                assertEffectDisplayState();
                int updates = host.lightUpdates;
                assertMqttPublication(in, "office/light/switch", "OFF");
                assertTrue(System.nanoTime() - colorAt >= TimeUnit.MILLISECONDS.toNanos(450));
                long offAt = System.nanoTime();
                plugin.handleMqttMessage("office/light/status", "{\"state\":\"OFF\"}");
                plugin.handleMqttMessage("office/brightness/status", "{\"brightness\":5}");
                plugin.handleMqttMessage("office/rgb/status", "{\"rgb\":[0,0,0]}");
                assertNoCommand(client, in);
                assertEquals(logicalState, host.lastLightState);
                assertEquals(updates, host.lightUpdates);
                client.setSoTimeout(4000);
                assertMqttPublication(in, "office/light/switch", "ON");
                assertTrue(System.nanoTime() - offAt >= TimeUnit.MILLISECONDS.toNanos(450));
                assertMqttPublication(in, "office/brightness/set", "70");
                assertMqttPublication(in, "office/rgb/set", colors[index]);
                plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
                assertEquals(logicalState, host.lastLightState);
                assertEquals(updates, host.lightUpdates);
                plugin.onEvent("light.rgb_backlight",
                        Collections.<String, Object>singletonMap("effect", names[index]));
                assertMqttPublication(in, "office/light/switch", "OFF");
                long restartOffAt = System.nanoTime();
                assertNoCommand(client, in);
                client.setSoTimeout(4000);
                assertMqttPublication(in, "office/light/switch", "ON");
                assertTrue(System.nanoTime() - restartOffAt >= TimeUnit.MILLISECONDS.toNanos(450));
                assertMqttPublication(in, "office/brightness/set", "70");
                assertMqttPublication(in, "office/rgb/set", colors[index]);
                plugin.onEvent("light.rgb_backlight",
                        Collections.<String, Object>singletonMap("effect", "None"));
                awaitEffect("None");
                assertNoCommand(client, in);
            }
        }
    }

    private void assertEffectDisplayState() {
        assertEquals(Boolean.TRUE, host.lastLightState.get("on"));
        for (String channel : new String[] {"brightness", "red", "green", "blue"}) {
            assertEquals(1.0d, host.lastLightState.get(channel));
        }
    }

    private void awaitEffect(String effect) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((host.lastLightState == null || !effect.equals(host.lastLightState.get("effect")))
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(effect, host.lastLightState.get("effect"));
    }

    @Test
    void savedBrokerDisableIsIgnoredAndRecoveryUsesLocalBroker() throws Exception {
        plugin.stop();
        CountDownLatch checked = new CountDownLatch(1);
        Smt101MqttRecovery recovery = new Smt101MqttRecovery(new Smt101MqttRecovery.Backend() {
            @Override
            public void check(boolean reenableDisabledMqtt, Smt101MqttRecovery.Callback callback) {
                assertTrue(reenableDisabledMqtt);
                callback.complete(Smt101MqttRecovery.Result.RESTARTED, null);
                checked.countDown();
            }

            @Override
            public void stop() {
            }
        });
        host = new FakeHost();
        plugin = new Smt101Plugin(() -> now.get(), recovery, 0);
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("enableEmbeddedMqttBroker", Boolean.FALSE);
        settings.put("enableAutomaticMqttRecovery", Boolean.TRUE);
        settings.put("reenableDisabledMqtt", Boolean.TRUE);
        plugin.start(host, settings);
        assertTrue(host.brokerStarted.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (checked.getCount() != 0 && System.nanoTime() < deadline) {
            plugin.refreshEntityStates();
            checked.await(10, TimeUnit.MILLISECONDS);
        }
        assertEquals(0L, checked.getCount());
        now.addAndGet(Smt101Plugin.MQTT_SILENCE_WARNING_MS);
        plugin.refreshEntityStates();
        assertFalse(host.logsContaining("No MQTT publications received").isEmpty());
        assertFalse(host.logsContaining("capture broker listening").isEmpty());
        assertEquals("0 clients connected", host.diagnostics.get("last_mqtt_message"));
    }

    @Test
    void connectedClientsTileTracksMultipleClientsAndDisconnects() throws Exception {
        assertEquals("0", host.connectedClients);
        try (Socket first = startLocalBrokerClient(false)) {
            assertTrue(first.isConnected());
            assertEquals("1", host.connectedClients);
            try (Socket second = connectLocalBrokerClient(true, "second-client")) {
                assertTrue(second.isConnected());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!"2".equals(host.connectedClients) && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals("2", host.connectedClients);
                assertEquals("2 clients connected: rgb-test, second-client",
                        host.diagnostics.get("last_mqtt_message"));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"1".equals(host.connectedClients) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("1", host.connectedClients);
            assertEquals("1 client connected: rgb-test", host.diagnostics.get("last_mqtt_message"));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!"0".equals(host.connectedClients) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("0", host.connectedClients);
        assertEquals("0 clients connected", host.diagnostics.get("last_mqtt_message"));
    }

    @Test
    void ignoresSavedClientIdOverrideAndLearnsCommandPrefixFromLiveStatus() throws Exception {
        try (Socket initial = startLocalBrokerClient(false)) {
            assertTrue(initial.isConnected());
            host.brokerStarted = new CountDownLatch(1);
            host.clientConnected = new CountDownLatch(1);
            plugin.configure(Collections.<String, Object>singletonMap("mqttClientId", "old-client"));
            assertTrue(host.brokerStarted.await(2, TimeUnit.SECONDS));
            try (Socket client = connectLocalBrokerClient(true)) {
                plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
                assertOffCommand(client);
            }
        }
    }

    @Test
    void announcesOnlyCapabilitiesDetectedFromValidMessages() throws Exception {
        plugin.handleMqttMessage("office/sensors/temperature", "invalid");
        plugin.handleMqttMessage("office/unsupported", "message");
        assertTrue(host.logsContaining("Detected capabilities").isEmpty());

        plugin.handleMqttMessage("office/sensors/temperature", "26.4");
        plugin.handleMqttMessage("office/sensors/humidity", "38");
        plugin.handleMqttMessage("office/sensors/distance", "802");
        plugin.handleMqttMessage("office/brightness/status", "{\"brightness\":20}");
        plugin.handleMqttMessage("office/switch1/status", "{\"state\":\"OFF\"}");
        plugin.handleMqttMessage("office/door2/status", "{\"state\":\"ON\"}");
        assertEquals("Detected capabilities on this device: "
                + "Temperature, Humidity, Distance, RGB light, Switch 1, Door 2.", host.lastStatus);
        assertFalse(host.lastStatusError);
        for (int index = 0; index < 10_000; index++) {
            plugin.handleMqttMessage("office/sensors/temperature", "26.4");
        }
        assertEquals(6, host.logsContaining("Detected capabilities").size());

        host.brokerStarted = new CountDownLatch(1);
        plugin.configure(Collections.<String, Object>emptyMap());
        assertTrue(host.brokerStarted.await(2, TimeUnit.SECONDS));
        plugin.handleMqttMessage("office/sensors/temperature", "26.4");
        assertEquals("Detected capabilities on this device: Temperature.", host.lastStatus);
        plugin.stop();
        plugin.handleMqttMessage("office/sensors/humidity", "38");
        assertEquals(7, host.logsContaining("Detected capabilities").size());
    }

    @Test
    void brokerTileShowsOnlyClientCountRegardlessOfMessageTime() throws Exception {
        try (Socket client = startLocalBrokerClient(false)) {
            assertTrue(client.isConnected());
            plugin.refreshEntityStates();
            assertEquals("1 client connected: rgb-test", host.diagnostics.get("last_mqtt_message"));

            now.set(1_792_843_200_000L);
            plugin.handleMqttMessage("office/unsupported", "unrecognized payload");
            String expected = "1 client connected: rgb-test";
            assertEquals(expected, host.diagnostics.get("last_mqtt_message"));

            now.addAndGet(60_000L);
            plugin.refreshEntityStates();
            assertEquals(expected, host.diagnostics.get("last_mqtt_message"));

            plugin.handleMqttMessage("office/sensors/temperature", "invalid");
            assertEquals(expected, host.diagnostics.get("last_mqtt_message"));
        }
    }

    @Test
    void diagnosticUpdatesFromAnActualInboundMqttPacket() throws Exception {
        try (Socket client = startLocalBrokerClient(false)) {
            plugin.refreshEntityStates();
            host.diagnosticPublished = new CountDownLatch(1);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream body = new DataOutputStream(bytes);
            body.writeUTF("office/unsupported");
            body.write("message".getBytes(StandardCharsets.UTF_8));
            DataOutputStream out = new DataOutputStream(client.getOutputStream());
            out.writeByte(0x30);
            out.writeByte(bytes.size());
            out.write(bytes.toByteArray());
            out.flush();

            assertTrue(host.diagnosticPublished.await(2, TimeUnit.SECONDS));
            plugin.refreshEntityStates();
            assertEquals("1 client connected: rgb-test", host.diagnostics.get("last_mqtt_message"));
            assertEquals("message", host.lastPayload);
        }
    }

    @Test
    void payloadIndicatorTurnsRedAtOneMinuteAndGreenOnIdenticalPayload() throws Exception {
        try (Socket client = startLocalBrokerClient(false, true)) {
            assertTrue(client.isConnected());
            plugin.refreshMqttPayloadDiagnostic();
            assertEquals("off", host.payloadLevel);
            plugin.handleMqttMessage("office/unsupported", "message");
            assertEquals("on", host.payloadLevel);
            now.addAndGet(59_999L);
            plugin.refreshMqttPayloadDiagnostic();
            assertEquals("on", host.payloadLevel);
            now.incrementAndGet();
            plugin.refreshMqttPayloadDiagnostic();
            assertEquals("off", host.payloadLevel);
            assertEquals("message", host.lastPayload);
            plugin.handleMqttMessage("office/unsupported", "message");
            assertEquals("on", host.payloadLevel);
        }
    }

    @Test
    void payloadIndicatorStaysNeutralWhenAutomaticRecoveryIsDisabled() throws Exception {
        try (Socket client = startLocalBrokerClient(false)) {
            assertTrue(client.isConnected());
            plugin.refreshMqttPayloadDiagnostic();
            assertEquals("", host.payloadLevel);
            plugin.handleMqttMessage("office/unsupported", "message");
            assertEquals("", host.payloadLevel);
            assertEquals("message", host.lastPayload);
            now.addAndGet(60_000L);
            plugin.refreshMqttPayloadDiagnostic();
            assertEquals("", host.payloadLevel);
            assertEquals("message", host.lastPayload);
        }
    }

    @Test
    void payloadShowsLatestContentWithBoundedSanitizedDisplay() throws Exception {
        try (Socket client = startLocalBrokerClient(false)) {
            assertTrue(client.isConnected());
            assertEquals("Waiting for first message", host.lastPayload);
            plugin.handleMqttMessage("office/unsupported", "{\"temperature\":26.4}");
            assertEquals("{\"temperature\":26.4}", host.lastPayload);
            now.addAndGet(1000L);
            plugin.handleMqttMessage("office/unsupported", "first\nsecond\tthird\u0000");
            assertEquals("first second third ", host.lastPayload);
            now.addAndGet(1000L);
            String longPayload = String.join("", Collections.nCopies(1000, "x"));
            plugin.handleMqttMessage("office/unsupported", longPayload);
            assertEquals(String.join("", Collections.nCopies(77, "x")) + "...", host.lastPayload);
            assertEquals(80, host.lastPayload.length());
            now.addAndGet(1000L);
            plugin.handleMqttMessage("office/unsupported", "");
            assertEquals("(empty payload)", host.lastPayload);
        }
    }

    @Test
    void diagnosticRetainsOnlyLatestValueAndSkipsUnchangedUpdates() throws Exception {
        try (Socket client = startLocalBrokerClient(false)) {
            assertTrue(client.isConnected());
            plugin.refreshEntityStates();
            int before = host.diagnosticUpdates;
            for (int index = 0; index < 10_000; index++) {
                plugin.handleMqttMessage("office/unsupported", "message " + index);
            }
            assertEquals(before, host.diagnosticUpdates);
            assertEquals(1, host.diagnostics.size());
            assertEquals("message 0", host.lastPayload);

            now.addAndGet(1000L);
            plugin.handleMqttMessage("office/unsupported", "latest");
            assertEquals(before, host.diagnosticUpdates);
            assertEquals(1, host.diagnostics.size());
            assertEquals("latest", host.lastPayload);
        }
        int beforeStop = host.diagnosticUpdates;
        plugin.stop();
        // Kiosk clears tiles when it revokes the session; stop must not publish or remove tiles.
        assertEquals(beforeStop, host.diagnosticUpdates);
        host.diagnostics.clear();
        assertTrue(host.diagnostics.isEmpty());
        plugin.handleMqttMessage("office/unsupported", "late");
        plugin.refreshEntityStates();
        assertTrue(host.diagnostics.isEmpty());
    }

    @Test
    void diagnosticResetsWhenSettingsChange() throws Exception {
        try (Socket client = startLocalBrokerClient(false)) {
            assertTrue(client.isConnected());
            plugin.handleMqttMessage("office/unsupported", "message");
            host.diagnosticPublished = new CountDownLatch(1);
            host.payloadPublished = new CountDownLatch(1);
            Map<String, Object> settings = new LinkedHashMap<String, Object>();
            settings.put("enableEmbeddedMqttBroker", Boolean.FALSE);
            plugin.configure(settings);
            assertTrue(host.diagnosticPublished.await(2, TimeUnit.SECONDS));
            assertTrue(host.payloadPublished.await(2, TimeUnit.SECONDS));
            assertEquals("0 clients connected", host.diagnostics.get("last_mqtt_message"));
            assertEquals("Waiting for first message", host.lastPayload);
        }
        try (Socket client = startLocalBrokerClient(false)) {
            assertTrue(client.isConnected());
            plugin.refreshEntityStates();
            assertEquals("1 client connected: rgb-test", host.diagnostics.get("last_mqtt_message"));
        }
    }

    @Test
    void republishesLastKnownReadings() {
        plugin.handleMqttMessage("office/sensors/temperature", "{\"temperature\":26.4}");
        plugin.handleMqttMessage("office/sensors/humidity", "{\"humidity\":38.5}");
        plugin.handleMqttMessage("rk3576_u/sensors/distance", "{\"distance\":802}");
        plugin.handleMqttMessage("office/door1/status", "{\"state\":\"ON\"}");
        plugin.handleMqttMessage("office/switch2/status", "{\"state\":\"OFF\"}");
        host.published.clear();

        plugin.refreshEntityStates();

        assertEquals(
                sorted("sensor.temperature=26.4", "sensor.humidity=38.5",
                        "sensor.distance=802.0",
                        "binary_sensor.door1=true", "switch.switch2=false"),
                sorted(host.published));
    }

    @Test
    void publishesDistanceWithMillimetreMetadata() {
        plugin.handleMqttMessage("rk3576_u/sensors/distance", "{\"distance\":802}");
        assertEquals("mm", host.distanceMetadata.get("unit"));
        assertEquals("distance", host.distanceMetadata.get("deviceClass"));
        assertEquals("measurement", host.distanceMetadata.get("stateClass"));
        assertEquals(0, host.distanceMetadata.get("accuracyDecimals"));

        plugin.handleMqttMessage("rk3576_u/sensors/distance", "{\"distance\":2152}");

        assertEquals(sorted("sensor.distance=802.0", "sensor.distance=2152.0"),
                sorted(host.published));
    }

    @Test
    void createsOnlyEntitiesWhoseOwnValidPublicationsHaveArrived() {
        plugin.refreshEntityStates();
        assertTrue(host.published.isEmpty());
        plugin.handleMqttMessage("rk3576_u/sensors/distance", "{\"distance\":-1}");
        plugin.handleMqttMessage("rk3576_u/sensors/temperature", "{\"battery\":99}");
        plugin.handleMqttMessage("rk3576_u/sensors/humidity", "{\"battery\":99}");
        plugin.handleMqttMessage("rk3576_u/sensors/temperature", "NaN");
        plugin.handleMqttMessage("rk3576_u/sensors/humidity", "Infinity");
        plugin.handleMqttMessage("rk3576_u/door1/status", "{\"state\":\"unknown\"}");
        plugin.handleMqttMessage("rk3576_u/switch1/status", "{\"state\":\"unknown\"}");
        plugin.handleMqttMessage("rk3576_u/light/status", "{\"state\":\"unknown\"}");
        assertTrue(host.published.isEmpty());

        plugin.handleMqttMessage("rk3576_u/sensors/distance", "{\"distance\":802}");
        assertEquals(sorted("sensor.distance=802.0"), sorted(host.published));
        plugin.handleMqttMessage("rk3576_u/door2/status", "{\"state\":\"OFF\"}");
        plugin.handleMqttMessage("rk3576_u/switch1/status", "{\"state\":\"ON\"}");
        assertEquals(sorted("sensor.distance=802.0", "binary_sensor.door2=false", "switch.switch1=true"),
                sorted(host.published));

        host.published.clear();
        plugin.handleMqttMessage("rk3576_u/brightness/status", "{\"brightness\":20}");
        assertEquals(1, host.published.size());
        assertTrue(host.published.get(0).startsWith("light.rgb_backlight="));
    }

    @Test
    void configurationClearsDetectedEntitiesUntilFreshPublicationsArrive() throws Exception {
        plugin.handleMqttMessage("rk3576_u/sensors/distance", "{\"distance\":802}");
        plugin.handleMqttMessage("rk3576_u/light/status", "{\"state\":\"ON\"}");
        host.published.clear();
        host.removedSensors.clear();
        host.configured = new CountDownLatch(1);
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("enableEmbeddedMqttBroker", Boolean.FALSE);

        plugin.configure(settings);
        assertTrue(host.configured.await(2, TimeUnit.SECONDS));
        plugin.refreshEntityStates();
        assertTrue(host.published.isEmpty());
        assertTrue(host.removedSensors.contains("distance"));
        plugin.handleMqttMessage("rk3576_u/sensors/humidity", "{\"humidity\":53.4}");
        assertEquals(sorted("sensor.humidity=53.4"), sorted(host.published));
        host.published.clear();
        plugin.handleMqttMessage("rk3576_u/light/status", "{\"state\":\"OFF\"}");
        assertEquals(1, host.published.size());
        assertTrue(host.published.get(0).startsWith("light.rgb_backlight="));
    }

    @Test
    void rejectsInvalidDistanceWithoutReplacingTheLastReading() {
        plugin.handleMqttMessage("rk3576_u/sensors/distance", "{\"distance\":802}");
        host.published.clear();
        for (String payload : new String[] {
                "{\"temperature\":30}", "{\"distance\":-1}", "NaN", "Infinity", "unknown"}) {
            plugin.handleMqttMessage("rk3576_u/sensors/distance", payload);
        }
        assertTrue(host.published.isEmpty());
        assertEquals(5, host.logsContaining("Invalid distance reading").size());

        plugin.refreshEntityStates();
        assertEquals(sorted("sensor.distance=802.0"), sorted(host.published));
    }

    @Test
    void expiresDistanceAndRecoversOnTheNextReading() {
        plugin.handleMqttMessage("rk3576_u/sensors/distance", "{\"distance\":802}");
        now.addAndGet(Smt101Plugin.READING_MAX_AGE_MS - 1L);
        host.published.clear();
        plugin.refreshEntityStates();
        assertEquals(sorted("sensor.distance=802.0"), sorted(host.published));

        now.addAndGet(1L);
        host.published.clear();
        plugin.refreshEntityStates();
        assertEquals(sorted("sensor.distance=null"), sorted(host.published));
        assertEquals(1, host.logsContaining("No sensor.distance reading received").size());

        host.published.clear();
        plugin.refreshEntityStates();
        assertTrue(host.published.isEmpty());
        plugin.handleMqttMessage("rk3576_u/sensors/distance", "{\"distance\":2150}");
        assertEquals(sorted("sensor.distance=2150.0"), sorted(host.published));
    }

    @Test
    void expiresStaleNumericReadingsButKeepsDoorAndSwitchStates() {
        plugin.handleMqttMessage("office/sensors/temperature", "{\"temperature\":26.4}");
        plugin.handleMqttMessage("office/door1/status", "{\"state\":\"ON\"}");
        now.addAndGet(Smt101Plugin.READING_MAX_AGE_MS - 1L);
        plugin.handleMqttMessage("office/sensors/humidity", "{\"humidity\":38.5}");
        now.addAndGet(1L);
        host.published.clear();

        plugin.refreshEntityStates();

        assertEquals(
                sorted("sensor.temperature=null", "sensor.humidity=38.5", "binary_sensor.door1=true"),
                sorted(host.published));
        assertEquals(1, host.logsContaining("No sensor.temperature reading received").size());

        host.published.clear();
        plugin.refreshEntityStates();
        assertEquals(sorted("sensor.humidity=38.5", "binary_sensor.door1=true"), sorted(host.published));
    }

    @Test
    void warnsOnceWhenMqttGoesSilentAndReportsRecovery() {
        plugin.handleMqttMessage("office/sensors/temperature", "{\"temperature\":26.4}");
        now.addAndGet(Smt101Plugin.MQTT_SILENCE_WARNING_MS);

        plugin.refreshEntityStates();
        plugin.refreshEntityStates();

        assertEquals(1, host.logsContaining("No MQTT publications received for 10 minutes").size());
        assertTrue(host.lastStatusError);

        plugin.handleMqttMessage("office/sensors/temperature", "{\"temperature\":26.5}");

        assertEquals(1, host.logsContaining("MQTT publications resumed").size());
        assertFalse(host.lastStatusError);
    }

    @Test
    void logsRejectedUpdatesInsteadOfDroppingThemSilently() {
        host.rejection = "At most 64 entity changes per second are supported";

        plugin.handleMqttMessage("office/sensors/temperature", "{\"temperature\":26.4}");
        plugin.handleMqttMessage("office/sensors/temperature", "{\"temperature\":26.5}");

        List<String> rejections = host.logsContaining("Kiosk rejected sensor.temperature");
        assertEquals(1, rejections.size());
        assertTrue(rejections.get(0).contains("At most 64 entity changes"));

        host.rejection = null;
        plugin.refreshEntityStates();

        assertTrue(host.published.contains("sensor.temperature=26.5"));
        assertEquals(1, host.logsContaining("Kiosk accepted sensor.temperature updates again").size());
    }

    @Test
    void stopsWorkingOnceKioskRevokesTheSession() {
        host.rejection = "Plugin session has ended";

        plugin.handleMqttMessage("office/sensors/temperature", "{\"temperature\":26.4}");

        assertEquals(1, host.logsContaining("Kiosk ended this plugin session").size());
        host.rejection = null;
        plugin.refreshEntityStates();
        assertTrue(host.published.isEmpty());
    }

    @Test
    void deliversCommandsOffTheHostCallbackThread() throws Exception {
        host.statusReceived = new CountDownLatch(1);
        Map<String, Object> command = new LinkedHashMap<String, Object>();
        command.put("on", Boolean.TRUE);

        plugin.onEvent("switch.switch1", command);

        assertTrue(host.statusReceived.await(2, TimeUnit.SECONDS));
        assertTrue(host.lastStatus.contains("topic prefix is not known"));
        assertTrue(host.lastStatusThread.startsWith("smt101-commands"));
    }

    @Test
    void turnsRgbOffThroughTheUnsubscribedStatusOwner() throws Exception {
        try (Socket client = startLocalBrokerClient(false)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream body = new DataOutputStream(bytes);
            body.writeUTF("office/light/status");
            body.write("{\"state\":\"ON\"}".getBytes(StandardCharsets.UTF_8));
            DataOutputStream out = new DataOutputStream(client.getOutputStream());
            out.writeByte(0x30);
            out.writeByte(bytes.size());
            out.write(bytes.toByteArray());
            out.flush();
            assertOffCommand(client);
            assertTrue(host.commandSent.await(2, TimeUnit.SECONDS));
            assertEquals(1, host.logsContaining("matching status publisher").size());
        }
    }

    @Test
    void turnsOnRgbOffOnceAfterTheFirstValidPowerStatus() throws Exception {
        try (Socket client = startLocalBrokerClient()) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            plugin.handleMqttMessage("office/brightness/status", "{\"brightness\":20}");
            plugin.handleMqttMessage("office/light/status", "{\"state\":\"unknown\"}");
            assertNoCommand(client, in);

            plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
            client.setSoTimeout(2000);
            assertEquals(0x30, in.readUnsignedByte());
            int length = in.readUnsignedByte();
            String topic = in.readUTF();
            byte[] payload = new byte[length - 2 - topic.getBytes(StandardCharsets.UTF_8).length];
            in.readFully(payload);
            assertEquals("office/light/switch", topic);
            assertEquals("OFF", new String(payload, StandardCharsets.UTF_8));
            assertTrue(host.commandSent.await(2, TimeUnit.SECONDS));
            assertTrue(host.lastStatusThread.startsWith("smt101-commands"));
            assertEquals(Boolean.FALSE, host.lastLightState.get("on"));
            assertEquals(0.2d, host.lastLightState.get("brightness"));

            plugin.handleMqttMessage("office/light/status", "{\"state\":\"OFF\"}");
            plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
            assertNoCommand(client, in);
            assertEquals(1, host.logsContaining("RGB backlight is on after MQTT connection").size());

            host.brokerStarted = new CountDownLatch(1);
            host.clientConnected = new CountDownLatch(1);
            plugin.configure(Collections.<String, Object>emptyMap());
            assertTrue(host.brokerStarted.await(2, TimeUnit.SECONDS));
            try (Socket reconfiguredClient = connectLocalBrokerClient()) {
                plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
                assertOffCommand(reconfiguredClient);
                assertEquals(2, host.logsContaining("RGB backlight is on after MQTT connection").size());
            }
        }
    }

    @Test
    void leavesInitiallyOffRgbAndLaterPowerChangesAlone() throws Exception {
        try (Socket client = startLocalBrokerClient()) {
            plugin.handleMqttMessage("office/light/status", "{\"state\":\"OFF\"}");
            plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
            assertNoCommand(client, new DataInputStream(client.getInputStream()));
            assertTrue(host.logsContaining("RGB backlight is on after MQTT connection").isEmpty());
        }
    }

    @Test
    void userRgbCommandCancelsConnectionPowerCheck() throws Exception {
        try (Socket client = startLocalBrokerClient()) {
            host.statusReceived = new CountDownLatch(1);
            Map<String, Object> command = new LinkedHashMap<String, Object>();
            command.put("on", Boolean.TRUE);
            plugin.onEvent("light.rgb_backlight", command);
            assertTrue(host.statusReceived.await(2, TimeUnit.SECONDS));
            assertEquals("RGB backlight command could not be delivered because no RGB status has been received.",
                    host.lastStatus);
            assertTrue(host.lastStatusThread.startsWith("smt101-commands"));
            plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
            assertNoCommand(client, new DataInputStream(client.getInputStream()));
            assertTrue(host.logsContaining("RGB backlight is on after MQTT connection").isEmpty());
        }
    }

    @Test
    void noConnectionDoesNotRequestConnectionRgbOff() {
        plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
        assertTrue(host.logsContaining("RGB backlight is on after MQTT connection").isEmpty());
    }

    @Test
    void startingLocalBrokerDoesNotArmRgbOffBeforeAClientConnects() throws Exception {
        plugin.stop();
        host = new FakeHost();
        plugin = new Smt101Plugin(() -> now.get(), new Smt101MqttRecovery(), 0);
        plugin.start(host, Collections.<String, Object>emptyMap());
        assertTrue(host.brokerStarted.await(2, TimeUnit.SECONDS));
        plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
        assertTrue(host.logsContaining("RGB backlight is on after MQTT connection").isEmpty());
        try (Socket client = connectLocalBrokerClient()) {
            assertNoCommand(client, new DataInputStream(client.getInputStream()));
            plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
            assertOffCommand(client);
        }
    }

    @Test
    void reconnectArmsANewRgbOffCheckWithoutRestartingThePlugin() throws Exception {
        try (Socket client = startLocalBrokerClient()) {
            plugin.handleMqttMessage("office/light/status", "{\"state\":\"OFF\"}");
            assertNoCommand(client, new DataInputStream(client.getInputStream()));
        }
        host.clientConnected = new CountDownLatch(1);
        try (Socket client = connectLocalBrokerClient()) {
            plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
            assertOffCommand(client);
            plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
            assertNoCommand(client, new DataInputStream(client.getInputStream()));
            assertEquals(1, host.logsContaining("RGB backlight is on after MQTT connection").size());
        }
    }

    private static void assertOffCommand(Socket client) throws Exception {
        client.setSoTimeout(2000);
        DataInputStream in = new DataInputStream(client.getInputStream());
        assertMqttPublication(in, "office/light/switch", "OFF");
    }

    private static void assertMqttPublication(DataInputStream in, String expectedTopic, String expectedPayload)
            throws Exception {
        assertEquals(0x30, in.readUnsignedByte());
        int length = in.readUnsignedByte();
        String topic = in.readUTF();
        byte[] payload = new byte[length - 2 - topic.getBytes(StandardCharsets.UTF_8).length];
        in.readFully(payload);
        assertEquals(expectedTopic, topic);
        assertEquals(expectedPayload, new String(payload, StandardCharsets.UTF_8));
    }

    private Socket startLocalBrokerClient() throws Exception {
        return startLocalBrokerClient(true);
    }

    private Socket startLocalBrokerClient(boolean subscribe) throws Exception {
        return startLocalBrokerClient(subscribe, false);
    }

    private Socket startLocalBrokerClient(boolean subscribe, boolean recoveryEnabled) throws Exception {
        plugin.stop();
        host = new FakeHost();
        plugin = new Smt101Plugin(() -> now.get(), new Smt101MqttRecovery(), 0);
        plugin.start(host, Collections.<String, Object>singletonMap(
                "enableAutomaticMqttRecovery", Boolean.valueOf(recoveryEnabled)));
        assertTrue(host.brokerStarted.await(2, TimeUnit.SECONDS));
        return connectLocalBrokerClient(subscribe);
    }

    private Socket connectLocalBrokerClient() throws Exception {
        return connectLocalBrokerClient(true);
    }

    private Socket connectLocalBrokerClient(boolean subscribe) throws Exception {
        return connectLocalBrokerClient(subscribe, "rgb-test");
    }

    private Socket connectLocalBrokerClient(boolean subscribe, String clientId) throws Exception {
        Socket client = new Socket("127.0.0.1", host.brokerPort);
        client.setSoTimeout(2000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bytes);
        body.writeUTF("MQTT");
        body.writeByte(4);
        body.writeByte(2);
        body.writeShort(30);
        body.writeUTF(clientId);
        DataOutputStream out = new DataOutputStream(client.getOutputStream());
        out.writeByte(0x10);
        out.writeByte(bytes.size());
        out.write(bytes.toByteArray());
        out.flush();
        assertEquals(0x20020000, new DataInputStream(client.getInputStream()).readInt());
        assertTrue(host.clientConnected.await(2, TimeUnit.SECONDS));
        if (!subscribe) {
            return client;
        }
        bytes.reset();
        body.writeShort(1);
        body.writeUTF("office/#");
        body.writeByte(0);
        out.writeByte(0x82);
        out.writeByte(bytes.size());
        out.write(bytes.toByteArray());
        out.flush();
        DataInputStream in = new DataInputStream(client.getInputStream());
        assertEquals(0x90, in.readUnsignedByte());
        assertEquals(3, in.readUnsignedByte());
        assertEquals(1, in.readUnsignedShort());
        assertEquals(0, in.readUnsignedByte());
        return client;
    }

    private static void assertNoCommand(Socket client, DataInputStream in) throws Exception {
        client.setSoTimeout(150);
        assertThrows(SocketTimeoutException.class, () -> in.readUnsignedByte());
    }

    private static List<String> sorted(String... values) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, values);
        Collections.sort(list);
        return list;
    }

    private static List<String> sorted(List<String> values) {
        List<String> list;
        synchronized (values) {
            list = new ArrayList<String>(values);
        }
        Collections.sort(list);
        return list;
    }

    private static final class FakeHost implements PluginHost {
        volatile CountDownLatch configured = new CountDownLatch(1);
        volatile CountDownLatch diagnosticPublished = new CountDownLatch(1);
        final Map<String, String> diagnostics = Collections.synchronizedMap(new LinkedHashMap<String, String>());
        volatile int diagnosticUpdates;
        volatile String connectedClients;
        volatile String lastPayload;
        volatile String payloadLevel;
        volatile CountDownLatch statusReceived = new CountDownLatch(1);
        volatile CountDownLatch payloadPublished = new CountDownLatch(1);
        volatile CountDownLatch brokerStarted = new CountDownLatch(1);
        volatile CountDownLatch clientConnected = new CountDownLatch(1);
        final CountDownLatch commandSent = new CountDownLatch(1);
        volatile int brokerPort;
        final List<String> published = Collections.synchronizedList(new ArrayList<String>());
        final List<String> logs = Collections.synchronizedList(new ArrayList<String>());
        final List<String> removedSensors = Collections.synchronizedList(new ArrayList<String>());
        volatile Map<String, Object> distanceMetadata;
        volatile Map<String, Object> lastLightState;
        volatile String[] lightEffects;
        volatile int lightUpdates;
        volatile String rejection;
        volatile String lastStatus = "";
        volatile boolean lastStatusError;
        volatile String lastStatusThread = "";

        List<String> logsContaining(String text) {
            List<String> matches = new ArrayList<String>();
            synchronized (logs) {
                for (String log : logs) {
                    if (log.contains(text)) {
                        matches.add(log);
                    }
                }
            }
            return matches;
        }

        private void record(String id, Object state) {
            String message = rejection;
            if (message != null) {
                throw new IllegalStateException(message);
            }
            published.add(id + "=" + state);
        }

        @Override
        public void publishStatusTile(String key, String title, String level, String text) {
            if ("last_mqtt_payload".equals(key)) {
                assertEquals("Last MQTT Broker payload", title);
                assertTrue("".equals(level) || "on".equals(level) || "off".equals(level));
                payloadLevel = level;
                assertTrue(text.length() <= 80);
                assertFalse(text.chars().anyMatch(Character::isISOControl));
                lastPayload = text;
                payloadPublished.countDown();
                diagnosticPublished.countDown();
                return;
            }
            assertEquals("last_mqtt_message", key);
            assertEquals("MQTT Broker", title);
            connectedClients = text.split(" ")[0];
                assertTrue(text.startsWith(connectedClients + ("1".equals(connectedClients)
                        ? " client connected" : " clients connected")));
            assertEquals("0".equals(connectedClients) ? "off" : "on", level);
            assertTrue(text.length() <= 80);
            if (rejection != null) {
                throw new IllegalStateException(rejection);
            }
            diagnostics.put(key, text);
            diagnosticUpdates++;
            diagnosticPublished.countDown();
        }

        @Override
        public void removeStatusTile(String key) {
            diagnostics.remove(key);
        }

        @Override
        public void publishSensor(String key, String name, Map<String, Object> metadata, Double state) {
            if ("distance".equals(key)) {
                assertEquals("Distance", name);
                distanceMetadata = metadata;
            }
            record("sensor." + key, state);
        }

        @Override
        public void publishBinarySensor(String key, String name, String deviceClass, Boolean state) {
            record("binary_sensor." + key, state);
        }

        @Override
        public void publishSwitch(String key, String name, boolean state) {
            record("switch." + key, Boolean.valueOf(state));
        }

        @Override
        public void publishLight(String key, String name, String[] effects, Map<String, Object> state) {
            lightEffects = effects;
            lightUpdates++;
            lastLightState = new LinkedHashMap<String, Object>(state);
            record("light." + key, state);
        }

        @Override
        public void removeSensor(String key) {
            removedSensors.add(key);
        }

        @Override
        public void removeBinarySensor(String key) {
        }

        @Override
        public void removeSwitch(String key) {
        }

        @Override
        public void removeLight(String key) {
            configured.countDown();
        }

        @Override
        public void status(String message, boolean error) {
            lastStatus = message;
            lastStatusError = error;
            lastStatusThread = Thread.currentThread().getName();
            statusReceived.countDown();
            if ("RGB backlight command sent.".equals(message)) {
                commandSent.countDown();
            }
        }

        @Override
        public void showWindow(String title, String message, String buttonLabel) {
        }

        @Override
        public void hideWindow() {
        }

        @Override
        public void log(String message) {
            logs.add(message);
            String prefix = "Embedded MQTT capture broker listening on 127.0.0.1:";
            if (message.startsWith(prefix)) {
                brokerPort = Integer.parseInt(message.substring(prefix.length(), message.indexOf(". Point")));
                brokerStarted.countDown();
            }
            if (message.startsWith("Embedded MQTT client connected:")) {
                clientConnected.countDown();
            }
        }
    }
}
