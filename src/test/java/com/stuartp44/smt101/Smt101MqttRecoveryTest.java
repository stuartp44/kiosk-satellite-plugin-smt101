package com.stuartp44.smt101;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import me.jxl.kiosk.plugins.PluginHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Smt101MqttRecoveryTest {
    private final FakeBackend backend = new FakeBackend();
    private final Smt101MqttRecovery recovery = new Smt101MqttRecovery(backend);
    private final List<String> logs = new ArrayList<String>();
    private final PluginHost host = (PluginHost) Proxy.newProxyInstance(
            PluginHost.class.getClassLoader(), new Class<?>[] {PluginHost.class},
            (proxy, method, arguments) -> {
                if ("log".equals(method.getName())) {
                    logs.add((String) arguments[0]);
                }
                if ("shizukuState".equals(method.getName()) || "executeShizuku".equals(method.getName())) {
                    throw new AssertionError("Recovery must not depend on Shizuku");
                }
                return null;
            });

    @Test
    void disabledByDefault() {
        recovery.check(host, 1_000L);
        assertEquals(0, backend.requests);
    }

    @Test
    void healthyAndDisabledServicesAreCheckedOncePerMinute() {
        recovery.configure(true);
        recovery.check(host, 1_000L);
        backend.complete(Smt101MqttRecovery.Result.RUNNING);
        recovery.check(host, 60_999L);
        assertEquals(1, backend.requests);
        recovery.check(host, 61_000L);
        backend.complete(Smt101MqttRecovery.Result.DISABLED);
        recovery.check(host, 121_000L);
        assertEquals(3, backend.requests);
        assertTrue(logs.isEmpty());
    }

    @Test
    void onlyOneRequestIsPendingAndRestartsHaveFiveMinuteCooldown() {
        recovery.configure(true);
        recovery.check(host, 1_000L);
        recovery.check(host, 61_000L);
        assertEquals(1, backend.requests);
        backend.complete(Smt101MqttRecovery.Result.RESTARTED);
        assertTrue(logs.get(0).contains("through the OEM service"));
        recovery.check(host, 1_000L + Smt101MqttRecovery.RETRY_INTERVAL_MS - 1L);
        assertEquals(1, backend.requests);
        recovery.check(host, 1_000L + Smt101MqttRecovery.RETRY_INTERVAL_MS);
        assertEquals(2, backend.requests);
    }

    @Test
    void reportsFailuresAndRecoveryWithoutRepeatingIdenticalErrors() {
        recovery.configure(true);
        recovery.check(host, 1_000L);
        backend.callback.complete(null, "OEM service rejected binding");
        recovery.check(host, 61_000L);
        assertEquals(1, backend.requests);
        recovery.check(host, 301_000L);
        backend.callback.complete(null, "OEM service rejected binding");
        assertEquals(1, logs.size());
        recovery.check(host, 601_000L);
        backend.complete(Smt101MqttRecovery.Result.RUNNING);
        assertEquals(2, logs.size());
        assertTrue(logs.get(1).contains("working again"));
    }

    @Test
    void rejectsMissingResultsAndErrorWithSuccessResult() {
        recovery.configure(true);
        recovery.check(host, 1_000L);
        backend.callback.complete(null, null);
        assertTrue(logs.get(0).contains("missing OEM service result"));
        recovery.check(host, 301_000L);
        backend.callback.complete(Smt101MqttRecovery.Result.RESTARTED, "transaction failed");
        assertTrue(logs.get(1).contains("transaction failed"));
    }

    @Test
    void reportsSynchronousErrorsAndRateLimitsRetries() {
        recovery.configure(true);
        backend.reject = true;
        recovery.check(host, 1_000L);
        assertTrue(logs.get(0).contains("worker unavailable"));
        backend.reject = false;
        recovery.check(host, 61_000L);
        assertEquals(0, backend.requests);
        recovery.check(host, 301_000L);
        assertEquals(1, backend.requests);
    }

    @Test
    void discardsCallbacksFromAnOldConfigurationWithoutOverlappingCommands() {
        recovery.configure(true);
        recovery.check(host, 1_000L);
        recovery.configure(false);
        recovery.configure(true);
        recovery.check(host, 61_000L);
        assertEquals(1, backend.requests);
        backend.complete(Smt101MqttRecovery.Result.RESTARTED);
        assertTrue(logs.isEmpty());
        recovery.check(host, 121_000L);
        assertEquals(2, backend.requests);
        recovery.stop();
        assertTrue(backend.stopped);
        backend.complete(Smt101MqttRecovery.Result.RESTARTED);
        recovery.check(host, 421_000L);
        assertEquals(2, backend.requests);
        assertTrue(logs.isEmpty());
    }

    private static final class FakeBackend implements Smt101MqttRecovery.Backend {
        Smt101MqttRecovery.Callback callback;
        int requests;
        boolean reject;
        boolean stopped;

        @Override
        public void check(Smt101MqttRecovery.Callback callback) {
            if (reject) {
                throw new IllegalStateException("worker unavailable");
            }
            requests++;
            this.callback = callback;
        }

        void complete(Smt101MqttRecovery.Result result) {
            callback.complete(result, null);
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }
}
