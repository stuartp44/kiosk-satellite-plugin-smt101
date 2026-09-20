package com.stuartp44.smt101;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

public final class Smt101Plugin implements KioskPlugin {
    private static final int MQTT_BROKER_PORT = 1883;
    private static final String TEMPERATURE_NAME = "Temperature";
    private static final String HUMIDITY_NAME = "Humidity";
    private static final String RGB_BACKLIGHT_NAME = "RGB Backlight";
    private static final String RGB_BACKLIGHT_KEY = "rgb_backlight";

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new PluginThreadFactory());

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

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.running = true;
        scheduleConfiguration(Smt101Config.fromSettings(settings));
    }

    @Override
    public void configure(Map<String, Object> settings) {
        scheduleConfiguration(Smt101Config.fromSettings(settings));
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if ("switch.switch1".equals(event) || "switch.switch2".equals(event)) {
            handleSwitchCommand(event.substring("switch.".length()), payload);
            return;
        }
        if (!("light." + RGB_BACKLIGHT_KEY).equals(event)) {
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
                    public void onMessage(String topic, String payload) {
                        if (!running || mqttBrokerGeneration != generation) {
                            return;
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
                                    safePublishSwitch(binaryInput.key, binaryInput.name, state.booleanValue());
                                } else {
                                    safePublishBinarySensor(binaryInput, state);
                                }
                            }
                            return;
                        }
                        Smt101RgbLight.StatusTopic rgbStatus =
                                Smt101RgbLight.matchStatusTopic(topic);
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
                            safePublishSensor(
                                    "temperature",
                                    TEMPERATURE_NAME,
                                    sensorMetadata("°C", "temperature", 1),
                                    value);
                        } else if (type == Smt101MqttBroker.ReadingType.HUMIDITY) {
                            safePublishSensor(
                                    "humidity",
                                    HUMIDITY_NAME,
                                    sensorMetadata("%", "humidity", 0),
                                    value);
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
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    broker.run();
                } catch (Exception exception) {
                    if (running && mqttBrokerGeneration == generation) {
                        safeStatus("Embedded MQTT broker failed to start.", true);
                        safeLog("Embedded MQTT broker failed: " + summarize(exception));
                    }
                }
            }
        }, "smt101-mqtt-broker");
        thread.setDaemon(true);
        mqttBrokerThread = thread;
        thread.start();
    }

    private static String safeMqttText(String value) {
        return Smt101MqttBroker.payloadPreview(value == null ? "" : value);
    }

    private void stopEmbeddedMqttBroker() {
        mqttBrokerGeneration += 1L;
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
        safePublishSensor("temperature", TEMPERATURE_NAME, sensorMetadata("°C", "temperature", 1), null);
        safePublishSensor("humidity", HUMIDITY_NAME, sensorMetadata("%", "humidity", 0), null);
        safeRemoveBinarySensor("switch1");
        safeRemoveBinarySensor("switch2");
        safePublishBinarySensor(new Smt101BinaryStatus.Input("door1", "Door 1", "door", "", false), null);
        safePublishBinarySensor(new Smt101BinaryStatus.Input("door2", "Door 2", "door", "", false), null);
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
        } catch (RuntimeException ignored) {
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
            safeLog("Failed to publish RGB backlight entity: " + summarize(exception));
        }
    }

    private void safePublishBinarySensor(Smt101BinaryStatus.Input input, Boolean state) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        try {
            pluginHost.publishBinarySensor(input.key, input.name, input.deviceClass, state);
        } catch (RuntimeException ignored) {
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
        } catch (RuntimeException ignored) {
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
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "smt101-plugin");
            thread.setDaemon(true);
            return thread;
        }
    }
}
