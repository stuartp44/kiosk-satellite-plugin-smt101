package com.stuartp44.smt101;

import me.jxl.kiosk.plugins.PluginHost;

final class Smt101MqttRecovery {
    static final long RETRY_INTERVAL_MS = 5L * 60_000L;

    enum Result {
        RUNNING, DISABLED, RESTARTED
    }

    interface Callback {
        void complete(Result result, String error);
    }

    interface Backend {
        void check(Callback callback);
        void stop();
    }

    private Backend backend;
    private boolean enabled;
    private boolean pending;
    private long generation;
    private long nextCheckMs;
    private String lastProblem;

    Smt101MqttRecovery() {
    }

    Smt101MqttRecovery(Backend backend) {
        this.backend = backend;
    }

    synchronized void configure(boolean enabled) {
        this.enabled = enabled;
        generation++;
        nextCheckMs = 0L;
        lastProblem = null;
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
        try {
            if (backend == null) {
                backend = new Smt101OemRecovery();
            }
            pending = true;
            backend.check(new Callback() {
                @Override
                public void complete(Result result, String error) {
                    Smt101MqttRecovery.this.complete(host, now, requestGeneration, result, error);
                }
            });
        } catch (RuntimeException exception) {
            pending = false;
            nextCheckMs = now + RETRY_INTERVAL_MS;
            problem(host, "Automatic MQTT recovery failed: " + exception);
        }
    }

    private synchronized void complete(PluginHost host, long now, long requestGeneration,
            Result result, String error) {
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
        if (result == Result.RESTARTED) {
            report(host, "Automatic MQTT recovery started the stopped OEM publisher through the OEM service; waiting for MQTT publications.", false);
        } else {
            nextCheckMs = now + 60_000L;
            if (lastProblem != null) {
                report(host, "Automatic MQTT recovery checks are working again.", false);
            }
        }
        lastProblem = null;
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
