package com.stuartp44.smt101;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocketFactory;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public final class Smt101Plugin implements KioskPlugin {
    private static final long INITIAL_RECONNECT_DELAY_SECONDS = 2L;
    private static final long RETRY_RECONNECT_DELAY_SECONDS = 30L;
    private static final long LOST_CONNECTION_DELAY_SECONDS = 5L;
    private static final String TEMPERATURE_NAME = "SMT101 Temperature";
    private static final String HUMIDITY_NAME = "SMT101 Humidity";
    private static final String LIGHT_NAME = "SMT101 Light";
    private static final String INPUT1_NAME = "SMT101 Input 1";
    private static final String INPUT2_NAME = "SMT101 Input 2";
    private static final String NO_DEVICE_CLASS = "";

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new PluginThreadFactory());
    private final Smt101MessageProcessor messageProcessor = new Smt101MessageProcessor();

    private volatile PluginHost host;
    private volatile boolean running;
    private volatile Smt101Config config;
    private MqttAsyncClient mqttClient;
    private ScheduledFuture<?> reconnectFuture;
    private String resolvedClientId;
    private long clientGeneration;

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.running = true;
        scheduleConfiguration(Smt101Config.fromSettings(settings), true);
    }

    @Override
    public void configure(Map<String, Object> settings) {
        scheduleConfiguration(Smt101Config.fromSettings(settings), false);
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        safeLog("Ignoring unsupported command: " + command);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        safeLog("Ignoring unsupported event: " + event);
    }

    @Override
    public void stop() throws InterruptedException {
        running = false;
        cancelReconnect();
        final CountDownLatch latch = new CountDownLatch(1);
        boolean completed = false;
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        removeEntities();
                        disconnectClient();
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
            disconnectClient();
        }
        executor.shutdownNow();
    }

    private void scheduleConfiguration(final Smt101Config newConfig, final boolean initialStart) {
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    config = newConfig;
                    if (!newConfig.getMqttClientId().isEmpty()) {
                        resolvedClientId = null;
                    }
                    publishConfiguredEntities(newConfig);
                    if (!newConfig.hasBrokerHost()) {
                        disconnectClient();
                        safeStatus("Set MQTT broker host to start the SMT101 bridge.", false);
                        if (initialStart) {
                            safeLog("SMT101 plugin is idle until MQTT broker host is configured.");
                        }
                        return;
                    }
                    safeStatus("Connecting to " + newConfig.brokerUri(), false);
                    scheduleReconnect(INITIAL_RECONNECT_DELAY_SECONDS);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void scheduleReconnect(long delaySeconds) {
        cancelReconnect();
        if (!running) {
            return;
        }
        reconnectFuture = executor.schedule(new Runnable() {
            @Override
            public void run() {
                connectClient();
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void cancelReconnect() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
    }

    private void connectClient() {
        Smt101Config activeConfig = currentConfig();
        if (!running || !activeConfig.hasBrokerHost()) {
            return;
        }
        disconnectClient();
        try {
            clientGeneration += 1L;
            long generation = clientGeneration;
            MqttAsyncClient newClient = new MqttAsyncClient(activeConfig.brokerUri(), resolveClientId(activeConfig), new MemoryPersistence());
            newClient.setCallback(new ClientCallback(generation));
            mqttClient = newClient;
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(false);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            if (!activeConfig.getMqttUsername().isEmpty()) {
                options.setUserName(activeConfig.getMqttUsername());
            }
            if (!activeConfig.getMqttPassword().isEmpty()) {
                options.setPassword(activeConfig.getMqttPassword().toCharArray());
            }
            if (activeConfig.isMqttTls()) {
                options.setSocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault());
            }
            newClient.connect(options).waitForCompletion(10000L);
            subscribeToTopics(activeConfig, newClient);
            safeStatus("Connected to " + activeConfig.brokerUri(), false);
        } catch (Exception exception) {
            safeStatus("MQTT connection failed. Retrying soon.", true);
            safeLog("MQTT connect failed: " + summarize(exception));
            disconnectClient();
            scheduleReconnect(RETRY_RECONNECT_DELAY_SECONDS);
        }
    }

    private void subscribeToTopics(Smt101Config activeConfig, MqttAsyncClient client) throws MqttException {
        String[] topics = activeConfig.subscriptionTopics();
        if (topics.length == 0) {
            safeStatus("All SMT101 entity groups are disabled.", false);
            return;
        }
        int[] qos = new int[topics.length];
        Arrays.fill(qos, 0);
        client.subscribe(topics, qos).waitForCompletion(10000L);
        safeLog("Subscribed to SMT101 topics under prefix " + activeConfig.getTopicPrefix());
    }

    private void handleConnectComplete(long generation, boolean reconnect, String serverUri) {
        if (!isActiveGeneration(generation)) {
            return;
        }
        safeLog((reconnect ? "Reconnected to " : "Connected to ") + serverUri);
    }

    private void handleConnectionLost(long generation, Throwable cause) {
        if (!isActiveGeneration(generation)) {
            return;
        }
        safeStatus("MQTT disconnected. Reconnecting…", true);
        safeLog("MQTT connection lost: " + summarize(cause));
        scheduleReconnect(LOST_CONNECTION_DELAY_SECONDS);
    }

    private void handleMessage(long generation, String topic, String payload) {
        if (!isActiveGeneration(generation)) {
            return;
        }
        ProcessedMessage processed = messageProcessor.process(currentConfig(), topic, payload);
        if (!processed.isMatched()) {
            return;
        }
        if (!processed.isValid()) {
            safeLog("Ignoring malformed payload on " + topic + ": " + abbreviate(payload));
            return;
        }
        publishProcessedMessage(processed);
    }

    private boolean isActiveGeneration(long generation) {
        return running && generation == clientGeneration;
    }

    private void publishConfiguredEntities(Smt101Config activeConfig) {
        if (activeConfig.isEnvironmentSensorsEnabled()) {
            safePublishSensor("temperature", TEMPERATURE_NAME, sensorMetadata("°C", "temperature", 1), null);
            safePublishSensor("humidity", HUMIDITY_NAME, sensorMetadata("%", "humidity", 0), null);
            safePublishSensor("light", LIGHT_NAME, sensorMetadata("lx", "illuminance", 0), null);
        } else {
            safeRemoveSensor("temperature");
            safeRemoveSensor("humidity");
            safeRemoveSensor("light");
        }
        if (activeConfig.isInputsEnabled()) {
            safePublishBinarySensor("input1", INPUT1_NAME, NO_DEVICE_CLASS, null);
            safePublishBinarySensor("input2", INPUT2_NAME, NO_DEVICE_CLASS, null);
        } else {
            safeRemoveBinarySensor("input1");
            safeRemoveBinarySensor("input2");
        }
    }

    private void publishProcessedMessage(ProcessedMessage processed) {
        Smt101Topic topic = processed.getTopic();
        if (topic == null) {
            return;
        }
        switch (topic) {
            case TEMPERATURE:
                safePublishSensor("temperature", TEMPERATURE_NAME, sensorMetadata("°C", "temperature", 1), processed.getNumericValue());
                break;
            case HUMIDITY:
                safePublishSensor("humidity", HUMIDITY_NAME, sensorMetadata("%", "humidity", 0), processed.getNumericValue());
                break;
            case LIGHT:
                safePublishSensor("light", LIGHT_NAME, sensorMetadata("lx", "illuminance", 0), processed.getNumericValue());
                break;
            case INPUT1:
                safePublishBinarySensor("input1", INPUT1_NAME, NO_DEVICE_CLASS, processed.getBinaryValue());
                break;
            case INPUT2:
                safePublishBinarySensor("input2", INPUT2_NAME, NO_DEVICE_CLASS, processed.getBinaryValue());
                break;
            default:
                break;
        }
    }

    private void removeEntities() {
        safeRemoveSensor("temperature");
        safeRemoveSensor("humidity");
        safeRemoveSensor("light");
        safeRemoveBinarySensor("input1");
        safeRemoveBinarySensor("input2");
    }

    private void disconnectClient() {
        MqttAsyncClient client = mqttClient;
        mqttClient = null;
        clientGeneration += 1L;
        if (client == null) {
            return;
        }
        try {
            client.setCallback(null);
        } catch (RuntimeException ignored) {
        }
        try {
            if (client.isConnected()) {
                client.disconnect().waitForCompletion(5000L);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    private Smt101Config currentConfig() {
        return config == null ? Smt101Config.fromSettings(null) : config;
    }

    private String resolveClientId(Smt101Config activeConfig) {
        if (!activeConfig.getMqttClientId().isEmpty()) {
            return activeConfig.getMqttClientId();
        }
        if (resolvedClientId == null || resolvedClientId.isEmpty()) {
            resolvedClientId = "smt101-sensors-" + UUID.randomUUID().toString();
        }
        return resolvedClientId;
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

    private void safePublishBinarySensor(String key, String name, String deviceClass, Boolean state) {
        PluginHost pluginHost = host;
        if (pluginHost == null) {
            return;
        }
        try {
            pluginHost.publishBinarySensor(key, name, deviceClass, state);
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

    private static String abbreviate(String payload) {
        if (payload == null) {
            return "";
        }
        String normalized = payload.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }

    private final class ClientCallback implements MqttCallbackExtended {
        private final long generation;

        private ClientCallback(long generation) {
            this.generation = generation;
        }

        @Override
        public void connectComplete(final boolean reconnect, final String serverURI) {
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        handleConnectComplete(generation, reconnect, serverURI);
                    }
                });
            } catch (RejectedExecutionException ignored) {
            }
        }

        @Override
        public void connectionLost(final Throwable cause) {
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        handleConnectionLost(generation, cause);
                    }
                });
            } catch (RejectedExecutionException ignored) {
            }
        }

        @Override
        public void messageArrived(final String topic, final MqttMessage message) {
            final String payload = message == null ? "" : new String(message.getPayload(), StandardCharsets.UTF_8);
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        handleMessage(generation, topic, payload);
                    }
                });
            } catch (RejectedExecutionException ignored) {
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
        }
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
