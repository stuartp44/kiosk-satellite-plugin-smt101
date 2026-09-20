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
                Smt101MqttBroker.ReadingType.UNKNOWN,
                Smt101MqttBroker.inferReadingType("panel/status"));
        assertEquals(
                Smt101MqttBroker.ReadingType.UNKNOWN,
                Smt101MqttBroker.inferReadingType("panel/sensors/light"));
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
            writeConnectV5(out, "oem-v5");
            assertEquals(0x20, in.readUnsignedByte());
            assertEquals(3, in.readUnsignedByte());
            in.readFully(new byte[3]);

            writePublishV5(out, "panel/temperature", "23.5");
            assertTrue(published.await(2, TimeUnit.SECONDS));
            assertEquals("23.5", payload.get());
        } finally {
            broker.stop();
            thread.join(2000);
        }
    }

    @Test
    void publishesCommandsOnlyToSubscribedLocalClient() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
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

            assertEquals(
                    Smt101MqttBroker.PublishResult.CONNECTED_FALLBACK,
                    broker.publish("office/switch1/set", "{\"state\":\"ON\"}"));
            assertEquals(0x30, readPacket(publisherIn).header);
            assertEquals(0x30, readPacket(in).header);
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
