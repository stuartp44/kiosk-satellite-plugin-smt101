package com.stuartp44.smt101;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal MQTT capture broker for receiving publications from the device's OEM sensor process.
 * It intentionally supports only the protocol surface needed for capture: CONNECT, PUBLISH
 * (QoS 0/1), PINGREQ and DISCONNECT.
 */
final class Smt101MqttBroker {
    private static final int MAX_PACKET_BYTES = 1024 * 1024;
    private static final int MAX_LOG_PAYLOAD_CHARS = 256;
    private static final Pattern TEMPERATURE_JSON_VALUE = Pattern.compile(
            "\"(?:temperature|temp|ths)\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+)?)\"?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HUMIDITY_JSON_VALUE = Pattern.compile(
            "\"(?:humidity|humid|hum|moisture)\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+)?)\"?",
            Pattern.CASE_INSENSITIVE);
    enum ReadingType {
        TEMPERATURE,
        HUMIDITY,
        UNKNOWN
    }

    enum PublishResult {
        SUBSCRIBED,
        CONNECTED_FALLBACK,
        NO_CLIENT
    }

    interface Listener {
        void onStarted(int port);

        void onClientConnected(String clientId, int protocolLevel, boolean hasUsername);

        void onClientSubscribed(List<String> topicFilters);

        void onMessage(String topic, String payload);

        void onError(String message);
    }

    private final int port;
    private final Listener listener;
    private final List<ClientConnection> clients =
            Collections.synchronizedList(new ArrayList<ClientConnection>());
    private volatile boolean stopped;
    private volatile ServerSocket serverSocket;

    Smt101MqttBroker(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    void run() throws IOException {
        if (stopped) {
            return;
        }
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        serverSocket = server;
        notifyStarted(server.getLocalPort());
        try {
            while (!stopped) {
                Socket client;
                try {
                    client = server.accept();
                } catch (SocketException exception) {
                    if (stopped) {
                        return;
                    }
                    throw exception;
                }
                final Socket acceptedClient = client;
                Thread clientThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            acceptedClient.setSoTimeout(120_000);
                            handleClient(acceptedClient);
                        } catch (IOException exception) {
                            if (!stopped) {
                                notifyError("client disconnected: " + summarize(exception));
                            }
                        } finally {
                            closeQuietly(acceptedClient);
                        }
                    }
                }, "smt101-mqtt-client");
                clientThread.setDaemon(true);
                clientThread.start();
            }
        } finally {
            closeQuietly(server);
            if (serverSocket == server) {
                serverSocket = null;
            }
        }
    }

    void stop() {
        stopped = true;
        synchronized (clients) {
            for (ClientConnection client : clients) {
                closeQuietly(client.socket);
            }
            clients.clear();
        }
        closeQuietly(serverSocket);
        serverSocket = null;
    }

    InetAddress getBoundAddress() {
        ServerSocket server = serverSocket;
        return server == null ? null : server.getInetAddress();
    }

    private void handleClient(Socket client) throws IOException {
        DataInputStream in = new DataInputStream(client.getInputStream());
        DataOutputStream out = new DataOutputStream(client.getOutputStream());
        Packet connect = readPacket(in);
        if (connect == null || packetType(connect.header) != 1) {
            throw new IOException("first packet was not CONNECT");
        }
        ConnectInfo info = parseConnect(connect.body);
        synchronized (out) {
            writeConnAck(out, info.protocolLevel);
        }
        ClientConnection connection = new ClientConnection(client, out, info.protocolLevel);
        clients.add(connection);
        if (listener != null) {
            listener.onClientConnected(info.clientId, info.protocolLevel, info.hasUsername);
        }

        try {
            while (!stopped) {
                Packet packet = readPacket(in);
                if (packet == null) {
                    return;
                }
                switch (packetType(packet.header)) {
                    case 3:
                        handlePublish(out, packet, info.protocolLevel);
                        break;
                    case 8:
                        List<String> subscriptions = parseSubscriptions(packet, info.protocolLevel);
                        connection.subscriptions =
                                mergeSubscriptions(connection.subscriptions, subscriptions);
                        if (listener != null) {
                            listener.onClientSubscribed(subscriptions);
                        }
                        synchronized (out) {
                            writeSubAck(out, packet, info.protocolLevel, subscriptions.size());
                        }
                        break;
                    case 6:
                        synchronized (out) {
                            writePacket(out, 0x70, publishAckBody(packet, info.protocolLevel));
                        }
                        break;
                    case 12:
                        synchronized (out) {
                            writePacket(out, 0xD0, new byte[0]);
                        }
                        break;
                    case 14:
                        return;
                    default:
                        break;
                }
            }
        } finally {
            clients.remove(connection);
        }
    }

    PublishResult publish(String topic, String payload) {
        if (topic == null || payload == null) {
            return PublishResult.NO_CLIENT;
        }
        ClientConnection[] snapshot;
        synchronized (clients) {
            snapshot = clients.toArray(new ClientConnection[clients.size()]);
        }
        boolean hasMatchingSubscription = false;
        for (ClientConnection client : snapshot) {
            if (matchesAnySubscription(topic, client.subscriptions)) {
                hasMatchingSubscription = true;
                break;
            }
        }
        boolean delivered = false;
        for (ClientConnection client : snapshot) {
            if (!hasMatchingSubscription
                    || matchesAnySubscription(topic, client.subscriptions)) {
                delivered |= publishToClient(client, topic, payload);
            }
        }
        if (!delivered) {
            return PublishResult.NO_CLIENT;
        }
        return hasMatchingSubscription
                ? PublishResult.SUBSCRIBED
                : PublishResult.CONNECTED_FALLBACK;
    }

    private boolean publishToClient(ClientConnection client, String topic, String payload) {
        byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        int propertiesLength = client.protocolLevel == 5 ? 1 : 0;
        byte[] body = new byte[2 + topicBytes.length + propertiesLength + payloadBytes.length];
        body[0] = (byte) ((topicBytes.length >> 8) & 0xFF);
        body[1] = (byte) (topicBytes.length & 0xFF);
        System.arraycopy(topicBytes, 0, body, 2, topicBytes.length);
        int cursor = 2 + topicBytes.length;
        if (client.protocolLevel == 5) {
            body[cursor++] = 0;
        }
        System.arraycopy(payloadBytes, 0, body, cursor, payloadBytes.length);
        try {
            synchronized (client.output) {
                writePacket(client.output, 0x30, body);
            }
            return true;
        } catch (IOException exception) {
            notifyError("failed to publish command: " + summarize(exception));
            return false;
        }
    }

    private void handlePublish(DataOutputStream out, Packet packet, int protocolLevel) throws IOException {
        byte[] body = packet.body;
        if (body.length < 2) {
            throw new IOException("malformed PUBLISH packet");
        }
        int topicLength = unsignedShort(body, 0);
        if (topicLength <= 0 || topicLength > body.length - 2) {
            throw new IOException("malformed PUBLISH topic");
        }
        String topic = new String(body, 2, topicLength, StandardCharsets.UTF_8);
        int cursor = 2 + topicLength;
        int qos = (packet.header >> 1) & 0x03;
        int packetId = 0;
        if (qos > 0) {
            if (cursor + 2 > body.length) {
                throw new IOException("malformed PUBLISH packet identifier");
            }
            packetId = unsignedShort(body, cursor);
            cursor += 2;
        }
        if (protocolLevel == 5) {
            VariableByteInteger propertyLength = readVariableByteInteger(body, cursor);
            cursor = propertyLength.nextCursor + propertyLength.value;
            if (cursor > body.length) {
                throw new IOException("malformed MQTT 5 PUBLISH properties");
            }
        }
        String payload = new String(body, cursor, body.length - cursor, StandardCharsets.UTF_8);
        if (qos == 1) {
            synchronized (out) {
                writePacket(out, 0x40, shortBytes(packetId));
            }
        } else if (qos == 2) {
            synchronized (out) {
                writePacket(out, 0x50, protocolLevel == 5
                        ? new byte[] {(byte) (packetId >> 8), (byte) packetId, 0x00, 0x00}
                        : shortBytes(packetId));
            }
        }
        if (listener != null) {
            listener.onMessage(topic, payload);
        }
    }

    static ReadingType inferReadingType(String topic) {
        String normalized = topic == null ? "" : topic.toLowerCase(Locale.ROOT);
        if (normalized.contains("temperature")
                || hasTopicToken(normalized, "temp")
                || hasTopicToken(normalized, "ths")) {
            return ReadingType.TEMPERATURE;
        }
        if (normalized.contains("humidity") || normalized.contains("humid")
                || normalized.contains("moisture") || hasTopicToken(normalized, "hum")) {
            return ReadingType.HUMIDITY;
        }
        return ReadingType.UNKNOWN;
    }

    private static boolean hasTopicToken(String topic, String token) {
        return topic.matches(".*(?:^|[/_.-])" + token + "(?:$|[/_.-]).*");
    }

    static String payloadPreview(String payload) {
        if (payload == null) {
            return "";
        }
        String sanitized = payload.replace('\r', ' ').replace('\n', ' ').trim();
        if (sanitized.length() <= MAX_LOG_PAYLOAD_CHARS) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_LOG_PAYLOAD_CHARS) + "...";
    }

    static Double parseInferredReading(String payload, ReadingType type) {
        if (payload == null || type == null || type == ReadingType.UNKNOWN) {
            return null;
        }
        String trimmed = payload.trim();
        try {
            return Double.valueOf(Double.parseDouble(trimmed));
        } catch (NumberFormatException ignored) {
            // The OEM publishes compact JSON, so only accept a number paired with the expected key.
        }
        Pattern pattern;
        if (type == ReadingType.TEMPERATURE) {
            pattern = TEMPERATURE_JSON_VALUE;
        } else if (type == ReadingType.HUMIDITY) {
            pattern = HUMIDITY_JSON_VALUE;
        } else {
            return null;
        }
        Matcher matcher = pattern.matcher(trimmed);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.valueOf(Double.parseDouble(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static ConnectInfo parseConnect(byte[] body) throws IOException {
        int cursor = 0;
        String protocolName = readUtf8(body, cursor);
        cursor += 2 + utf8Length(body, cursor);
        if (cursor + 4 > body.length) {
            throw new IOException("malformed CONNECT packet");
        }
        int protocolLevel = body[cursor++] & 0xFF;
        int flags = body[cursor++] & 0xFF;
        cursor += 2; // keepalive
        if (!"MQTT".equals(protocolName) && !"MQIsdp".equals(protocolName)) {
            throw new IOException("unsupported MQTT protocol name");
        }
        if (protocolLevel == 5) {
            VariableByteInteger propertyLength = readVariableByteInteger(body, cursor);
            cursor = propertyLength.nextCursor + propertyLength.value;
            if (cursor > body.length) {
                throw new IOException("malformed MQTT 5 CONNECT properties");
            }
        }
        String clientId = cursor < body.length ? readUtf8(body, cursor) : "";
        return new ConnectInfo(clientId, protocolLevel, (flags & 0x80) != 0);
    }

    private static VariableByteInteger readVariableByteInteger(byte[] body, int cursor) throws IOException {
        int count = 0;
        int multiplier = 1;
        int value = 0;
        int digit;
        do {
            if (cursor >= body.length || count++ == 4) {
                throw new IOException("malformed MQTT variable byte integer");
            }
            digit = body[cursor++] & 0xFF;
            value += (digit & 0x7F) * multiplier;
            multiplier *= 128;
        } while ((digit & 0x80) != 0);
        return new VariableByteInteger(value, cursor);
    }

    private static String readUtf8(byte[] body, int cursor) throws IOException {
        int length = utf8Length(body, cursor);
        if (cursor + 2 + length > body.length) {
            throw new IOException("malformed MQTT UTF-8 field");
        }
        return new String(body, cursor + 2, length, StandardCharsets.UTF_8);
    }

    private static int utf8Length(byte[] body, int cursor) throws IOException {
        if (cursor + 2 > body.length) {
            throw new IOException("malformed MQTT UTF-8 length");
        }
        return unsignedShort(body, cursor);
    }

    private static Packet readPacket(DataInputStream in) throws IOException {
        int header = in.read();
        if (header < 0) {
            return null;
        }
        int multiplier = 1;
        int remainingLength = 0;
        int digits = 0;
        int digit;
        do {
            digit = in.read();
            if (digit < 0) {
                throw new EOFException("connection ended inside MQTT remaining length");
            }
            if (++digits > 4) {
                throw new IOException("invalid MQTT remaining length");
            }
            remainingLength += (digit & 0x7F) * multiplier;
            multiplier *= 128;
        } while ((digit & 0x80) != 0);
        if (remainingLength < 0 || remainingLength > MAX_PACKET_BYTES) {
            throw new IOException("MQTT packet exceeds " + MAX_PACKET_BYTES + " bytes");
        }
        byte[] body = new byte[remainingLength];
        in.readFully(body);
        return new Packet(header, body);
    }

    private static void writeConnAck(DataOutputStream out, int protocolLevel) throws IOException {
        writePacket(out, 0x20, protocolLevel == 5
                ? new byte[] {0x00, 0x00, 0x00}
                : new byte[] {0x00, 0x00});
    }

    private static void writeSubAck(
            DataOutputStream out, Packet packet, int protocolLevel, int subscriptionCount) throws IOException {
        if (packet.body.length < 2) {
            throw new IOException("malformed SUBSCRIBE packet");
        }
        int cursor = protocolLevel == 5 ? 3 : 2;
        byte[] response = new byte[cursor + subscriptionCount];
        response[0] = packet.body[0];
        response[1] = packet.body[1];
        writePacket(out, 0x90, response);
    }

    private static List<String> parseSubscriptions(Packet packet, int protocolLevel) throws IOException {
        byte[] body = packet.body;
        if (body.length < 2) {
            throw new IOException("malformed SUBSCRIBE packet");
        }
        int cursor = 2;
        if (protocolLevel == 5) {
            VariableByteInteger propertyLength = readVariableByteInteger(body, cursor);
            cursor = propertyLength.nextCursor + propertyLength.value;
        }
        List<String> filters = new ArrayList<String>();
        while (cursor < body.length) {
            String filter = readUtf8(body, cursor);
            cursor += 2 + utf8Length(body, cursor);
            if (cursor >= body.length) {
                throw new IOException("malformed SUBSCRIBE options");
            }
            cursor += 1;
            filters.add(filter);
        }
        return Collections.unmodifiableList(filters);
    }

    private static boolean matchesAnySubscription(String topic, List<String> filters) {
        for (String filter : filters) {
            if (matchesTopicFilter(topic, filter)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> mergeSubscriptions(List<String> current, List<String> additions) {
        List<String> merged = new ArrayList<String>(current);
        for (String addition : additions) {
            if (!merged.contains(addition)) {
                merged.add(addition);
            }
        }
        return Collections.unmodifiableList(merged);
    }

    private static boolean matchesTopicFilter(String topic, String filter) {
        String[] topicParts = topic.split("/", -1);
        String[] filterParts = filter.split("/", -1);
        int topicIndex = 0;
        for (int filterIndex = 0; filterIndex < filterParts.length; filterIndex++) {
            String part = filterParts[filterIndex];
            if ("#".equals(part)) {
                return filterIndex == filterParts.length - 1;
            }
            if (topicIndex >= topicParts.length
                    || (!"+".equals(part) && !part.equals(topicParts[topicIndex]))) {
                return false;
            }
            topicIndex += 1;
        }
        return topicIndex == topicParts.length;
    }

    private static byte[] publishAckBody(Packet packet, int protocolLevel) throws IOException {
        if (packet.body.length < 2) {
            throw new IOException("malformed PUBREL packet");
        }
        return protocolLevel == 5
                ? new byte[] {packet.body[0], packet.body[1], 0x00, 0x00}
                : new byte[] {packet.body[0], packet.body[1]};
    }

    private static void writePacket(DataOutputStream out, int header, byte[] body) throws IOException {
        out.write(header);
        int remaining = body.length;
        do {
            int digit = remaining % 128;
            remaining /= 128;
            if (remaining > 0) {
                digit |= 0x80;
            }
            out.write(digit);
        } while (remaining > 0);
        out.write(body);
        out.flush();
    }

    private static byte[] shortBytes(int value) {
        return new byte[] {(byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
    }

    private static int unsignedShort(byte[] bytes, int cursor) {
        return ((bytes[cursor] & 0xFF) << 8) | (bytes[cursor + 1] & 0xFF);
    }

    private static int packetType(int header) {
        return (header >> 4) & 0x0F;
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void notifyStarted(int actualPort) {
        if (listener != null) {
            listener.onStarted(actualPort);
        }
    }

    private void notifyError(String message) {
        if (listener != null) {
            listener.onError(message);
        }
    }

    private static String summarize(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message.trim();
    }

    private static final class Packet {
        final int header;
        final byte[] body;

        Packet(int header, byte[] body) {
            this.header = header;
            this.body = body;
        }
    }

    private static final class ConnectInfo {
        final String clientId;
        final int protocolLevel;
        final boolean hasUsername;

        ConnectInfo(String clientId, int protocolLevel, boolean hasUsername) {
            this.clientId = clientId;
            this.protocolLevel = protocolLevel;
            this.hasUsername = hasUsername;
        }
    }

    private static final class ClientConnection {
        final Socket socket;
        final DataOutputStream output;
        final int protocolLevel;
        volatile List<String> subscriptions = Collections.emptyList();

        ClientConnection(Socket socket, DataOutputStream output, int protocolLevel) {
            this.socket = socket;
            this.output = output;
            this.protocolLevel = protocolLevel;
        }
    }

    private static final class VariableByteInteger {
        final int value;
        final int nextCursor;

        VariableByteInteger(int value, int nextCursor) {
            this.value = value;
            this.nextCursor = nextCursor;
        }
    }
}
