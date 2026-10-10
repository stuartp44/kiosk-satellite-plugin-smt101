package com.stuartp44.smt101;

import me.jxl.kiosk.plugins.PluginHost;

final class Smt101MqttRecovery {
    static final long RETRY_INTERVAL_MS = 5L * 60_000L;

    enum Result {
        RUNNING, DISABLED, RESTARTED, REENABLED
    }

    interface Callback {
        void complete(Result result, String error);
    }

    interface Backend {
        void check(boolean reenableDisabledMqtt, Callback callback);
        void stop();
    }

    private Backend backend;
    private boolean enabled;
    private boolean reenableDisabledMqtt;
    private boolean pending;
    private long generation;
    private long nextCheckMs;
    private String lastProblem;
    private long publicationCount;
    private boolean waitingForPublications;
    private boolean localMqttMode;

    Smt101MqttRecovery() {
    }

    Smt101MqttRecovery(Backend backend) {
        this.backend = backend;
    }

    synchronized void configure(boolean enabled) {
        configure(enabled, false);
    }

    synchronized void configure(boolean enabled, boolean reenableDisabledMqtt) {
        configure(enabled, reenableDisabledMqtt, true);
    }

    synchronized void configure(boolean enabled, boolean reenableDisabledMqtt, boolean localMqttMode) {
        this.enabled = enabled;
        this.reenableDisabledMqtt = enabled && reenableDisabledMqtt;
        this.localMqttMode = localMqttMode;
        generation++;
        nextCheckMs = 0L;
        lastProblem = null;
        waitingForPublications = false;
    }

    synchronized void stop() {
        configure(false);
        if (backend != null) {
            backend.stop();
        }
    }

    synchronized void check(final PluginHost host, final long now) {
        if (!enabled || pending || now < nextCheckMs) {
            return;
        }
        final long requestGeneration = generation;
        final long publicationsAtRequest = publicationCount;
        try {
            if (backend == null) {
                backend = new Smt101OemRecovery();
            }
            pending = true;
            backend.check(reenableDisabledMqtt, new Callback() {
                @Override
                public void complete(Result result, String error) {
                    Smt101MqttRecovery.this.complete(host, now, requestGeneration,
                            publicationsAtRequest, result, error);
                }
            });
        } catch (RuntimeException exception) {
            pending = false;
            nextCheckMs = now + RETRY_INTERVAL_MS;
            problem(host, "Automatic MQTT recovery failed: " + exception);
        }
    }

    private synchronized void complete(PluginHost host, long now, long requestGeneration,
            long publicationsAtRequest, Result result, String error) {
        pending = false;
        if (!enabled || requestGeneration != generation) {
            return;
        }
        nextCheckMs = now + RETRY_INTERVAL_MS;
        if (result == null || error != null) {
            problem(host, "Automatic MQTT recovery failed: "
                    + (error == null ? "missing OEM service result" : error));
            return;
        }
        if (result == Result.REENABLED || result == Result.RESTARTED) {
            waitingForPublications = localMqttMode && publicationCount == publicationsAtRequest;
            String action = result == Result.REENABLED
                    ? "re-enabled disabled tablet MQTT" : "started the stopped OEM publisher";
            report(host, "Automatic MQTT recovery " + action + " through the OEM service; "
                    + (!localMqttMode ? "publisher is running with the tablet's configured MQTT connection settings; external broker delivery is not monitored."
                            : waitingForPublications ? "waiting for MQTT publications."
                            : "MQTT publications are being received."), false);
        } else {
            nextCheckMs = now + 60_000L;
            if (lastProblem != null) {
                report(host, "Automatic MQTT recovery checks are working again.", false);
            }
        }
        lastProblem = null;
    }

    synchronized void onPublication(PluginHost host) {
        publicationCount++;
        if (enabled && waitingForPublications) {
            waitingForPublications = false;
            report(host, "Automatic MQTT recovery completed; MQTT publications are being received.", false);
        }
    }

    static Result unchangedState(boolean mqttEnabled, String state, boolean reenableDisabledMqtt) {
        if (!mqttEnabled && !reenableDisabledMqtt) {
            return Result.DISABLED;
        }

        if (!"running".equals(state) && !"stopped".equals(state)) {
            throw new IllegalStateException("Unexpected OEM MQTT service state: " + state);
        }
        return mqttEnabled && "running".equals(state) ? Result.RUNNING : null;
    }

    static boolean canCheck(boolean localMqttMode, boolean brokerListening) {
        return !localMqttMode || brokerListening;
    }

    private void problem(PluginHost host, String message) {
        if (!message.equals(lastProblem)) {
            lastProblem = message;
            report(host, message, true);
        }
    }

    private static void report(PluginHost host, String message, boolean error) {
        try {
            host.log(message);
            host.status(message, error);
        } catch (RuntimeException exception) {
            System.err.println("smt101-sensors: " + message
                    + " (could not report recovery status: " + exception + ")");
        }
    }
}
