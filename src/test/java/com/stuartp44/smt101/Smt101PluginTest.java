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
    void startWithoutBroker() throws Exception {
        host = new FakeHost();
        plugin = new Smt101Plugin(new Smt101Plugin.Clock() {
            @Override
            public long nowMillis() {
                return now.get();
            }
        });
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("enableEmbeddedMqttBroker", Boolean.FALSE);
        plugin.start(host, settings);
        assertTrue(host.configured.await(2, TimeUnit.SECONDS));
        assertTrue(host.published.isEmpty());
    }

    @AfterEach
    void stop() throws Exception {
        plugin.stop();
        assertTrue(host.removedSensors.contains("distance"));
    }

    @Test
    void externalModeRunsRecoveryWithoutLocalBrokerOrLocalSilenceWarning() throws Exception {
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
        plugin = new Smt101Plugin(() -> now.get(), recovery);
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("enableEmbeddedMqttBroker", Boolean.FALSE);
        settings.put("enableAutomaticMqttRecovery", Boolean.TRUE);
        settings.put("reenableDisabledMqtt", Boolean.TRUE);
        plugin.start(host, settings);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (checked.getCount() != 0 && System.nanoTime() < deadline) {
            plugin.refreshEntityStates();
            checked.await(10, TimeUnit.MILLISECONDS);
        }
        assertEquals(0L, checked.getCount());
        assertTrue(host.lastStatus.contains("external broker delivery is not monitored"));
        now.addAndGet(Smt101Plugin.MQTT_SILENCE_WARNING_MS);
        plugin.refreshEntityStates();
        assertTrue(host.logsContaining("No MQTT publications received").isEmpty());
        assertTrue(host.logsContaining("capture broker listening").isEmpty());
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
        Map<String, Object> command = new LinkedHashMap<String, Object>();
        command.put("on", Boolean.TRUE);

        plugin.onEvent("switch.switch1", command);

        assertTrue(host.statusReceived.await(2, TimeUnit.SECONDS));
        assertTrue(host.lastStatus.contains("topic prefix is not known"));
        assertTrue(host.lastStatusThread.startsWith("smt101-commands"));
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
            Map<String, Object> command = new LinkedHashMap<String, Object>();
            command.put("on", Boolean.TRUE);
            plugin.onEvent("light.rgb_backlight", command);
            assertTrue(host.statusReceived.await(2, TimeUnit.SECONDS));
            plugin.handleMqttMessage("office/light/status", "{\"state\":\"ON\"}");
            assertNoCommand(client, new DataInputStream(client.getInputStream()));
            assertTrue(host.logsContaining("RGB backlight is on after MQTT connection").isEmpty());
        }
    }

    @Test
    void externalModeDoesNotRequestConnectionRgbOff() {
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
        assertEquals(0x30, in.readUnsignedByte());
        int length = in.readUnsignedByte();
        String topic = in.readUTF();
        byte[] payload = new byte[length - 2 - topic.getBytes(StandardCharsets.UTF_8).length];
        in.readFully(payload);
        assertEquals("office/light/switch", topic);
        assertEquals("OFF", new String(payload, StandardCharsets.UTF_8));
    }

    private Socket startLocalBrokerClient() throws Exception {
        plugin.stop();
        host = new FakeHost();
        plugin = new Smt101Plugin(() -> now.get(), new Smt101MqttRecovery(), 0);
        plugin.start(host, Collections.<String, Object>emptyMap());
        assertTrue(host.brokerStarted.await(2, TimeUnit.SECONDS));
        return connectLocalBrokerClient();
    }

    private Socket connectLocalBrokerClient() throws Exception {
        Socket client = new Socket("127.0.0.1", host.brokerPort);
        client.setSoTimeout(2000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bytes);
        body.writeUTF("MQTT");
        body.writeByte(4);
        body.writeByte(2);
        body.writeShort(30);
        body.writeUTF("rgb-test");
        DataOutputStream out = new DataOutputStream(client.getOutputStream());
        out.writeByte(0x10);
        out.writeByte(bytes.size());
        out.write(bytes.toByteArray());
        out.flush();
        assertEquals(0x20020000, new DataInputStream(client.getInputStream()).readInt());
        assertTrue(host.clientConnected.await(2, TimeUnit.SECONDS));
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
        final CountDownLatch statusReceived = new CountDownLatch(1);
        volatile CountDownLatch brokerStarted = new CountDownLatch(1);
        volatile CountDownLatch clientConnected = new CountDownLatch(1);
        final CountDownLatch commandSent = new CountDownLatch(1);
        volatile int brokerPort;
        final List<String> published = Collections.synchronizedList(new ArrayList<String>());
        final List<String> logs = Collections.synchronizedList(new ArrayList<String>());
        final List<String> removedSensors = Collections.synchronizedList(new ArrayList<String>());
        volatile Map<String, Object> distanceMetadata;
        volatile Map<String, Object> lastLightState;
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
