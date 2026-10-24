package com.stuartp44.smt101;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

public final class Smt101Plugin implements KioskPlugin {
    private static final int MQTT_BROKER_PORT = 1883;
    private static final String TEMPERATURE_NAME = "Temperature";
    private static final String HUMIDITY_NAME = "Humidity";
    private static final String DISTANCE_NAME = "Distance";
    private static final String RGB_BACKLIGHT_NAME = "RGB Backlight";
    private static final String RGB_BACKLIGHT_KEY = "rgb_backlight";
    private static final String LAST_MQTT_MESSAGE_KEY = "last_mqtt_message";
    private static final String MQTT_PAYLOAD_KEY = "last_mqtt_payload";
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
    private final ScheduledExecutorService commandExecutor =
            Executors.newSingleThreadScheduledExecutor(new PluginThreadFactory("smt101-commands"));
    private final Object effectLock = new Object();
    private volatile String activeEffect = "None";
    private Smt101RgbLight.State effectLogicalState;
    private volatile long effectGeneration;
    private ScheduledFuture<?> effectTask;
    private final Clock clock;
    private final int mqttBrokerPort;
    private final Smt101MqttRecovery mqttRecovery;
    private boolean automaticMqttRecoveryEnabled;
    private final Object stateLock = new Object();
    private final LinkedHashSet<String> detectedCapabilities = new LinkedHashSet<String>();

    private volatile PluginHost host;
    private volatile boolean running;
    private Thread mqttBrokerThread;
    private Smt101MqttBroker mqttBroker;
    private volatile long mqttBrokerGeneration;
    private volatile long mqttClientGeneration;
    private volatile Smt101RgbLight.State rgbBacklightState = Smt101RgbLight.initialState();
    private volatile boolean rgbBacklightDetected;
    private final AtomicBoolean connectionRgbPowerPending = new AtomicBoolean(false);
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
    private String lastPublishedMqttDiagnostic;
    private String lastMqttPayload;
    private String lastPublishedMqttPayload;
    private String lastPublishedMqttPayloadLevel;
    private Long lastMqttPayloadPublishedMs;
    private volatile boolean mqttSilenceReported;
    private volatile boolean brokerFailureReported;
    private long mqttDiscoveryStartedMs;
    private boolean waitingForFirstPublication;
    private volatile boolean mqttDiscoveryTimeoutReported;

    public Smt101Plugin() {
        this(SYSTEM_CLOCK);
    }

    Smt101Plugin(Clock clock) {
        this(clock, new Smt101MqttRecovery());
    }

    Smt101Plugin(Clock clock, Smt101MqttRecovery mqttRecovery) {
        this(clock, mqttRecovery, MQTT_BROKER_PORT);
    }

    Smt101Plugin(Clock clock, Smt101MqttRecovery mqttRecovery, int mqttBrokerPort) {
        this.clock = clock;
        this.mqttRecovery = mqttRecovery;
        mqttRecovery.setStatusReporter(new Smt101MqttRecovery.StatusReporter() {
            @Override
            public void status(String message, boolean error) {
                safeStatus(message, error);
            }
        });
        this.mqttBrokerPort = mqttBrokerPort;
    }

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.running = true;
        scheduleConfiguration(Smt101Config.fromSettings(settings));
        scheduleStateRefresh();
        executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshMqttPayloadDiagnostic();
                } catch (RuntimeException exception) {
                    safeLog("Failed to refresh MQTT payload diagnostic: " + summarize(exception));
                }
            }
        }, 1L, 1L, TimeUnit.SECONDS);
    }

    @Override
    public void configure(Map<String, Object> settings) {
        cancelLightEffect();
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
        if (("light." + RGB_BACKLIGHT_KEY).equals(event)) {
            connectionRgbPowerPending.set(false);
            cancelLightEffect();
        }
        enqueueCommand(event, command);
    }

    private void enqueueCommand(final String event, final Map<String, Object> command) {
        final long generation = mqttBrokerGeneration;
        final long lightVersion = effectGeneration;
        try {
            commandExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (!running || mqttBrokerGeneration != generation
                            || (("light." + RGB_BACKLIGHT_KEY).equals(event)
                                && lightVersion != effectGeneration)) {
                        safeLog("Command " + event + " cancelled because the plugin session or configuration changed.");
                        return;
                    }
                    try {
                        handleCommandEvent(event, command, lightVersion);
                    } catch (RuntimeException exception) {
                        safeLog("Command " + event + " failed: " + summarize(exception));
                        safeStatus("Command could not be delivered: " + summarize(exception), true);
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            safeLog("Command " + event + " could not be queued: " + summarize(exception));
        }
    }

    private void handleCommandEvent(String event, Map<String, Object> payload, long lightVersion) {
        if ("switch.switch1".equals(event) || "switch.switch2".equals(event)) {
            handleSwitchCommand(event.substring("switch.".length()), payload);
            return;
        }

        if (!rgbBacklightDetected) {
            safeStatus("RGB backlight command could not be delivered because no RGB status has been received.", true);
            return;
        }
        if (payload == null) {
            safeStatus("RGB backlight command was empty.", true);
            return;
        }
        Object effect = payload.get("effect");
        if (effect != null && !"None".equals(effect) && !Boolean.FALSE.equals(payload.get("on"))) {
            if (!(effect instanceof String) || !Smt101LightEffects.supports((String) effect)) {
                safeStatus("Unknown RGB light effect: " + effect, true);
                return;
            }
            startLightEffect((String) effect, Smt101RgbLight.applyCommand(rgbBacklightState, payload),
                    lightVersion);
            return;
        }
        if (payload.size() == 1 && "None".equals(effect)) {
            safePublishRgbBacklight(rgbBacklightState);
            return;
        }
        sendRgbCommand(payload, lightVersion);
    }

    private boolean sendRgbCommand(Map<String, Object> payload, long effectVersion) {
        return sendRgbCommand(payload, effectVersion, null);
    }

    private boolean sendRgbCommand(Map<String, Object> payload, long effectVersion,
            Map<String, String> lastEffectCommands) {
        Smt101RgbLight.State requested = Smt101RgbLight.applyCommand(rgbBacklightState, payload);
        Smt101MqttBroker broker = mqttBroker;
        java.util.List<Smt101RgbLight.Publication> publications =
                Smt101RgbLight.commandPublications(rgbTopicPrefix, payload, requested);
        if (broker == null || publications.isEmpty()) {
            safeStatus("RGB backlight command could not be delivered because its MQTT topic prefix is not known.", true);
            return false;
        }
        if (lastEffectCommands != null && broker.connectedClientCount() == 0) {
            safeLog("RGB effect command not delivered; no MQTT client connected.");
            safeStatus("RGB backlight command could not be delivered: no MQTT client connected.", true);
            return false;
        }
        if (lastEffectCommands != null && !lastEffectCommands.isEmpty()
                && !lastEffectCommands.containsKey(publications.get(0).topic)) {
            lastEffectCommands.clear();
        }
        for (Smt101RgbLight.Publication publication : publications) {
            if (!running || effectVersion != effectGeneration) {
                return false;
            }
            if (lastEffectCommands != null
                    && publication.payload.equals(lastEffectCommands.get(publication.topic))) {
                continue;
            }
            boolean effectFrame = !"None".equals(activeEffect);
            if (!effectFrame) {
                safeLog("Embedded MQTT command topic=\"" + safeMqttText(publication.topic)
                        + "\", payload=\"" + Smt101MqttBroker.payloadPreview(publication.payload) + "\".");
            }
            Smt101MqttBroker.PublishResult result =
                    broker.publish(publication.topic, publication.payload);
            if (result == Smt101MqttBroker.PublishResult.NO_CLIENT) {
                safeLog("Embedded MQTT command not delivered; no matching subscriber or status publisher is connected.");
                safeStatus("RGB backlight command could not be delivered: no matching MQTT subscriber or status publisher.", true);
                return false;
            }
            if (lastEffectCommands != null) {
                lastEffectCommands.put(publication.topic, publication.payload);
            }
            if (!effectFrame) {
                logCommandDelivery(result);
            }
        }
        synchronized (effectLock) {
            if (!running || effectVersion != effectGeneration) {
                return false;
            }
            rgbBacklightState = requested;
            safePublishRgbBacklight(requested);
            if ("None".equals(activeEffect)) {
                safeStatus("RGB backlight command sent.", false);
            }
        }
        return true;
    }

    private void cancelLightEffect() {
        synchronized (effectLock) {
            if (!"None".equals(activeEffect)) {
                safeLog("RGB effect stopped: " + activeEffect + ".");
            }
            effectGeneration++;
            activeEffect = "None";
            effectLogicalState = null;
            if (effectTask != null) {
                effectTask.cancel(false);
                effectTask = null;
            }
        }
    }

    private void startLightEffect(final String name, final Smt101RgbLight.State base, long expectedVersion) {
        synchronized (effectLock) {
            if (!running || effectGeneration != expectedVersion) {
                return;
            }
            cancelLightEffect();
            activeEffect = name;
            safeLog("RGB effect started: " + name + ". Routine animation command and RGB status logs are suppressed.");
            effectLogicalState = new Smt101RgbLight.State(true, 1, 1, 1, 1);
            final long version = effectGeneration;
            final long brokerVersion = mqttBrokerGeneration;
            final long started = System.nanoTime();
            final boolean warning = name.startsWith("Warning ");
            effectTask = commandExecutor.scheduleWithFixedDelay(new Runnable() {
                private boolean published;
                private boolean warningOn;
                private final Map<String, String> lastCommands = new LinkedHashMap<String, String>();
                private long clientGeneration = mqttClientGeneration;

                @Override
                public void run() {
                    if (!running || version != effectGeneration || brokerVersion != mqttBrokerGeneration) {
                        return;
                    }
                    try {
                        if (clientGeneration != mqttClientGeneration) {
                            lastCommands.clear();
                            clientGeneration = mqttClientGeneration;
                        }
                        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                        Map<String, Object> frame = Smt101LightEffects.frame(name, base,
                                warning ? (warningOn ? 0L : Smt101LightEffects.WARNING_HOLD_MS) : elapsed);
                        boolean delivered = sendRgbCommand(frame, version, warning ? null : lastCommands);
                        synchronized (effectLock) {
                            if (version != effectGeneration) {
                                return;
                            }
                            if (!delivered) {
                                cancelLightEffect();
                                safePublishRgbBacklight(rgbBacklightState);
                            } else if (!published) {
                                publishRgbBacklight(effectLogicalState, name);
                                published = true;
                            }
                            if (delivered && warning) {
                                warningOn = !warningOn;
                            }
                        }
                    } catch (RuntimeException exception) {
                        synchronized (effectLock) {
                            if (version == effectGeneration) {
                                cancelLightEffect();
                                safePublishRgbBacklight(rgbBacklightState);
                                safeStatus("RGB effect stopped: " + summarize(exception), true);
                            }
                        }
                    }
                }
            }, 0L, warning ? Smt101LightEffects.WARNING_HOLD_MS : 250L, TimeUnit.MILLISECONDS);
        }
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
        if (!lastKnownStates.containsKey("switch." + key)) {
            safeStatus("Switch command could not be delivered because no switch status has been received.", true);
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
            safeLog("Embedded MQTT command not delivered; no matching subscriber or status publisher is connected.");
            safeStatus("Switch command could not be delivered: no matching MQTT subscriber or status publisher.", true);
            return;
        }
        logCommandDelivery(result);
        safeStatus(("switch1".equals(key) ? "Switch 1" : "Switch 2") + " command sent.", false);
    }

    private void logCommandDelivery(Smt101MqttBroker.PublishResult result) {
        if (result == Smt101MqttBroker.PublishResult.SUBSCRIBED) {
            safeLog("Embedded MQTT command delivered to subscribed OEM client.");
        } else {
            safeLog("Embedded MQTT command delivered to matching status publisher without requiring a subscription.");
        }
    }

    @Override
    public void stop() throws InterruptedException {
        running = false;
        cancelLightEffect();
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
        mqttRecovery.stop();
    }

    private void scheduleConfiguration(final Smt101Config newConfig) {
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    stopServices();
                    resetDetectedEntities();
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
        safeStatus("Detecting device capabilities, please wait.", false);
        synchronized (stateLock) {
            mqttDiscoveryStartedMs = clock.nowMillis();
            waitingForFirstPublication = true;
            mqttDiscoveryTimeoutReported = false;
        }
        automaticMqttRecoveryEnabled = resolvedConfig.isAutomaticMqttRecoveryEnabled();
        mqttRecovery.configure(resolvedConfig.isAutomaticMqttRecoveryEnabled(),
                resolvedConfig.isReenableMqttEnabled(), true);
        rgbTopicPrefix = null;
        switch1TopicPrefix = null;
        switch2TopicPrefix = null;
        startEmbeddedMqttBrokerIfEnabled(resolvedConfig);
        synchronized (stateLock) {
            publishMqttDiagnostic(true);
            publishMqttPayload(true);
        }
    }

    private void startEmbeddedMqttBrokerIfEnabled(final Smt101ResolvedConfig resolvedConfig) {
        mqttBrokerGeneration += 1L;
        final long generation = mqttBrokerGeneration;
        final Smt101MqttBroker broker = new Smt101MqttBroker(
                mqttBrokerPort,
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
                        synchronized (stateLock) {
                            if (running && mqttBrokerGeneration == generation) {
                                mqttClientGeneration++;
                                connectionRgbPowerPending.set(true);
                                publishMqttDiagnostic(false);
                                safeLog("Embedded MQTT client connected: clientId=\"" + safeMqttText(clientId)
                                        + "\", protocolLevel=" + protocolLevel + ", usernameSupplied=" + hasUsername + ".");
                            }
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
                        synchronized (stateLock) {
                            if (running && mqttBrokerGeneration == generation) {
                                publishMqttDiagnostic(false);
                                safeLog("Embedded MQTT client disconnected: clientId=\"" + safeMqttText(clientId)
                                        + "\", reason=\"" + safeMqttText(reason)
                                        + "\", connectedFor=" + (connectedMillis / 1000L) + "s.");
                            }
                        }
                    }

                    @Override
                    public void onMessage(String topic, String payload) {
                        synchronized (stateLock) {
                            if (!running || mqttBrokerGeneration != generation) {
                                return;
                            }
                            handleMqttMessageLocked(topic, payload);
                        }
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
        if (!running) {
            return;
        }
        long now = clock.nowMillis();
        waitingForFirstPublication = false;
        if (mqttDiscoveryTimeoutReported) {
            mqttDiscoveryTimeoutReported = false;
            safeLog("Built-in MQTT client publications received after startup timeout.");
            safeStatus("Built-in MQTT client publications received.", false);
        }
        lastMqttActivityMs = now;
        lastMqttPayload = mqttPayloadDisplay(payload);
        publishMqttDiagnostic(false);
        publishMqttPayload(false);
        if (!running) {
            return;
        }
        mqttRecovery.onPublication(host);
        if (mqttSilenceReported) {
            mqttSilenceReported = false;
            safeLog("MQTT publications resumed.");
            safeStatus("MQTT publications resumed.", false);
        }
        Smt101RgbLight.StatusTopic rgbStatus = Smt101RgbLight.matchStatusTopic(topic);
        if (rgbStatus == null || "None".equals(activeEffect)) {
            safeLog("Embedded MQTT PUBLISH topic=\"" + safeMqttText(topic) + "\", payload=\""
                    + Smt101MqttBroker.payloadPreview(payload) + "\".");
        }
        Smt101BinaryStatus.Input binaryInput = Smt101BinaryStatus.match(topic);
        if (binaryInput != null) {
            Boolean state = Smt101BinaryStatus.parseState(payload);
            if (state != null) {
                if (binaryInput.writable) {
                    if ("switch1".equals(binaryInput.key)) {
                        switch1TopicPrefix = binaryInput.topicPrefix;
                    } else {
                        switch2TopicPrefix = binaryInput.topicPrefix;
                    }
                    lastKnownStates.put("switch." + binaryInput.key, state);
                    safePublishSwitch(binaryInput.key, binaryInput.name, state.booleanValue());
                } else {
                    lastKnownStates.put("binary_sensor." + binaryInput.key, state);
                    safePublishBinarySensor(binaryInput, state);
                }
                recordCapability(binaryInput.name);
            }
            return;
        }
        if (rgbStatus != null) {
            Smt101RgbLight.State state = Smt101RgbLight.applyStatus(
                    rgbBacklightState, rgbStatus.type, payload);
            if (state != null) {
                rgbTopicPrefix = rgbStatus.prefix;
                rgbBacklightState = state;
                rgbBacklightDetected = true;
                safePublishRgbBacklight(state);
                recordCapability("RGB light");
                if (rgbStatus.type == Smt101RgbLight.StatusType.POWER
                        && connectionRgbPowerPending.compareAndSet(true, false) && state.on) {
                    Map<String, Object> command = new LinkedHashMap<String, Object>();
                    command.put("on", Boolean.FALSE);
                    safeLog("RGB backlight is on after MQTT connection; requesting OFF.");
                    enqueueCommand("light." + RGB_BACKLIGHT_KEY, command);
                }
            } else {
                safeLog("Invalid RGB status on MQTT topic \"" + safeMqttText(topic) + "\".");
            }
            return;
        }
        Smt101MqttBroker.ReadingType type = Smt101MqttBroker.inferReadingType(topic);
        Double value = Smt101MqttBroker.parseInferredReading(payload, type);
        if (value == null || value.isNaN() || value.isInfinite()
                || (type == Smt101MqttBroker.ReadingType.DISTANCE && value.doubleValue() < 0.0d)) {
            if (type != Smt101MqttBroker.ReadingType.UNKNOWN) {
                safeLog("Invalid " + type.name().toLowerCase(Locale.ROOT)
                        + " reading on MQTT topic \"" + safeMqttText(topic) + "\".");
            }
            return;
        }
        if (type == Smt101MqttBroker.ReadingType.TEMPERATURE) {
            lastKnownStates.put("sensor.temperature", value);
            lastReceivedMs.put("sensor.temperature", Long.valueOf(now));
            publishTemperature(value);
            recordCapability(TEMPERATURE_NAME);
        } else if (type == Smt101MqttBroker.ReadingType.HUMIDITY) {
            lastKnownStates.put("sensor.humidity", value);
            lastReceivedMs.put("sensor.humidity", Long.valueOf(now));
            publishHumidity(value);
            recordCapability(HUMIDITY_NAME);
        } else if (type == Smt101MqttBroker.ReadingType.DISTANCE) {
            lastKnownStates.put("sensor.distance", value);
            lastReceivedMs.put("sensor.distance", Long.valueOf(now));
            publishDistance(value);
            recordCapability(DISTANCE_NAME);
        }
    }

    private void recordCapability(String name) {
        if (!running || !detectedCapabilities.add(name)) {
            return;
        }
        String message = "Detected capabilities on this device: "
                + String.join(", ", detectedCapabilities) + ".";
        safeLog(message);
        safeStatus(message, false);
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
        Smt101MqttBroker broker = mqttBroker;
        if (Smt101MqttRecovery.canCheck(true,
                broker != null && broker.getBoundAddress() != null)) {
            mqttRecovery.check(host, now);
        }
        checkMqttSilence(now);
        synchronized (stateLock) {
            publishMqttDiagnostic(true);
            publishMqttPayload(true);
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
                } else if ("sensor.distance".equals(id)) {
                    publishDistance((Double) state);
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

    private void publishMqttDiagnostic(boolean force) {
        PluginHost pluginHost = host;
        if (!running || pluginHost == null) {
            return;
        }
        Smt101MqttBroker broker = mqttBroker;
        java.util.List<String> clientIds = broker == null
                ? java.util.Collections.<String>emptyList() : broker.connectedClientIds();
        int clients = clientIds.size();
        String state = clients + (clients == 1 ? " client connected" : " clients connected");
        if (!clientIds.isEmpty()) {
            StringBuilder names = new StringBuilder(state).append(": ");
            for (String clientId : clientIds) {
                if (names.length() > 80) {
                    break;
                }
                if (names.length() > state.length() + 2) {
                    names.append(", ");
                }
                names.append(mqttPayloadDisplay(clientId.isEmpty() ? "(unnamed)" : clientId));
            }
            state = mqttPayloadDisplay(names.toString());
        }
        if (!force && state.equals(lastPublishedMqttDiagnostic)) {
            return;
        }
        try {
            pluginHost.publishStatusTile(LAST_MQTT_MESSAGE_KEY, "MQTT Broker",
                    clients > 0 ? "on" : "off", state);
            lastPublishedMqttDiagnostic = state;
            clearPublishFailure("status_tile." + LAST_MQTT_MESSAGE_KEY);
        } catch (RuntimeException exception) {
            reportPublishFailure("status_tile." + LAST_MQTT_MESSAGE_KEY, state, exception);
        }
    }

    private void publishHumidity(Double value) {
        safePublishSensor("humidity", HUMIDITY_NAME, sensorMetadata("%", "humidity", 0), value);
    }

    private void publishMqttPayload(boolean force) {
        PluginHost pluginHost = host;
        if (!running || pluginHost == null) {
            return;
        }
        String state = lastMqttPayload == null ? "Waiting for first message" : lastMqttPayload;
        long now = clock.nowMillis();
        String level = !automaticMqttRecoveryEnabled ? ""
                : lastMqttPayload != null && now - lastMqttActivityMs < 60_000L ? "on" : "off";
        boolean levelChanged = !level.equals(lastPublishedMqttPayloadLevel);
        if (!force && !levelChanged && (state.equals(lastPublishedMqttPayload)
                || (lastMqttPayloadPublishedMs != null
                    && now / 1000L == lastMqttPayloadPublishedMs.longValue() / 1000L))) {
            return;
        }
        try {
            pluginHost.publishStatusTile(MQTT_PAYLOAD_KEY, "Last MQTT Broker payload", level, state);
            lastPublishedMqttPayload = state;
            lastPublishedMqttPayloadLevel = level;
            lastMqttPayloadPublishedMs = lastMqttPayload == null ? null : Long.valueOf(now);
            clearPublishFailure("status_tile." + MQTT_PAYLOAD_KEY);
        } catch (RuntimeException exception) {
            reportPublishFailure("status_tile." + MQTT_PAYLOAD_KEY, state, exception);
        }
    }

    void refreshMqttPayloadDiagnostic() {
        synchronized (stateLock) {
            if (running && waitingForFirstPublication && !mqttDiscoveryTimeoutReported
                    && clock.nowMillis() - mqttDiscoveryStartedMs >= 60_000L) {
                mqttDiscoveryTimeoutReported = true;
                String message = "No MQTT publications received from the built-in MQTT client within 60 seconds. "
                        + "Have you configured the built-in MQTT client settings? "
                        + "Setup: https://github.com/stuartp44/kiosk-satellite-plugin-smt101/blob/main/README.md#tablet-mqtt-settings";
                safeLog(message);
                safeStatus(message, true);
            }
            publishMqttPayload(false);
        }
    }

    private static String mqttPayloadDisplay(String payload) {
        if (payload.isEmpty()) {
            return "(empty payload)";
        }
        boolean shortened = payload.length() > 80;
        int length = shortened ? 77 : payload.length();
        if (length > 0 && Character.isHighSurrogate(payload.charAt(length - 1))) {
            length--;
        }
        StringBuilder display = new StringBuilder(80);
        for (int index = 0; index < length; index++) {
            char character = payload.charAt(index);
            display.append(Character.isISOControl(character) ? ' ' : character);
        }
        if (shortened) {
            display.append("...");
        }
        return display.toString();
    }

    private void publishDistance(Double value) {
        safePublishSensor("distance", DISTANCE_NAME, sensorMetadata("mm", "distance", 0), value);
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
        connectionRgbPowerPending.set(false);
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

    private void resetDetectedEntities() {
        synchronized (stateLock) {
            lastKnownStates.clear();
            lastReceivedMs.clear();
            lastPublishedMqttDiagnostic = null;
            rgbBacklightState = Smt101RgbLight.initialState();
            rgbBacklightDetected = false;
            removeEntities();
        }
    }

    private void removeEntities() {
        safeRemoveSensor("temperature");
        safeRemoveSensor("humidity");
        safeRemoveSensor("distance");
        safeRemoveBinarySensor("switch1");
        safeRemoveBinarySensor("switch2");
        safeRemoveSwitch("switch1");
        safeRemoveSwitch("switch2");
        safeRemoveBinarySensor("door1");
        safeRemoveBinarySensor("door2");
        synchronized (stateLock) {
            lastPublishedMqttDiagnostic = null;
            detectedCapabilities.clear();
            waitingForFirstPublication = false;
            mqttDiscoveryTimeoutReported = false;
            lastMqttPayload = null;
            lastPublishedMqttPayload = null;
            lastPublishedMqttPayloadLevel = null;
            lastMqttPayloadPublishedMs = null;
            PluginHost pluginHost = host;
            if (running && pluginHost != null) {
                try {
                    pluginHost.removeStatusTile(LAST_MQTT_MESSAGE_KEY);
                    pluginHost.removeStatusTile(MQTT_PAYLOAD_KEY);
                } catch (RuntimeException exception) {
                    safeLog("Failed to remove MQTT diagnostic: " + summarize(exception));
                }
            }
        }
        safeRemoveLight(RGB_BACKLIGHT_KEY);
    }

    private void stopServices() {
        cancelLightEffect();
        mqttRecovery.configure(false);
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
        synchronized (effectLock) {
            if (effectLogicalState == null) {
                publishRgbBacklight(state, "None");
            }
        }
    }

    private void publishRgbBacklight(Smt101RgbLight.State state, String effect) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        Map<String, Object> values = state.toEntityState();
        values.put("effect", effect);
        try {
            pluginHost.publishLight(
                    RGB_BACKLIGHT_KEY,
                    RGB_BACKLIGHT_NAME,
                    Smt101LightEffects.NAMES.clone(),
                    values);
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
        if (mqttDiscoveryTimeoutReported && !error) {
            return;
        }
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
