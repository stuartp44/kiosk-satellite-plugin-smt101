package com.stuartp44.smt101;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

public final class Smt101Plugin implements KioskPlugin {
    private static final long RESTART_DELAY_SECONDS = 5L;
    private static final String TEMPERATURE_NAME = "SMT101 Temperature";
    private static final String HUMIDITY_NAME = "SMT101 Humidity";

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new PluginThreadFactory());
    private final GeteventReadingParser readingParser = new GeteventReadingParser();
    private final SystemPropertyReader propertyReader;

    private volatile PluginHost host;
    private volatile boolean running;
    private volatile Smt101Config config;
    private Process geteventProcess;
    private Thread readerThread;
    private ScheduledFuture<?> restartFuture;
    private long readerGeneration;

    public Smt101Plugin() {
        this(new ShellSystemPropertyReader());
    }

    Smt101Plugin(SystemPropertyReader propertyReader) {
        this.propertyReader = propertyReader;
    }

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
    }

    @Override
    public void stop() throws InterruptedException {
        running = false;
        cancelRestart();
        final CountDownLatch latch = new CountDownLatch(1);
        boolean completed = false;
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        removeEntities();
                        stopReader();
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
            stopReader();
        }
        executor.shutdownNow();
    }

    private void scheduleConfiguration(final Smt101Config newConfig) {
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    config = newConfig;
                    publishConfiguredEntities(newConfig);
                    stopReader();
                    startReader(newConfig.resolve(propertyReader));
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void startReader(final Smt101ResolvedConfig resolvedConfig) {
        cancelRestart();
        stopReader();
        if (!running) {
            return;
        }
        try {
            final Process process = new ProcessBuilder("sh", "-c", resolvedConfig.getGeteventCommand())
                    .redirectErrorStream(true)
                    .start();
            geteventProcess = process;
            readerGeneration += 1L;
            final long generation = readerGeneration;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runReaderLoop(generation, resolvedConfig, process);
                }
            }, "smt101-getevent");
            thread.setDaemon(true);
            readerThread = thread;
            safeStatus(
                    "Listening for SMT101 temperature/humidity on event"
                            + resolvedConfig.getTemperatureEventDevice()
                            + " and event"
                            + resolvedConfig.getHumidityEventDevice()
                            + ".",
                    false);
            safeLog("Started direct SMT101 sensor reader with command: " + resolvedConfig.getGeteventCommand());
            thread.start();
        } catch (Exception exception) {
            safeStatus("Failed to start getevent reader. Retrying soon.", true);
            safeLog("Failed to start getevent reader: " + summarize(exception));
            scheduleRestart(RESTART_DELAY_SECONDS);
        }
    }

    private void scheduleRestart(final long delaySeconds) {
        cancelRestart();
        if (!running) {
            return;
        }
        restartFuture = executor.schedule(new Runnable() {
            @Override
            public void run() {
                startReader(currentConfig().resolve(propertyReader));
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void cancelRestart() {
        if (restartFuture != null) {
            restartFuture.cancel(false);
            restartFuture = null;
        }
    }

    private void runReaderLoop(final long generation, Smt101ResolvedConfig resolvedConfig, Process process) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (running && generation == readerGeneration) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                final GeteventReading reading = readingParser.parseLine(line, resolvedConfig);
                if (reading == null) {
                    continue;
                }
                try {
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (running && generation == readerGeneration) {
                                publishReading(reading);
                            }
                        }
                    });
                } catch (RejectedExecutionException ignored) {
                    return;
                }
            }
        } catch (Exception exception) {
            if (running && generation == readerGeneration) {
                safeStatus("Direct sensor reader failed. Retrying soon.", true);
                safeLog("Direct sensor reader failed: " + summarize(exception));
            }
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Exception ignored) {
            }
            process.destroy();
            if (running && generation == readerGeneration) {
                safeStatus("Sensor reader stopped. Restarting soon.", true);
                scheduleRestart(RESTART_DELAY_SECONDS);
            }
        }
    }

    private void publishConfiguredEntities(Smt101Config activeConfig) {
        safePublishSensor("temperature", TEMPERATURE_NAME, sensorMetadata("°C", "temperature", 1), null);
        safePublishSensor("humidity", HUMIDITY_NAME, sensorMetadata("%", "humidity", 0), null);
    }

    private void publishReading(GeteventReading reading) {
        if (reading == null) {
            return;
        }
        switch (reading.getType()) {
            case TEMPERATURE:
                safePublishSensor("temperature", TEMPERATURE_NAME, sensorMetadata("°C", "temperature", 1), Double.valueOf(reading.getValue()));
                break;
            case HUMIDITY:
                safePublishSensor("humidity", HUMIDITY_NAME, sensorMetadata("%", "humidity", 0), Double.valueOf(reading.getValue()));
                break;
            default:
                break;
        }
    }

    private void removeEntities() {
        safeRemoveSensor("temperature");
        safeRemoveSensor("humidity");
    }

    private void stopReader() {
        cancelRestart();
        readerGeneration += 1L;
        Thread thread = readerThread;
        readerThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        Process process = geteventProcess;
        geteventProcess = null;
        if (process == null) {
            return;
        }
        try {
            process.destroy();
            if (!process.waitFor(1L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
    }

    private Smt101Config currentConfig() {
        return config == null ? Smt101Config.fromSettings(null) : config;
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
