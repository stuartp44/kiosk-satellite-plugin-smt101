package com.stuartp44.smt101;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

public final class Smt101Plugin implements KioskPlugin {
    private static final int MQTT_BROKER_PORT = 1883;
    private static final String TEMPERATURE_NAME = "Temperature";
    private static final String HUMIDITY_NAME = "Humidity";
    private static final String RGB_BACKLIGHT_NAME = "RGB Backlight";
    private static final String RGB_BACKLIGHT_KEY = "rgb_backlight";
    private static final long STATE_REFRESH_SECONDS = 60L;
    private static final long FAILURE_LOG_INTERVAL_MS = 60_000L;
    static final long MQTT_SILENCE_WARNING_MS = 10L * 60_000L;
    static final long READING_MAX_AGE_MS = 30L * 60_000L;
    private static final long[] BROKER_RETRY_DELAYS_MS = {5_000L, 10_000L, 30_000L};
    private static final long BROKER_STABLE_RUN_MS = 60_000L;
    private static final Smt101BinaryStatus.Input DOOR1 =
            new Smt101BinaryStatus.Input("door1", "Door 1", "door", "", false);
    private static final Smt101BinaryStatus.Input DOOR2 =
            new Smt101BinaryStatus.Input("door2", "Door 2", "door", "", false);

    interface Clock {
        long nowMillis();
    }

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }
    };

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(new PluginThreadFactory("smt101-plugin"));
    // Commands write to the OEM client socket; keeping them off the host callback thread means a
    // stalled write cannot trip Kiosk's callback timeout and disable the plugin.
    private final ExecutorService commandExecutor =
            Executors.newSingleThreadExecutor(new PluginThreadFactory("smt101-commands"));
    private final Clock clock;
    private final Object stateLock = new Object();

    private volatile PluginHost host;
    private volatile boolean running;
    private Thread mqttBrokerThread;
    private Smt101MqttBroker mqttBroker;
    private volatile long mqttBrokerGeneration;
    private volatile Smt101RgbLight.State rgbBacklightState = Smt101RgbLight.initialState();
    private volatile String configuredTopicPrefix;
    private volatile String rgbTopicPrefix;
    private volatile String switch1TopicPrefix;
    private volatile String switch2TopicPrefix;
    private final Map<String, Object> lastKnownStates = new ConcurrentHashMap<String, Object>();
    private final Map<String, Long> lastFailureLogMs = new ConcurrentHashMap<String, Long>();
    private final Map<String, Integer> suppressedFailures = new ConcurrentHashMap<String, Integer>();
    private final Map<String, Long> lastReceivedMs = new ConcurrentHashMap<String, Long>();
    private final AtomicBoolean sessionRevoked = new AtomicBoolean(false);
    // 0 disables the silence check; set when the broker starts and on every MQTT publication.
    private volatile long lastMqttActivityMs;
    private volatile boolean mqttSilenceReported;
    private volatile boolean brokerFailureReported;

    public Smt101Plugin() {
        this(SYSTEM_CLOCK);
    }

    Smt101Plugin(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.running = true;
        scheduleConfiguration(Smt101Config.fromSettings(settings));
        scheduleStateRefresh();
    }

    @Override
    public void configure(Map<String, Object> settings) {
        scheduleConfiguration(Smt101Config.fromSettings(settings));
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
    }

    @Override
    public void onEvent(final String event, Map<String, Object> payload) {
        if (!"switch.switch1".equals(event) && !"switch.switch2".equals(event)
                && !("light." + RGB_BACKLIGHT_KEY).equals(event)) {
            return;
        }
        final Map<String, Object> command =
                payload == null ? null : new LinkedHashMap<String, Object>(payload);
        try {
            commandExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        handleCommandEvent(event, command);
                    } catch (RuntimeException exception) {
                        safeLog("Command " + event + " failed: " + summarize(exception));
                        safeStatus("Command could not be delivered: " + summarize(exception), true);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void handleCommandEvent(String event, Map<String, Object> payload) {
        if ("switch.switch1".equals(event) || "switch.switch2".equals(event)) {
            handleSwitchCommand(event.substring("switch.".length()), payload);
            return;
        }

        Smt101RgbLight.State requested = Smt101RgbLight.applyCommand(rgbBacklightState, payload);
        Smt101MqttBroker broker = mqttBroker;
        java.util.List<Smt101RgbLight.Publication> publications =
                Smt101RgbLight.commandPublications(rgbTopicPrefix, payload, requested);
        if (broker == null || publications.isEmpty()) {
            safeStatus("RGB backlight command could not be delivered because its MQTT topic prefix is not known.", true);
            return;
        }
        for (Smt101RgbLight.Publication publication : publications) {
            safeLog("Embedded MQTT command topic=\"" + safeMqttText(publication.topic)
                    + "\", payload=\"" + Smt101MqttBroker.payloadPreview(publication.payload) + "\".");
            Smt101MqttBroker.PublishResult result =
                    broker.publish(publication.topic, publication.payload);
            if (result == Smt101MqttBroker.PublishResult.NO_CLIENT) {
                safeLog("Embedded MQTT command not delivered; no OEM MQTT client is connected.");
                safeStatus("RGB backlight command could not be delivered because the OEM MQTT client is disconnected.", true);
                return;
            }
            logCommandDelivery(result);
        }
        rgbBacklightState = requested;
        safePublishRgbBacklight(requested);
        safeStatus("RGB backlight command sent.", false);
    }

    private void handleSwitchCommand(String key, Map<String, Object> payload) {
        Object requestedValue = payload == null ? null : payload.get("on");
        if (!(requestedValue instanceof Boolean)) {
            safeStatus("Switch command was missing an on/off state.", true);
            return;
        }
        String prefix = "switch1".equals(key) ? switch1TopicPrefix : switch2TopicPrefix;
        Smt101MqttBroker broker = mqttBroker;
        if (prefix == null || broker == null) {
            safeStatus("Switch command could not be delivered because its MQTT topic prefix is not known.", true);
            return;
        }
        boolean requested = ((Boolean) requestedValue).booleanValue();
        Smt101BinaryStatus.Input input =
                new Smt101BinaryStatus.Input(key, "", "", prefix, true);
        String topic = Smt101BinaryStatus.commandTopic(input);
        String mqttPayload = Smt101BinaryStatus.commandPayload(requested);
        safeLog("Embedded MQTT command topic=\"" + safeMqttText(topic)
                + "\", payload=\"" + Smt101MqttBroker.payloadPreview(mqttPayload) + "\".");
        Smt101MqttBroker.PublishResult result = broker.publish(topic, mqttPayload);
        if (result == Smt101MqttBroker.PublishResult.NO_CLIENT) {
            safeLog("Embedded MQTT command not delivered; no OEM MQTT client is connected.");
            safeStatus("Switch command could not be delivered because the OEM MQTT client is disconnected.", true);
            return;
        }
        logCommandDelivery(result);
        safeStatus(("switch1".equals(key) ? "Switch 1" : "Switch 2") + " command sent.", false);
    }

    private void logCommandDelivery(Smt101MqttBroker.PublishResult result) {
        if (result == Smt101MqttBroker.PublishResult.SUBSCRIBED) {
            safeLog("Embedded MQTT command delivered to subscribed OEM client.");
        } else {
            safeLog("Embedded MQTT command delivered to connected OEM client without requiring a subscription.");
        }
    }

    @Override
    public void stop() throws InterruptedException {
        running = false;
        final CountDownLatch latch = new CountDownLatch(1);
        boolean completed = false;
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        removeEntities();
                        stopServices();
                    } finally {
                        latch.countDown();
                    }
                }
            });
            completed = latch.await(2, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
        }
        if (!completed) {
            removeEntities();
            stopServices();
        }
        executor.shutdownNow();
        commandExecutor.shutdownNow();
    }

    private void scheduleConfiguration(final Smt101Config newConfig) {
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    publishConfiguredEntities();
                    stopServices();
                    startServices(newConfig.resolve());
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void startServices(final Smt101ResolvedConfig resolvedConfig) {
        if (!running) {
            return;
        }
        configuredTopicPrefix = resolvedConfig.getMqttTopicPrefix();
        rgbTopicPrefix = configuredTopicPrefix;
        switch1TopicPrefix = configuredTopicPrefix;
        switch2TopicPrefix = configuredTopicPrefix;
        if (!resolvedConfig.isMqttTopicPrefixValid()) {
            safeStatus(
                    "MQTT topic prefix is invalid. Remove MQTT wildcards (+ or #); automatic discovery is being used.",
                    true);
        }
        startEmbeddedMqttBrokerIfEnabled(resolvedConfig);
    }

    private void startEmbeddedMqttBrokerIfEnabled(final Smt101ResolvedConfig resolvedConfig) {
        if (!resolvedConfig.isEmbeddedMqttBrokerEnabled()) {
            return;
        }
        mqttBrokerGeneration += 1L;
        final long generation = mqttBrokerGeneration;
        final Smt101MqttBroker broker = new Smt101MqttBroker(
                MQTT_BROKER_PORT,
                new Smt101MqttBroker.Listener() {
                    @Override
                    public void onStarted(int port) {
                        if (running && mqttBrokerGeneration == generation) {
                            safeLog("Embedded MQTT capture broker listening on 127.0.0.1:" + port
                                    + ". Point the OEM MQTT client at 127.0.0.1.");
                            if (brokerFailureReported) {
                                brokerFailureReported = false;
                                safeStatus("Embedded MQTT broker recovered and is listening again.", false);
                            }
                        }
                    }

                    @Override
                    public void onClientConnected(String clientId, int protocolLevel, boolean hasUsername) {
                        if (running && mqttBrokerGeneration == generation) {
                            safeLog("Embedded MQTT client connected: clientId=\"" + safeMqttText(clientId)
                                    + "\", protocolLevel=" + protocolLevel + ", usernameSupplied=" + hasUsername + ".");
                        }
                    }

                    @Override
                    public void onClientSubscribed(java.util.List<String> topicFilters) {
                        if (running && mqttBrokerGeneration == generation) {
                            safeLog("Embedded MQTT client subscribed to " + topicFilters + ".");
                        }
                    }

                    @Override
                    public void onClientDisconnected(String clientId, String reason, long connectedMillis) {
                        if (running && mqttBrokerGeneration == generation) {
                            safeLog("Embedded MQTT client disconnected: clientId=\"" + safeMqttText(clientId)
                                    + "\", reason=\"" + safeMqttText(reason)
                                    + "\", connectedFor=" + (connectedMillis / 1000L) + "s.");
                        }
                    }

                    @Override
                    public void onMessage(String topic, String payload) {
                        if (!running || mqttBrokerGeneration != generation) {
                            return;
                        }
                        handleMqttMessage(topic, payload);
                    }

                    @Override
                    public void onError(String message) {
                        if (running && mqttBrokerGeneration == generation) {
                            safeLog("Embedded MQTT broker: " + message);
                        }
                    }
                });
        mqttBroker = broker;
        mqttSilenceReported = false;
        lastMqttActivityMs = clock.nowMillis();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runBrokerWithRetry(broker, generation);
            }
        }, "smt101-mqtt-broker");
        thread.setDaemon(true);
        mqttBrokerThread = thread;
        thread.start();
    }

    /**
     * Keeps the broker listening. Without this, a failed bind (for example while a previous
     * instance still holds port 1883) or an accept error would leave nothing listening until
     * the plugin is restarted, so the OEM client could never reconnect.
     */
    private void runBrokerWithRetry(Smt101MqttBroker broker, long generation) {
        int attempt = 0;
        while (running && mqttBrokerGeneration == generation) {
            long startedAt = System.currentTimeMillis();
            try {
                broker.run();
                return;
            } catch (Throwable failure) {
                if (!running || mqttBrokerGeneration != generation) {
                    return;
                }
                if (System.currentTimeMillis() - startedAt >= BROKER_STABLE_RUN_MS) {
                    attempt = 0;
                }
                long delay = BROKER_RETRY_DELAYS_MS[Math.min(attempt, BROKER_RETRY_DELAYS_MS.length - 1)];
                attempt++;
                brokerFailureReported = true;
                safeStatus("Embedded MQTT broker is not listening; retrying in " + (delay / 1000L) + " s.", true);
                safeLog("Embedded MQTT broker failed: " + failure.getClass().getSimpleName() + ": "
                        + summarize(failure) + ". Retry " + attempt + " in " + (delay / 1000L) + " s.");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    void handleMqttMessage(String topic, String payload) {
        synchronized (stateLock) {
            handleMqttMessageLocked(topic, payload);
        }
    }

    private void handleMqttMessageLocked(String topic, String payload) {
        long now = clock.nowMillis();
        lastMqttActivityMs = now;
        if (mqttSilenceReported) {
            mqttSilenceReported = false;
            safeLog("MQTT publications resumed.");
            safeStatus("MQTT publications resumed.", false);
        }
        safeLog("Embedded MQTT PUBLISH topic=\"" + safeMqttText(topic) + "\", payload=\""
                + Smt101MqttBroker.payloadPreview(payload) + "\".");
        Smt101BinaryStatus.Input binaryInput = Smt101BinaryStatus.match(topic);
        if (binaryInput != null) {
            Boolean state = Smt101BinaryStatus.parseState(payload);
            if (state != null) {
                if (binaryInput.writable) {
                    if (configuredTopicPrefix == null) {
                        if ("switch1".equals(binaryInput.key)) {
                            switch1TopicPrefix = binaryInput.topicPrefix;
                        } else {
                            switch2TopicPrefix = binaryInput.topicPrefix;
                        }
                    }
                    lastKnownStates.put("switch." + binaryInput.key, state);
                    safePublishSwitch(binaryInput.key, binaryInput.name, state.booleanValue());
                } else {
                    lastKnownStates.put("binary_sensor." + binaryInput.key, state);
                    safePublishBinarySensor(binaryInput, state);
                }
            }
            return;
        }
        Smt101RgbLight.StatusTopic rgbStatus = Smt101RgbLight.matchStatusTopic(topic);
        if (rgbStatus != null) {
            if (configuredTopicPrefix == null) {
                rgbTopicPrefix = rgbStatus.prefix;
            }
            Smt101RgbLight.State state = Smt101RgbLight.applyStatus(
                    rgbBacklightState, rgbStatus.type, payload);
            if (state != null) {
                rgbBacklightState = state;
                safePublishRgbBacklight(state);
            }
            return;
        }
        Smt101MqttBroker.ReadingType type = Smt101MqttBroker.inferReadingType(topic);
        Double value = Smt101MqttBroker.parseInferredReading(payload, type);
        if (value == null) {
            return;
        }
        if (type == Smt101MqttBroker.ReadingType.TEMPERATURE) {
            lastKnownStates.put("sensor.temperature", value);
            lastReceivedMs.put("sensor.temperature", Long.valueOf(now));
            publishTemperature(value);
        } else if (type == Smt101MqttBroker.ReadingType.HUMIDITY) {
            lastKnownStates.put("sensor.humidity", value);
            lastReceivedMs.put("sensor.humidity", Long.valueOf(now));
            publishHumidity(value);
        }
    }

    /**
     * Re-sends every reading received since the entities were last reset, so a host-side
     * update that was dropped does not leave an entity stuck until the OEM value changes.
     * Numeric readings older than {@link #READING_MAX_AGE_MS} are expired to Unknown instead,
     * so a silent OEM publisher is not masked by the resend. Door and switch states may only be
     * published by the OEM client when they change, so they are not expired.
     */
    void refreshEntityStates() {
        if (!running) {
            return;
        }
        long now = clock.nowMillis();
        checkMqttSilence(now);
        synchronized (stateLock) {
            for (Map.Entry<String, Object> entry : lastKnownStates.entrySet()) {
                String id = entry.getKey();
                Object state = entry.getValue();
                Long received = lastReceivedMs.get(id);
                if (received != null && now - received.longValue() >= READING_MAX_AGE_MS) {
                    lastKnownStates.remove(id);
                    lastReceivedMs.remove(id);
                    safeLog("No " + id + " reading received for " + (READING_MAX_AGE_MS / 60_000L)
                            + " minutes; marking it Unknown.");
                    state = null;
                }
                if ("sensor.temperature".equals(id)) {
                    publishTemperature((Double) state);
                } else if ("sensor.humidity".equals(id)) {
                    publishHumidity((Double) state);
                } else if ("binary_sensor.door1".equals(id)) {
                    safePublishBinarySensor(DOOR1, (Boolean) state);
                } else if ("binary_sensor.door2".equals(id)) {
                    safePublishBinarySensor(DOOR2, (Boolean) state);
                } else if ("switch.switch1".equals(id)) {
                    safePublishSwitch("switch1", "Switch 1", ((Boolean) state).booleanValue());
                } else if ("switch.switch2".equals(id)) {
                    safePublishSwitch("switch2", "Switch 2", ((Boolean) state).booleanValue());
                }
            }
        }
    }

    private void checkMqttSilence(long now) {
        long lastActivity = lastMqttActivityMs;
        if (lastActivity == 0L || mqttSilenceReported || now - lastActivity < MQTT_SILENCE_WARNING_MS) {
            return;
        }
        mqttSilenceReported = true;
        Smt101MqttBroker broker = mqttBroker;
        int clients = broker == null ? 0 : broker.connectedClientCount();
        String message = "No MQTT publications received for " + ((now - lastActivity) / 60_000L)
                + " minutes; " + clients + " OEM MQTT client connection(s) open.";
        safeLog(message);
        safeStatus(message, true);
    }

    private void publishTemperature(Double value) {
        safePublishSensor("temperature", TEMPERATURE_NAME, sensorMetadata("°C", "temperature", 1), value);
    }

    private void publishHumidity(Double value) {
        safePublishSensor("humidity", HUMIDITY_NAME, sensorMetadata("%", "humidity", 0), value);
    }

    private void scheduleStateRefresh() {
        try {
            executor.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        refreshEntityStates();
                    } catch (RuntimeException exception) {
                        safeLog("Failed to refresh entity states: " + summarize(exception));
                    }
                }
            }, STATE_REFRESH_SECONDS, STATE_REFRESH_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static String safeMqttText(String value) {
        return Smt101MqttBroker.payloadPreview(value == null ? "" : value);
    }

    private void stopEmbeddedMqttBroker() {
        mqttBrokerGeneration += 1L;
        lastMqttActivityMs = 0L;
        Smt101MqttBroker broker = mqttBroker;
        mqttBroker = null;
        if (broker != null) {
            broker.stop();
        }
        Thread thread = mqttBrokerThread;
        mqttBrokerThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void publishConfiguredEntities() {
        synchronized (stateLock) {
            lastKnownStates.clear();
            lastReceivedMs.clear();
        }
        publishTemperature(null);
        publishHumidity(null);
        safeRemoveBinarySensor("switch1");
        safeRemoveBinarySensor("switch2");
        safePublishBinarySensor(DOOR1, null);
        safePublishBinarySensor(DOOR2, null);
        safePublishRgbBacklight(rgbBacklightState);
    }

    private void removeEntities() {
        safeRemoveSensor("temperature");
        safeRemoveSensor("humidity");
        safeRemoveBinarySensor("switch1");
        safeRemoveBinarySensor("switch2");
        safeRemoveSwitch("switch1");
        safeRemoveSwitch("switch2");
        safeRemoveBinarySensor("door1");
        safeRemoveBinarySensor("door2");
        safeRemoveLight(RGB_BACKLIGHT_KEY);
    }

    private void stopServices() {
        stopEmbeddedMqttBroker();
    }

    private static Map<String, Object> sensorMetadata(String unit, String deviceClass, int accuracyDecimals) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("unit", unit);
        metadata.put("deviceClass", deviceClass);
        metadata.put("stateClass", "measurement");
        metadata.put("accuracyDecimals", Integer.valueOf(accuracyDecimals));
        return metadata;
    }

    private void safePublishSensor(String key, String name, Map<String, Object> metadata, Double state) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        try {
            pluginHost.publishSensor(key, name, metadata, state);
            clearPublishFailure("sensor." + key);
        } catch (RuntimeException exception) {
            reportPublishFailure("sensor." + key, state, exception);
        }
    }

    private void safeRemoveSensor(String key) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        try {
            pluginHost.removeSensor(key);
        } catch (RuntimeException ignored) {
        }
    }

    private void safePublishRgbBacklight(Smt101RgbLight.State state) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        try {
            pluginHost.publishLight(
                    RGB_BACKLIGHT_KEY,
                    RGB_BACKLIGHT_NAME,
                    new String[0],
                    state.toEntityState());
        } catch (RuntimeException exception) {
            reportPublishFailure("light." + RGB_BACKLIGHT_KEY, state.toEntityState(), exception);
        }
    }

    private void safePublishBinarySensor(Smt101BinaryStatus.Input input, Boolean state) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        try {
            pluginHost.publishBinarySensor(input.key, input.name, input.deviceClass, state);
            clearPublishFailure("binary_sensor." + input.key);
        } catch (RuntimeException exception) {
            reportPublishFailure("binary_sensor." + input.key, state, exception);
        }
    }

    private void safeRemoveBinarySensor(String key) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        try {
            pluginHost.removeBinarySensor(key);
        } catch (RuntimeException ignored) {
        }
    }

    private void safePublishSwitch(String key, String name, boolean state) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        try {
            pluginHost.publishSwitch(key, name, state);
            clearPublishFailure("switch." + key);
        } catch (RuntimeException exception) {
            reportPublishFailure("switch." + key, Boolean.valueOf(state), exception);
        }
    }

    /**
     * Kiosk rejects entity updates once it has revoked this plugin session or the update budget
     * is exceeded. Report those rejections (throttled per entity) instead of dropping them silently.
     * System.err reaches logcat even when the host has already revoked its own log channel.
     */
    private void reportPublishFailure(String entityId, Object state, RuntimeException exception) {
        if (isSessionRevoked(exception)) {
            onSessionRevoked(entityId, exception);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastFailureLogMs.get(entityId);
        if (last != null && now - last.longValue() < FAILURE_LOG_INTERVAL_MS) {
            Integer count = suppressedFailures.get(entityId);
            suppressedFailures.put(entityId, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
            return;
        }
        lastFailureLogMs.put(entityId, Long.valueOf(now));
        Integer suppressed = suppressedFailures.remove(entityId);
        String message = "Kiosk rejected " + entityId + " update (state=" + state + "): "
                + exception.getClass().getSimpleName() + ": " + summarize(exception)
                + (suppressed == null ? "" : " (" + suppressed + " similar failures suppressed)");
        System.err.println("smt101-sensors: " + message);
        safeLog(message);
    }

    private void clearPublishFailure(String entityId) {
        if (lastFailureLogMs.remove(entityId) != null) {
            suppressedFailures.remove(entityId);
            safeLog("Kiosk accepted " + entityId + " updates again.");
        }
    }

    // Kiosk's messages when an entity update arrives after it revoked this plugin session.
    private static boolean isSessionRevoked(RuntimeException exception) {
        String message = exception.getMessage();
        return exception instanceof IllegalStateException && message != null
                && (message.contains("Plugin session has ended")
                        || message.contains("SDK 1 entities access is required"));
    }

    /**
     * Kiosk never revives a revoked session; a restart creates a new plugin instance. Stop this
     * instance's broker so it does not keep holding port 1883 and block the new session's broker.
     */
    private void onSessionRevoked(String entityId, RuntimeException exception) {
        if (!sessionRevoked.compareAndSet(false, true)) {
            return;
        }
        running = false;
        String message = "Kiosk ended this plugin session (" + entityId + " update rejected: "
                + summarize(exception) + "); stopping the embedded MQTT broker.";
        System.err.println("smt101-sensors: " + message);
        safeLog(message);
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    stopServices();
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void safeRemoveSwitch(String key) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        try {
            pluginHost.removeSwitch(key);
        } catch (RuntimeException ignored) {
        }
    }

    private void safeRemoveLight(String key) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        try {
            pluginHost.removeLight(key);
        } catch (RuntimeException ignored) {
        }
    }

    private void safeStatus(String message, boolean error) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        try {
            pluginHost.status(message, error);
        } catch (RuntimeException ignored) {
        }
    }

    private void safeLog(String message) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        try {
            pluginHost.log(message);
        } catch (RuntimeException ignored) {
        }
    }

    private static String summarize(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return (message == null || message.trim().isEmpty()) ? throwable.getClass().getSimpleName() : message.trim();
    }

    private static final class PluginThreadFactory implements ThreadFactory {
        private final String name;

        PluginThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
