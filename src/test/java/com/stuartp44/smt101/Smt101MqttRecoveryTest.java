package com.stuartp44.smt101;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import me.jxl.kiosk.plugins.PluginHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void externalModeChecksWithoutBrokerWhileLocalModeRequiresListeningBroker() {
        assertTrue(Smt101MqttRecovery.canCheck(false, false));
        assertTrue(Smt101MqttRecovery.canCheck(false, true));
        assertFalse(Smt101MqttRecovery.canCheck(true, false));
        assertTrue(Smt101MqttRecovery.canCheck(true, true));
    }

    @Test
    void externalRecoveryDoesNotClaimLocalReceptionOrWaitForPublications() {
        recovery.configure(true, true, false);
        recovery.check(host, 1_000L);
        assertTrue(backend.reenable);
        backend.complete(Smt101MqttRecovery.Result.REENABLED);
        assertTrue(logs.get(0).contains("external broker delivery is not monitored"));
        assertFalse(logs.get(0).contains("waiting for MQTT publications"));
        recovery.onPublication(host);
        assertEquals(1, logs.size());
        recovery.configure(true, false, false);
        recovery.check(host, 61_000L);
        backend.complete(Smt101MqttRecovery.Result.RESTARTED);
        assertTrue(logs.get(1).contains("publisher is running"));
    }

    @Test
    void replacesWaitingStatusOncePublicationsResume() {
        recovery.configure(true, true);
        recovery.check(host, 1_000L);
        backend.complete(Smt101MqttRecovery.Result.REENABLED);
        assertTrue(logs.get(0).contains("waiting for MQTT publications"));
        recovery.onPublication(host);
        recovery.onPublication(host);
        assertEquals(2, logs.size());
        assertTrue(logs.get(1).contains("MQTT publications are being received"));
    }

    @Test
    void callbackDoesNotOverwritePublicationsWithStaleWaitingStatus() {
        recovery.configure(true);
        recovery.onPublication(host);
        recovery.check(host, 1_000L);
        recovery.onPublication(host);
        backend.complete(Smt101MqttRecovery.Result.RESTARTED);
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("MQTT publications are being received"));
        assertFalse(logs.get(0).contains("waiting"));
    }

    @Test
    void oldPublicationsDoNotClaimRecoveryAndConfigurationClearsWaiting() {
        recovery.configure(true);
        recovery.onPublication(host);
        recovery.check(host, 1_000L);
        backend.complete(Smt101MqttRecovery.Result.RESTARTED);
        assertTrue(logs.get(0).contains("waiting"));
        recovery.configure(false);
        recovery.onPublication(host);
        assertEquals(1, logs.size());
    }

    @Test
    void reenablePolicyRespectsOptInAndRejectsUnexpectedStates() {
        assertEquals(Smt101MqttRecovery.Result.DISABLED,
                Smt101MqttRecovery.unchangedState(false, "stopped", false));
        assertEquals(Smt101MqttRecovery.Result.DISABLED,
                Smt101MqttRecovery.unchangedState(false, "running", false));
        assertEquals(Smt101MqttRecovery.Result.RUNNING,
                Smt101MqttRecovery.unchangedState(true, "running", true));
        assertNull(Smt101MqttRecovery.unchangedState(false, "stopped", true));
        assertNull(Smt101MqttRecovery.unchangedState(false, "running", true));
        assertNull(Smt101MqttRecovery.unchangedState(true, "stopped", false));
        assertThrows(IllegalStateException.class,
                () -> Smt101MqttRecovery.unchangedState(false, "stopping", true));
        assertThrows(IllegalStateException.class,
                () -> Smt101MqttRecovery.unchangedState(true, "", false));
    }

    @Test
    void reenableIsOptInAndUsesRecoveryCooldown() {
        recovery.configure(true);
        recovery.check(host, 1_000L);
        assertFalse(backend.reenable);
        backend.complete(Smt101MqttRecovery.Result.DISABLED);
        recovery.configure(false, true);
        recovery.check(host, 61_000L);
        assertEquals(1, backend.requests);
        recovery.configure(true, true);
        recovery.check(host, 61_000L);
        assertTrue(backend.reenable);
        backend.complete(Smt101MqttRecovery.Result.REENABLED);
        assertTrue(logs.get(0).contains("re-enabled disabled tablet MQTT"));
        recovery.check(host, 360_999L);
        assertEquals(2, backend.requests);
        recovery.check(host, 361_000L);
        assertEquals(3, backend.requests);
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
        boolean reenable;

        @Override
        public void check(boolean reenableDisabledMqtt, Smt101MqttRecovery.Callback callback) {
            if (reject) {
                throw new IllegalStateException("worker unavailable");
            }
            requests++;
            reenable = reenableDisabledMqtt;
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
