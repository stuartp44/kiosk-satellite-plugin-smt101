package com.stuartp44.smt101;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Smt101MqttBrokerTest {
    @Test
    void infersCommonSensorTopics() {
        assertEquals(
                Smt101MqttBroker.ReadingType.TEMPERATURE,
                Smt101MqttBroker.inferReadingType("panel/sensor/temperature"));
        assertEquals(
                Smt101MqttBroker.ReadingType.HUMIDITY,
                Smt101MqttBroker.inferReadingType("panel/humidity"));
        assertEquals(
                Smt101MqttBroker.ReadingType.DISTANCE,
                Smt101MqttBroker.inferReadingType("rk3576_u/sensors/distance"));
        assertEquals(
                Smt101MqttBroker.ReadingType.UNKNOWN,
                Smt101MqttBroker.inferReadingType("panel/sensors/distanceometer"));
        assertEquals(
                Smt101MqttBroker.ReadingType.UNKNOWN,
                Smt101MqttBroker.inferReadingType("panel/status"));
        assertEquals(
                Smt101MqttBroker.ReadingType.UNKNOWN,
                Smt101MqttBroker.inferReadingType("panel/sensors/light"));
    }

    @Test
    void extractsDistanceReadings() {
        assertEquals(802.0d, Smt101MqttBroker.parseInferredReading(
                "{\"distance\":802}", Smt101MqttBroker.ReadingType.DISTANCE));
        assertEquals(2152.0d, Smt101MqttBroker.parseInferredReading(
                "{\"distance\":\"2152\"}", Smt101MqttBroker.ReadingType.DISTANCE));
        assertEquals(0.0d, Smt101MqttBroker.parseInferredReading(
                "0", Smt101MqttBroker.ReadingType.DISTANCE));
        assertEquals(802.5d, Smt101MqttBroker.parseInferredReading(
                "802.5", Smt101MqttBroker.ReadingType.DISTANCE));
        assertNull(Smt101MqttBroker.parseInferredReading(
                "{\"temperature\":30,\"distance_text\":\"unknown\"}",
                Smt101MqttBroker.ReadingType.DISTANCE));
    }

    @Test
    void extractsExpectedJsonReadingWithoutUsingUnrelatedNumbers() {
        assertEquals(
                26.4d,
                Smt101MqttBroker.parseInferredReading(
                        "{\"temperature\":26.4}", Smt101MqttBroker.ReadingType.TEMPERATURE),
                0.0001d);
        assertEquals(
                38.5d,
                Smt101MqttBroker.parseInferredReading(
                        "{\"humidity\":\"38.5\"}", Smt101MqttBroker.ReadingType.HUMIDITY),
                0.0001d);
        assertNull(Smt101MqttBroker.parseInferredReading(
                "{\"battery\":99,\"temperature_text\":\"unknown\"}",
                Smt101MqttBroker.ReadingType.TEMPERATURE));
        assertNull(Smt101MqttBroker.parseInferredReading(
                "{\"temperature\":26.4}", Smt101MqttBroker.ReadingType.HUMIDITY));
    }

    @Test
    void acceptsConnectAndCapturesQosOnePublish() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        AtomicInteger port = new AtomicInteger();
        AtomicReference<String> clientId = new AtomicReference<String>();
        AtomicInteger protocolLevel = new AtomicInteger();
        AtomicReference<String> topic = new AtomicReference<String>();
        AtomicReference<String> payload = new AtomicReference<String>();
        Smt101MqttBroker broker = new Smt101MqttBroker(0, new Smt101MqttBroker.Listener() {
            @Override
            public void onStarted(int actualPort) {
                port.set(actualPort);
                started.countDown();
            }

            @Override
            public void onClientConnected(String connectedClientId, int connectedProtocolLevel, boolean hasUsername) {
                clientId.set(connectedClientId);
                protocolLevel.set(connectedProtocolLevel);
            }

            @Override
            public void onClientSubscribed(java.util.List<String> topicFilters) {
            }

            @Override
            public void onMessage(String publishedTopic, String publishedPayload) {
                topic.set(publishedTopic);
                payload.set(publishedPayload);
                published.countDown();
            }

            @Override
            public void onError(String message) {
            }
        });
        Thread thread = new Thread(() -> {
            try {
                broker.run();
            } catch (Exception ignored) {
            }
        });
        thread.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(broker.getBoundAddress().isLoopbackAddress());

        try (Socket socket = new Socket("127.0.0.1", port.get())) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            writeConnect(out, "oem-client");
            assertEquals(0x20, in.readUnsignedByte());
            assertEquals(2, in.readUnsignedByte());
            assertEquals(0, in.readUnsignedByte());
            assertEquals(0, in.readUnsignedByte());

            writePublish(out, "panel/temperature", "23.5", 7);
            assertEquals(0x40, in.readUnsignedByte());
            assertEquals(2, in.readUnsignedByte());
            assertEquals(0, in.readUnsignedByte());
            assertEquals(7, in.readUnsignedByte());
            assertTrue(published.await(2, TimeUnit.SECONDS));
            assertEquals("oem-client", clientId.get());
            assertEquals(4, protocolLevel.get());
            assertEquals("panel/temperature", topic.get());
            assertEquals("23.5", payload.get());
        } finally {
            broker.stop();
            thread.join(2000);
        }
    }

    @Test
    void skipsMqttFiveConnectAndPublishProperties() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        AtomicInteger port = new AtomicInteger();
        AtomicReference<String> payload = new AtomicReference<String>();
        Smt101MqttBroker broker = new Smt101MqttBroker(0, new Smt101MqttBroker.Listener() {
            @Override
            public void onStarted(int actualPort) {
                port.set(actualPort);
                started.countDown();
            }

            @Override
            public void onClientConnected(String clientId, int protocolLevel, boolean hasUsername) {
            }

            @Override
            public void onClientSubscribed(java.util.List<String> topicFilters) {
            }

            @Override
            public void onMessage(String topic, String publishedPayload) {
                payload.set(publishedPayload);
                published.countDown();
            }

            @Override
            public void onError(String message) {
            }
        });
        Thread thread = new Thread(() -> {
            try {
                broker.run();
            } catch (Exception ignored) {
            }
        });
        thread.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));

        try (Socket socket = new Socket("127.0.0.1", port.get())) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            socket.setSoTimeout(2000);
            writeConnectV5(out, "oem-v5");
            assertEquals(0x20, in.readUnsignedByte());
            assertEquals(3, in.readUnsignedByte());
            in.readFully(new byte[3]);

            writePublishV5(out, "panel/temperature", "23.5");
            assertTrue(published.await(2, TimeUnit.SECONDS));
            assertEquals("23.5", payload.get());
            writePublishV5(out, "panel/light/status", "{\"state\":\"OFF\"}");
            assertNoQueuedCommand(out, in);
            assertEquals(Smt101MqttBroker.PublishResult.STATUS_OWNER,
                    broker.publish("panel/light/switch", "ON"));
            Packet command = readPacket(in);
            assertEquals(0x30, command.header);
            assertEquals("panel/light/switch", new DataInputStream(
                    new java.io.ByteArrayInputStream(command.body)).readUTF());
            int topicLength = "panel/light/switch".length();
            assertEquals(0, command.body[2 + topicLength]);
            assertEquals("ON", new String(command.body, 3 + topicLength,
                    command.body.length - 3 - topicLength, StandardCharsets.UTF_8));
        } finally {
            broker.stop();
            thread.join(2000);
        }
    }

    @Test
    void publishesCommandsOnlyToSubscribedLocalClient() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(2);
        CountDownLatch subscribed = new CountDownLatch(1);
        AtomicInteger port = new AtomicInteger();
        AtomicReference<String> subscribedTopic = new AtomicReference<String>();
        Smt101MqttBroker broker = new Smt101MqttBroker(0, new Smt101MqttBroker.Listener() {
            @Override
            public void onStarted(int actualPort) {
                port.set(actualPort);
                started.countDown();
            }

            @Override
            public void onClientConnected(String clientId, int protocolLevel, boolean hasUsername) {
                connected.countDown();
            }

            @Override
            public void onClientSubscribed(java.util.List<String> topicFilters) {
                subscribedTopic.set(topicFilters.get(0));
                subscribed.countDown();
            }

            @Override
            public void onMessage(String topic, String payload) {
            }

            @Override
            public void onError(String message) {
            }
        });
        Thread thread = new Thread(() -> {
            try {
                broker.run();
            } catch (Exception ignored) {
            }
        });
        thread.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));

        try (Socket publisherSocket = new Socket("127.0.0.1", port.get());
                Socket subscriberSocket = new Socket("127.0.0.1", port.get())) {
            publisherSocket.setSoTimeout(2000);
            DataOutputStream publisherOut = new DataOutputStream(publisherSocket.getOutputStream());
            DataInputStream publisherIn = new DataInputStream(publisherSocket.getInputStream());
            writeConnect(publisherOut, "oem-sensor-publisher");
            readPacket(publisherIn);

            subscriberSocket.setSoTimeout(2000);
            DataOutputStream out = new DataOutputStream(subscriberSocket.getOutputStream());
            DataInputStream in = new DataInputStream(subscriberSocket.getInputStream());
            writeConnect(out, "oem-command-subscriber");
            readPacket(in);
            assertTrue(connected.await(2, TimeUnit.SECONDS));

            assertEquals(
                    Smt101MqttBroker.PublishResult.NO_CLIENT,
                    broker.publish("office/switch1/set", "{\"state\":\"ON\"}"));
            assertNoQueuedCommand(publisherOut, publisherIn);
            assertNoQueuedCommand(out, in);
            writePublish(out, "kitchen/switch2/status", "{\"state\":\"OFF\"}", 8);
            assertEquals(0x40, readPacket(in).header);
            assertEquals(Smt101MqttBroker.PublishResult.STATUS_OWNER,
                    broker.publish("kitchen/switch2/set", "ON"));
            assertEquals(0x30, readPacket(in).header);
            assertNoQueuedCommand(publisherOut, publisherIn);

            writePublish(publisherOut, "office/switch1/status", "{\"state\":\"unknown\"}", 1);
            assertEquals(0x40, readPacket(publisherIn).header);
            assertEquals(Smt101MqttBroker.PublishResult.NO_CLIENT,
                    broker.publish("office/switch1/set", "ON"));

            String[] statusTopics = {"switch1", "switch2", "light", "brightness", "rgb"};
            String[] statusPayloads = {
                    "{\"state\":\"OFF\"}", "{\"state\":\"OFF\"}", "{\"state\":\"OFF\"}",
                    "{\"brightness\":20}", "{\"rgb\":[255,255,255]}"};
            String[] commandTopics = {"switch1/set", "switch2/set", "light/switch", "brightness/set", "rgb/set"};
            for (int i = 0; i < statusTopics.length; i++) {
                writePublish(publisherOut, "office/" + statusTopics[i] + "/status", statusPayloads[i], 2 + i);
                assertEquals(0x40, readPacket(publisherIn).header);
                assertEquals(Smt101MqttBroker.PublishResult.STATUS_OWNER,
                        broker.publish("office/" + commandTopics[i], "test-command"));
                assertEquals(0x30, readPacket(publisherIn).header);
                assertNoQueuedCommand(out, in);
            }
            assertEquals(Smt101MqttBroker.PublishResult.NO_CLIENT,
                    broker.publish("office/nested/switch1/set", "ON"));
            assertEquals(Smt101MqttBroker.PublishResult.NO_CLIENT,
                    broker.publish("Office/switch1/set", "ON"));
            assertNoQueuedCommand(publisherOut, publisherIn);
            assertNoQueuedCommand(out, in);
            writeSubscribe(out, "office/+/set", 9);
            Packet subAck = readPacket(in);
            assertEquals(0x90, subAck.header);
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));
            assertEquals("office/+/set", subscribedTopic.get());

            assertEquals(
                    Smt101MqttBroker.PublishResult.SUBSCRIBED,
                    broker.publish("office/switch1/set", "{\"state\":\"ON\"}"));
            Packet publish = readPacket(in);
            assertEquals(0x30, publish.header);
            int topicLength = ((publish.body[0] & 0xFF) << 8) | (publish.body[1] & 0xFF);
            assertEquals(
                    "office/switch1/set",
                    new String(publish.body, 2, topicLength, StandardCharsets.UTF_8));
            assertEquals(
                    "{\"state\":\"ON\"}",
                    new String(
                            publish.body,
                            2 + topicLength,
                            publish.body.length - 2 - topicLength,
                            StandardCharsets.UTF_8));
            assertNoQueuedCommand(publisherOut, publisherIn);
        } finally {
            broker.stop();
            thread.join(2000);
        }
    }

    @Test
    void forgetsStatusOwnershipAfterDisconnectAndStop() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger port = new AtomicInteger();
        java.util.concurrent.BlockingQueue<String> disconnected =
                new java.util.concurrent.LinkedBlockingQueue<String>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Smt101MqttBroker broker = new Smt101MqttBroker(0, new Smt101MqttBroker.Listener() {
            @Override
            public void onStarted(int actualPort) {
                port.set(actualPort);
                started.countDown();
            }

            @Override
            public void onClientConnected(String clientId, int protocolLevel, boolean hasUsername) {
            }

            @Override
            public void onClientSubscribed(java.util.List<String> topicFilters) {
            }

            @Override
            public void onMessage(String topic, String payload) {
            }

            @Override
            public void onError(String message) {
                failure.set(new AssertionError(message));
            }

            @Override
            public void onClientDisconnected(String clientId, String reason, long connectedMillis) {
                disconnected.add(clientId);
            }
        });
        Thread thread = new Thread(() -> {
            try {
                broker.run();
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        thread.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int session = 0; session < 20; session++) {
                try (Socket client = new Socket("127.0.0.1", port.get())) {
                    client.setSoTimeout(2000);
                    DataOutputStream out = new DataOutputStream(client.getOutputStream());
                    DataInputStream in = new DataInputStream(client.getInputStream());
                    writeConnect(out, "reconnecting-oem");
                    assertEquals(0x20, readPacket(in).header);
                    assertNoQueuedCommand(out, in);
                    assertEquals(Smt101MqttBroker.PublishResult.NO_CLIENT,
                            broker.publish("office/switch1/set", "ON"));
                    for (int update = 0; update < 10; update++) {
                        String prefix = update == 9 ? "office" : "old-" + update;
                        writePublish(out, prefix + "/switch1/status", "{\"state\":\"OFF\"}", update + 1);
                        assertEquals(0x40, readPacket(in).header);
                    }
                    assertEquals(Smt101MqttBroker.PublishResult.NO_CLIENT,
                            broker.publish("old-0/switch1/set", "ON"));
                    assertEquals(Smt101MqttBroker.PublishResult.STATUS_OWNER,
                            broker.publish("office/switch1/set", "ON"));
                    assertEquals(0x30, readPacket(in).header);
                    if (session == 19) {
                        broker.stop();
                    } else if (session % 2 == 0) {
                        client.shutdownOutput();
                    } else {
                        writePacket(out, 0xE0, new byte[0]);
                    }
                    assertEquals("reconnecting-oem", disconnected.poll(2, TimeUnit.SECONDS));
                    assertEquals(0, broker.connectedClientCount());
                    assertEquals(Smt101MqttBroker.PublishResult.NO_CLIENT,
                            broker.publish("office/switch1/set", "ON"));
                }
            }
        } finally {
            broker.stop();
            thread.join(2000);
        }
        assertTrue(!thread.isAlive());
        assertNull(failure.get());
    }

    @Test
    void reportsDisconnectReasonForCleanAndAbruptDisconnects() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        java.util.concurrent.BlockingQueue<String> disconnects =
                new java.util.concurrent.LinkedBlockingQueue<String>();
        AtomicInteger port = new AtomicInteger();
        Smt101MqttBroker broker = new Smt101MqttBroker(0, new Smt101MqttBroker.Listener() {
            @Override
            public void onStarted(int actualPort) {
                port.set(actualPort);
                started.countDown();
            }

            @Override
            public void onClientConnected(String clientId, int protocolLevel, boolean hasUsername) {
            }

            @Override
            public void onClientSubscribed(java.util.List<String> topicFilters) {
            }

            @Override
            public void onClientDisconnected(String clientId, String reason, long connectedMillis) {
                disconnects.add(clientId + ":" + reason);
            }

            @Override
            public void onMessage(String topic, String payload) {
            }

            @Override
            public void onError(String message) {
            }
        });
        Thread thread = new Thread(() -> {
            try {
                broker.run();
            } catch (Exception ignored) {
            }
        });
        thread.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        try {
            try (Socket socket = new Socket("127.0.0.1", port.get())) {
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                writeConnect(out, "clean-client");
                readPacket(new DataInputStream(socket.getInputStream()));
                long deadline = System.currentTimeMillis() + 2000L;
                while (broker.connectedClientCount() != 1 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10L);
                }
                assertEquals(1, broker.connectedClientCount());
                writePacket(out, 0xE0, new byte[0]);
                assertEquals("clean-client:DISCONNECT received", disconnects.poll(2, TimeUnit.SECONDS));
            }
            try (Socket socket = new Socket("127.0.0.1", port.get())) {
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                writeConnect(out, "abrupt-client");
                readPacket(new DataInputStream(socket.getInputStream()));
            }
            assertEquals("abrupt-client:connection closed by client", disconnects.poll(2, TimeUnit.SECONDS));
            assertEquals(0, broker.connectedClientCount());
        } finally {
            broker.stop();
            thread.join(2000);
        }
    }

    @Test
    void canRunAgainAfterPortWasBusy() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        java.net.ServerSocket occupant = new java.net.ServerSocket();
        occupant.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0));
        int busyPort = occupant.getLocalPort();
        Smt101MqttBroker broker = new Smt101MqttBroker(busyPort, new Smt101MqttBroker.Listener() {
            @Override
            public void onStarted(int actualPort) {
                started.countDown();
            }

            @Override
            public void onClientConnected(String clientId, int protocolLevel, boolean hasUsername) {
            }

            @Override
            public void onClientSubscribed(java.util.List<String> topicFilters) {
            }

            @Override
            public void onMessage(String topic, String payload) {
            }

            @Override
            public void onError(String message) {
            }
        });
        try {
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, broker::run);
        } finally {
            occupant.close();
        }
        Thread thread = new Thread(() -> {
            try {
                broker.run();
            } catch (Exception ignored) {
            }
        });
        thread.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
        } finally {
            broker.stop();
            thread.join(2000);
        }
    }

    private static void writeConnect(DataOutputStream out, String clientId) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, "MQTT");
        body.write(4);
        body.write(2);
        body.write(0);
        body.write(30);
        writeUtf8(body, clientId);
        writePacket(out, 0x10, body.toByteArray());
    }

    private static void writePublish(DataOutputStream out, String topic, String payload, int packetId)
            throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, topic);
        body.write((packetId >> 8) & 0xFF);
        body.write(packetId & 0xFF);
        body.write(payload.getBytes(StandardCharsets.UTF_8));
        writePacket(out, 0x32, body.toByteArray());
    }

    private static void writeSubscribe(DataOutputStream out, String topic, int packetId) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write((packetId >> 8) & 0xFF);
        body.write(packetId & 0xFF);
        writeUtf8(body, topic);
        body.write(0);
        writePacket(out, 0x82, body.toByteArray());
    }

    private static Packet readPacket(DataInputStream in) throws Exception {
        int header = in.readUnsignedByte();
        int multiplier = 1;
        int remaining = 0;
        int digit;
        do {
            digit = in.readUnsignedByte();
            remaining += (digit & 0x7F) * multiplier;
            multiplier *= 128;
        } while ((digit & 0x80) != 0);
        byte[] body = new byte[remaining];
        in.readFully(body);
        return new Packet(header, body);
    }

    private static void assertNoQueuedCommand(DataOutputStream out, DataInputStream in) throws Exception {
        writePacket(out, 0xC0, new byte[0]);
        assertEquals(0xD0, readPacket(in).header);
    }

    private static void writeConnectV5(DataOutputStream out, String clientId) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, "MQTT");
        body.write(5);
        body.write(2);
        body.write(0);
        body.write(30);
        body.write(5);
        body.write(0x11);
        body.write(new byte[] {0, 0, 0, 0});
        writeUtf8(body, clientId);
        writePacket(out, 0x10, body.toByteArray());
    }

    private static void writePublishV5(DataOutputStream out, String topic, String payload) throws Exception {
        ByteArrayOutputStream properties = new ByteArrayOutputStream();
        properties.write(0x26);
        writeUtf8(properties, "x1");
        writeUtf8(properties, "ignore2");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, topic);
        body.write(properties.size());
        body.write(properties.toByteArray());
        body.write(payload.getBytes(StandardCharsets.UTF_8));
        writePacket(out, 0x30, body.toByteArray());
    }

    private static void writePacket(DataOutputStream out, int header, byte[] body) throws Exception {
        out.write(header);
        out.write(body.length);
        out.write(body);
        out.flush();
    }

    private static void writeUtf8(ByteArrayOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write((bytes.length >> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.write(bytes);
    }

    private static final class Packet {
        final int header;
        final byte[] body;

        Packet(int header, byte[] body) {
            this.header = header;
            this.body = body;
        }
    }
}
