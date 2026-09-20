package dev.zazuzin.zst;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Lightweight Minecraft login-phase authentication classifier.
 *
 * The normal Server List Ping does not expose online-mode. This service opens
 * a separate short-lived login connection and stops before configuration/play:
 * an authentication-requiring Encryption Request means Microsoft/online-mode,
 * while Login Success without required authentication means cracked/offline.
 * Results are cached so normal Finder scanning never waits for this work.
 */
final class ServerAuthService {
    static final int RECHECK_DAYS = 14;
    static final int WORKERS = 8;
    static final int TIMEOUT_MS = 3_000;

    private static final long RECHECK_TTL_MS = TimeUnit.DAYS.toMillis(RECHECK_DAYS);
    private static final int MAX_PACKET_BYTES = 2 * 1024 * 1024;
    private static final int MAX_LOGIN_PACKETS = 8;
    private static final int MAX_QUEUED = 256;
    private static final Path FILE = ToolState.configDir().resolve("server-auth.properties");
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            WORKERS, WORKERS, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUED), runnable -> {
                Thread thread = new Thread(runnable, "Zazu-Auth-Probe-" + THREAD_NUMBER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    enum Type { MICROSOFT, CRACKED, UNKNOWN }

    record Entry(Type type, long checkedAt, int protocol, String detail) {
        boolean fresh(long now) {
            return checkedAt > 0L && now - checkedAt < RECHECK_TTL_MS;
        }
    }

    private ServerAuthService() {}

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
        load();
    }

    static boolean isChecking(String endpoint) {
        return IN_FLIGHT.contains(normalize(endpoint));
    }

    static String shortLabel(String endpoint) {
        if (isChecking(endpoint)) return "…";
        Entry entry = CACHE.get(normalize(endpoint));
        if (entry == null) return "-";
        return switch (entry.type()) {
            case MICROSOFT -> "M";
            case CRACKED -> "C";
            case UNKNOWN -> "?";
        };
    }

    static String displayLabel(String endpoint) {
        if (isChecking(endpoint)) return "Checking…";
        Entry entry = CACHE.get(normalize(endpoint));
        if (entry == null) return "Not checked";
        return switch (entry.type()) {
            case MICROSOFT -> "Microsoft";
            case CRACKED -> "Cracked";
            case UNKNOWN -> "Unknown";
        };
    }

    static String checkedLabel(String endpoint) {
        Entry entry = CACHE.get(normalize(endpoint));
        if (entry == null || entry.checkedAt() <= 0L) return "Never";
        try {
            return DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")
                    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.checkedAt()));
        } catch (Throwable ignored) {
            return String.valueOf(entry.checkedAt());
        }
    }

    static boolean ensureAsync(Object client, String endpoint, int protocol, Runnable onUpdated) {
        if (!ToolState.authDetectionEnabled) return false;
        String key = normalize(endpoint);
        if (key.isBlank()) return false;
        Entry existing = CACHE.get(key);
        if (existing != null && existing.fresh(System.currentTimeMillis())) return false;
        return submit(client, endpoint, protocol, false, onUpdated);
    }

    static boolean recheckAsync(Object client, String endpoint, int protocol, Runnable onUpdated) {
        if (!ToolState.authDetectionEnabled) return false;
        return submit(client, endpoint, protocol, true, onUpdated);
    }

    private static boolean submit(Object client, String endpoint, int protocol, boolean force, Runnable onUpdated) {
        String key = normalize(endpoint);
        if (key.isBlank() || !IN_FLIGHT.add(key)) return false;
        if (!force) {
            Entry existing = CACHE.get(key);
            if (existing != null && existing.fresh(System.currentTimeMillis())) {
                IN_FLIGHT.remove(key);
                return false;
            }
        }

        int effectiveProtocol = protocol > 0 ? protocol : ToolState.protocolFor(endpoint);
        if (effectiveProtocol <= 0) effectiveProtocol = VanillaStatusProbe.currentProtocol();
        final int protocolToUse = effectiveProtocol;

        try {
            EXECUTOR.execute(() -> {
                Entry result;
                try {
                    result = probe(endpoint, protocolToUse);
                } catch (Throwable failure) {
                    rethrowIfFatal(failure);
                    result = new Entry(Type.UNKNOWN, System.currentTimeMillis(), protocolToUse, rootMessage(failure));
                }
                CACHE.put(key, result);
                save();
                IN_FLIGHT.remove(key);
                System.out.println("[Zazu's Server Seeker] Auth probe " + endpoint + ": "
                        + result.type() + " (protocol " + protocolToUse + ")"
                        + (result.detail().isBlank() ? "" : " — " + result.detail()));
                if (onUpdated != null) Reflection.execute(client, onUpdated);
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            IN_FLIGHT.remove(key);
            return false;
        }
    }

    private static Entry probe(String endpoint, int protocol) {
        long now = System.currentTimeMillis();
        try {
            Endpoint target = Endpoint.parse(endpoint);
            try (Socket socket = new Socket()) {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(target.host(), target.port()), TIMEOUT_MS);
                socket.setSoTimeout(TIMEOUT_MS);

                InputStream input = new BufferedInputStream(socket.getInputStream());
                OutputStream output = new BufferedOutputStream(socket.getOutputStream());
                writeHandshake(output, target, protocol);
                writeLoginStart(output, protocol);
                output.flush();

                boolean compression = false;
                for (int i = 0; i < MAX_LOGIN_PACKETS; i++) {
                    Packet packet = readPacket(input, compression);
                    int id = packet.id();

                    // Login Disconnect. Do not guess based on the text because
                    // whitelists, mod requirements and proxies can all disconnect here.
                    if (id == 0x00) return new Entry(Type.UNKNOWN, now, protocol, "Disconnected during login probe");

                    // Encryption Request. From 1.20.5 onward the packet can explicitly
                    // say authentication is not required; older forms always represent
                    // the normal Microsoft/online-mode authentication path.
                    if (id == 0x01) {
                        boolean shouldAuthenticate = encryptionRequiresAuthentication(packet.body(), protocol);
                        return new Entry(shouldAuthenticate ? Type.MICROSOFT : Type.CRACKED,
                                now, protocol, shouldAuthenticate
                                        ? "Authentication required before login"
                                        : "Encryption requested without authentication requirement");
                    }

                    // Login Success without an authentication-requiring encryption
                    // exchange is the observable client-side offline-mode signal.
                    if (id == 0x02) {
                        return new Entry(Type.CRACKED, now, protocol, "Login accepted without Microsoft authentication");
                    }

                    // Set Compression applies to subsequent login packets.
                    if (id == 0x03) {
                        compression = true;
                        continue;
                    }

                    // Login plugin/cookie negotiation can require a protocol-specific
                    // response. Mark unknown rather than sending arbitrary data.
                    if (id == 0x04 || id == 0x05) {
                        return new Entry(Type.UNKNOWN, now, protocol, "Custom login negotiation detected");
                    }
                }
                return new Entry(Type.UNKNOWN, now, protocol, "No decisive login response");
            }
        } catch (SocketTimeoutException timeout) {
            return new Entry(Type.UNKNOWN, now, protocol, "Login probe timed out");
        } catch (UnknownHostException failure) {
            return new Entry(Type.UNKNOWN, now, protocol, "DNS lookup failed");
        } catch (IOException | IllegalArgumentException failure) {
            return new Entry(Type.UNKNOWN, now, protocol, rootMessage(failure));
        }
    }

    private static boolean encryptionRequiresAuthentication(byte[] body, int protocol) throws IOException {
        // shouldAuthenticate was added to the modern Encryption Request in the
        // 1.20.5 protocol generation (766). Older packets do not have the flag.
        if (protocol < 766) return true;
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        readString(in);     // server id
        skipByteArray(in);  // public key
        skipByteArray(in);  // verify token
        int flag = in.read();
        return flag < 0 || flag != 0;
    }

    private static void writeHandshake(OutputStream output, Endpoint target, int protocol) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, 0x00);
        writeVarInt(packet, protocol);
        writeString(packet, target.host());
        packet.write((target.port() >>> 8) & 0xff);
        packet.write(target.port() & 0xff);
        writeVarInt(packet, 2); // login
        writeFramedPacket(output, packet.toByteArray());
    }

    private static void writeLoginStart(OutputStream output, int protocol) throws IOException {
        String username = probeUsername();
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, 0x00);
        writeString(packet, username);

        // Login Start changed several times around 1.19. Keep the payload valid
        // for the version families exposed by Server Seeker's version filter.
        if (protocol >= 764) {               // 1.20.2+
            writeUuid(packet, uuid);
        } else if (protocol >= 761) {        // 1.19.3 - 1.20.1
            packet.write(1);                 // optional UUID present
            writeUuid(packet, uuid);
        } else if (protocol == 760) {        // 1.19.1/1.19.2
            packet.write(0);                 // no signed profile key
            packet.write(1);                 // optional UUID present
            writeUuid(packet, uuid);
        } else if (protocol == 759) {        // 1.19
            packet.write(0);                 // no signed profile key
        }
        // <= 758 (1.18.2 and older): username only.
        writeFramedPacket(output, packet.toByteArray());
    }

    private static String probeUsername() {
        String hex = Long.toHexString(ThreadLocalRandom.current().nextLong());
        if (hex.length() < 7) hex = ("0000000" + hex);
        return "ZazuAuth_" + hex.substring(hex.length() - 7); // exactly 16 chars
    }

    private static Packet readPacket(InputStream input, boolean compression) throws IOException {
        int frameLength = readVarInt(input);
        if (frameLength <= 0 || frameLength > MAX_PACKET_BYTES)
            throw new IOException("Invalid Minecraft login packet length: " + frameLength);
        byte[] frame = readExactly(input, frameLength);
        byte[] packetBytes;

        if (!compression) {
            packetBytes = frame;
        } else {
            ByteArrayInputStream framed = new ByteArrayInputStream(frame);
            int dataLength = readVarInt(framed);
            byte[] payload = framed.readAllBytes();
            if (dataLength == 0) {
                packetBytes = payload;
            } else {
                if (dataLength < 0 || dataLength > MAX_PACKET_BYTES)
                    throw new IOException("Invalid uncompressed Minecraft packet length: " + dataLength);
                packetBytes = inflate(payload, dataLength);
            }
        }

        ByteArrayInputStream packet = new ByteArrayInputStream(packetBytes);
        int id = readVarInt(packet);
        return new Packet(id, packet.readAllBytes());
    }

    private static byte[] inflate(byte[] compressed, int expectedLength) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[expectedLength];
            int offset = 0;
            while (offset < out.length && !inflater.finished()) {
                int count;
                try { count = inflater.inflate(out, offset, out.length - offset); }
                catch (DataFormatException invalid) { throw new IOException("Invalid compressed Minecraft login packet", invalid); }
                if (count == 0 && inflater.needsInput()) break;
                if (count == 0 && inflater.needsDictionary()) throw new IOException("Compressed Minecraft packet needs a dictionary");
                offset += count;
            }
            if (offset != expectedLength) throw new IOException("Compressed Minecraft packet length mismatch");
            return out;
        } finally {
            inflater.end();
        }
    }

    private static void writeFramedPacket(OutputStream output, byte[] packet) throws IOException {
        writeVarInt(output, packet.length);
        output.write(packet);
    }

    private static void writeUuid(OutputStream output, UUID uuid) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(16);
        bytes.putLong(uuid.getMostSignificantBits());
        bytes.putLong(uuid.getLeastSignificantBits());
        output.write(bytes.array());
    }

    private static void skipByteArray(InputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > MAX_PACKET_BYTES) throw new IOException("Invalid Minecraft byte array length");
        readExactly(input, length);
    }

    private static String readString(InputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > MAX_PACKET_BYTES) throw new IOException("Invalid Minecraft string length");
        return new String(readExactly(input, length), StandardCharsets.UTF_8);
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] out = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(out, offset, length - offset);
            if (read < 0) throw new EOFException("Minecraft server closed the login connection");
            offset += read;
        }
        return out;
    }

    private static int readVarInt(InputStream input) throws IOException {
        int value = 0;
        int position = 0;
        while (position < 5) {
            int current = input.read();
            if (current < 0) throw new EOFException("Minecraft server closed the login connection");
            value |= (current & 0x7f) << (position * 7);
            if ((current & 0x80) == 0) return value;
            position++;
        }
        throw new IOException("Minecraft VarInt is too large");
    }

    private static void writeVarInt(OutputStream output, int value) throws IOException {
        do {
            int current = value & 0x7f;
            value >>>= 7;
            if (value != 0) current |= 0x80;
            output.write(current);
        } while (value != 0);
    }

    private static void writeString(OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static synchronized void load() {
        CACHE.clear();
        if (!Files.exists(FILE)) return;
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
            for (String key : properties.stringPropertyNames()) {
                if (!key.startsWith("auth.")) continue;
                String endpoint = decodeKey(key.substring(5));
                String[] fields = properties.getProperty(key, "").split("\\|", 4);
                if (endpoint.isBlank() || fields.length < 3) continue;
                try {
                    Type type = Type.valueOf(fields[0]);
                    long checkedAt = Long.parseLong(fields[1]);
                    int protocol = Integer.parseInt(fields[2]);
                    String detail = fields.length >= 4 ? decodeText(fields[3]) : "";
                    CACHE.put(normalize(endpoint), new Entry(type, checkedAt, protocol, detail));
                } catch (RuntimeException ignored) {}
            }
        } catch (IOException ex) {
            System.err.println("[Zazu's Server Seeker] Could not load auth cache: " + ex.getMessage());
        }
    }

    private static synchronized void save() {
        Properties properties = new Properties();
        ArrayList<String> keys = new ArrayList<>(CACHE.keySet());
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        for (String endpoint : keys) {
            Entry entry = CACHE.get(endpoint);
            if (entry == null) continue;
            properties.setProperty("auth." + encodeKey(endpoint), entry.type().name() + "|"
                    + entry.checkedAt() + "|" + entry.protocol() + "|" + encodeText(entry.detail()));
        }
        try {
            Files.createDirectories(FILE.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                properties.store(writer, "Zazu's Server Seeker authentication cache");
            }
        } catch (IOException ex) {
            System.err.println("[Zazu's Server Seeker] Could not save auth cache: " + ex.getMessage());
        }
    }

    private static String encodeKey(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeKey(String value) {
        try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    private static String encodeText(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Objects.toString(value, "").getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    private static String normalize(String endpoint) {
        return ToolState.normalize(endpoint);
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = Reflection.unwrap(failure);
        String message = root == null ? "" : root.getMessage();
        return message == null || message.isBlank()
                ? (root == null ? "Unknown auth probe error" : root.getClass().getSimpleName()) : message;
    }

    private static void rethrowIfFatal(Throwable failure) {
        Throwable root = Reflection.unwrap(failure);
        if (root instanceof Error fatal) throw fatal;
    }

    private record Packet(int id, byte[] body) {}

    private record Endpoint(String host, int port) {
        static Endpoint parse(String endpoint) {
            String value = endpoint == null ? "" : endpoint.trim();
            if (value.isEmpty()) throw new IllegalArgumentException("Empty server address");
            String host = value;
            int port = 25565;
            if (value.startsWith("[")) {
                int close = value.indexOf(']');
                if (close < 2) throw new IllegalArgumentException("Invalid IPv6 server address");
                host = value.substring(1, close);
                if (close + 1 < value.length()) {
                    if (value.charAt(close + 1) != ':') throw new IllegalArgumentException("Invalid IPv6 server port");
                    port = parsePort(value.substring(close + 2));
                }
            } else if (value.indexOf(':') == value.lastIndexOf(':') && value.lastIndexOf(':') > 0) {
                int split = value.lastIndexOf(':');
                host = value.substring(0, split);
                port = parsePort(value.substring(split + 1));
            }
            if (host.isBlank()) throw new IllegalArgumentException("Empty server host");
            return new Endpoint(host, port);
        }

        private static int parsePort(String value) {
            try {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65535) throw new IllegalArgumentException("Server port is out of range");
                return port;
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Invalid server port", invalid);
            }
        }
    }
}
