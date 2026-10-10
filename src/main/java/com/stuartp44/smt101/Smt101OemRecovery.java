package com.stuartp44.smt101;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class Smt101OemRecovery implements Smt101MqttRecovery.Backend {
    static final String FIRMWARE = "ZX-SMT1019-R157-V1.0A-10.1-GG-K1.29U-20260707";
    private static final String DESCRIPTOR = "com.broadcastinterface.ISystemInterface";
    // Verified against the Binder protocol in the confirmed firmware; never use on other builds.
    private static final int SET_SYSTEM_PROPERTIES = 11;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            runnable -> daemon(runnable, "smt101-oem-watchdog"));
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(
            runnable -> daemon(runnable, "smt101-oem-recovery"));
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean stopped;

    @Override
    public void check(final boolean reenableDisabledMqtt, final Smt101MqttRecovery.Callback callback) {
        if (stopped || !busy.compareAndSet(false, true)) {
            throw new IllegalStateException("OEM recovery worker is stopped or still completing a previous check");
        }
        final AtomicBoolean completed = new AtomicBoolean();
        final java.util.concurrent.ScheduledFuture<?> deadline = watchdog.schedule(() -> {
            if (completed.compareAndSet(false, true)) {
                callback.complete(null, "OEM service check timed out");
            }
        }, 15L, TimeUnit.SECONDS);
        try {
            worker.execute(() -> {
                Smt101MqttRecovery.Result result = null;
                String error = null;
                try {
                    result = recover(completed, reenableDisabledMqtt);
                } catch (Exception exception) {
                    if (exception instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    error = exception.toString();
                } finally {
                    busy.set(false);
                    deadline.cancel(false);
                    if (completed.compareAndSet(false, true)) {
                        callback.complete(result, error);
                    }
                }
            });
        } catch (RuntimeException exception) {
            busy.set(false);
            deadline.cancel(false);
            completed.set(true);
            throw exception;
        }
    }

    private Smt101MqttRecovery.Result recover(AtomicBoolean completed, boolean reenableDisabledMqtt) throws Exception {
        if (!FIRMWARE.equals(property("ro.build.display.id"))) {
            throw new IOException("Unsupported firmware for OEM MQTT recovery");
        }
        boolean enabled = mqttEnabled();
        String state = property("init.svc.iot_mqtt_client");
        Smt101MqttRecovery.Result unchanged =
                Smt101MqttRecovery.unchangedState(enabled, state, reenableDisabledMqtt);
        if (unchanged != null) {
            return unchanged;
        }
        // SDK 1 has no Context accessor. This lookup was verified in a target-SDK-36 normal app.
        Object application = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
        if (!(application instanceof Context)) {
            throw new IOException("Kiosk application context is unavailable");
        }
        Context context = (Context) application;
        final CountDownLatch connected = new CountDownLatch(1);
        final IBinder[] service = new IBinder[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service[0] = binder;
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                connected.countDown();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                connected.countDown();
            }

            @Override
            public void onBindingDied(ComponentName name) {
                connected.countDown();
            }
        };
        Intent intent = new Intent(DESCRIPTOR).setComponent(new ComponentName(
                "com.broadcastinterface", "com.broadcastinterface.ApiSystemService"));
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            throw new IOException("OEM system service rejected the binding");
        }
        try {
            if (!connected.await(3L, TimeUnit.SECONDS) || service[0] == null) {
                throw new IOException("OEM system service did not connect");
            }
            if (!DESCRIPTOR.equals(service[0].getInterfaceDescriptor())) {
                throw new IOException("Unexpected OEM Binder interface");
            }
            // Recheck after binding and only override a disable when explicitly configured.
            enabled = mqttEnabled();
            state = property("init.svc.iot_mqtt_client");
            unchanged = Smt101MqttRecovery.unchangedState(enabled, state, reenableDisabledMqtt);
            if (unchanged != null) {
                return unchanged;
            }
            if (stopped || completed.get()) {
                throw new IOException("OEM recovery cancelled or service state changed: " + state);
            }
            Parcel request = Parcel.obtain();
            Parcel response = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                request.writeString("persist.start.mqtt");
                request.writeString("1");
                if (!service[0].transact(SET_SYSTEM_PROPERTIES, request, response, 0)) {
                    throw new IOException("OEM property setter transaction is unsupported");
                }
                response.readException();
                if (response.readInt() != 1) {
                    throw new IOException("OEM service rejected MQTT start property");
                }
            } finally {
                request.recycle();
                response.recycle();
            }
            Thread.sleep(2_000L);
            if (!mqttEnabled() || !"running".equals(property("init.svc.iot_mqtt_client"))) {
                throw new IOException("OEM publisher did not stay enabled and running after start");
            }
            return enabled ? Smt101MqttRecovery.Result.RESTARTED : Smt101MqttRecovery.Result.REENABLED;
        } finally {
            context.unbindService(connection);
        }
    }

    private static boolean mqttEnabled() throws IOException, InterruptedException {
        String value = property("persist.start.mqtt");
        if (!"0".equals(value) && !"1".equals(value)) {
            throw new IOException("Cannot determine whether tablet MQTT is enabled: " + value);
        }
        return "1".equals(value);
    }

    private static String property(String name) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                throw new IOException("Property read timed out: " + name);
            }
            if (process.exitValue() != 0) {
                throw new IOException("Property read failed: " + name);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[256];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 4096) {
                        throw new IOException("Property output exceeded limit: " + name);
                    }
                    output.write(buffer, 0, count);
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        } finally {
            process.destroy();
        }
    }

    @Override
    public void stop() {
        stopped = true;
        worker.shutdownNow();
        watchdog.shutdownNow();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
