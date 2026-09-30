package com.stuartp44.smt101;

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
        host.published.clear();
    }

    @AfterEach
    void stop() throws Exception {
        plugin.stop();
    }

    @Test
    void republishesLastKnownReadings() {
        plugin.handleMqttMessage("office/sensors/temperature", "{\"temperature\":26.4}");
        plugin.handleMqttMessage("office/sensors/humidity", "{\"humidity\":38.5}");
        plugin.handleMqttMessage("office/door1/status", "{\"state\":\"ON\"}");
        plugin.handleMqttMessage("office/switch2/status", "{\"state\":\"OFF\"}");
        host.published.clear();

        plugin.refreshEntityStates();

        assertEquals(
                sorted("sensor.temperature=26.4", "sensor.humidity=38.5",
                        "binary_sensor.door1=true", "switch.switch2=false"),
                sorted(host.published));
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
        final CountDownLatch configured = new CountDownLatch(1);
        final CountDownLatch statusReceived = new CountDownLatch(1);
        final List<String> published = Collections.synchronizedList(new ArrayList<String>());
        final List<String> logs = Collections.synchronizedList(new ArrayList<String>());
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
            configured.countDown();
        }

        @Override
        public void removeSensor(String key) {
        }

        @Override
        public void removeBinarySensor(String key) {
        }

        @Override
        public void removeSwitch(String key) {
        }

        @Override
        public void removeLight(String key) {
        }

        @Override
        public void status(String message, boolean error) {
            lastStatus = message;
            lastStatusError = error;
            lastStatusThread = Thread.currentThread().getName();
            statusReceived.countDown();
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
        }
    }
}
