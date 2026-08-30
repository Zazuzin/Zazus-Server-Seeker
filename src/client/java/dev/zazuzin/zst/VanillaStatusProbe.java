package dev.zazuzin.zst;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, pure-Java Minecraft status client used only by Finder.
 *
 * This implementation intentionally does not touch Minecraft's
 * ServerStatusPinger, Netty event loops, native transports, or ViaFabricPlus
 * networking mixins. Provider candidates are ordinary IP:port endpoints, so a
 * small daemon socket pool can verify the standard Java status protocol without
 * putting Finder traffic into Minecraft's native networking lifecycle.
 */
final class VanillaStatusProbe {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int PONG_TIMEOUT_MS = 1_500;
    private static final int MAX_PACKET_BYTES = 2 * 1024 * 1024;
    // A provider page contains at most 20 candidates. Keeping one bounded
    // worker per candidate prevents dead endpoints from creating multiple
    // sequential timeout waves on later searches.
    private static final int MAX_IN_FLIGHT = 20;
    private static final int MAX_QUEUED = 128;
    private static final int FALLBACK_PROTOCOL_26_2 = 776;
    private static final Map<String, LatencySample> LATENCY = new ConcurrentHashMap<>();
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            MAX_IN_FLIGHT, MAX_IN_FLIGHT, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUED), runnable -> {
                Thread thread = new Thread(runnable, "Zazu-Finder-Status-" + THREAD_NUMBER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private static final Pattern VERSION_OBJECT = Pattern.compile(
            "\\\"version\\\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
    private static final Pattern VERSION_NAME = Pattern.compile(
            "\\\"name\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static final Pattern VERSION_PROTOCOL = Pattern.compile(
            "\\\"protocol\\\"\\s*:\\s*(-?\\d+)");

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    enum Failure { NONE, DNS, UNREACHABLE, TIMEOUT, ERROR }

    record Result(String endpoint, boolean replied, String version, int protocol,
                  long latencyMs, Failure failure, String detail) {
        static Result failed(String endpoint, Failure failure, String detail) {
            return new Result(endpoint, false, "", -1, -1L, failure, detail == null ? "" : detail);
        }
    }

    private VanillaStatusProbe() {}

    static CompletableFuture<List<Result>> probe(Object client, Object ignoredScreen,
                                                  Collection<String> endpoints,
                                                  BooleanSupplier sessionActive) {
        List<String> targets = endpoints == null ? List.of() : endpoints.stream()
                .filter(Objects::nonNull).map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
        if (targets.isEmpty()) return CompletableFuture.completedFuture(List.of());

        CompletableFuture<List<Result>> result = new CompletableFuture<>();
        Reflection.execute(client, () -> {
            if (!isActive(sessionActive)) {
                completeAllFailed(targets, result, Failure.ERROR, "Finder session is no longer active");
                return;
            }

            int protocol = currentProtocol();
            List<CompletableFuture<Result>> tasks = new ArrayList<>(targets.size());
            for (String endpoint : targets) {
                try {
                    tasks.add(CompletableFuture.supplyAsync(() -> probeEndpoint(endpoint, protocol), EXECUTOR));
                } catch (RejectedExecutionException rejected) {
                    tasks.add(CompletableFuture.completedFuture(
                            Result.failed(endpoint, Failure.ERROR, "Finder status queue is full")));
                }
            }

            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
                List<Result> completed = new ArrayList<>(tasks.size());
                for (int i = 0; i < tasks.size(); i++) {
                    try {
                        completed.add(tasks.get(i).join());
                    } catch (CompletionException failed) {
                        Throwable root = Reflection.unwrap(failed);
                        completed.add(Result.failed(targets.get(i), Failure.ERROR, rootMessage(root)));
                    }
                }
                Reflection.execute(client, () -> result.complete(List.copyOf(completed)));
            });
        });
        return result;
    }

    static CompletableFuture<Result> probeOne(Object client, Object ignoredScreen,
                                              String endpoint, BooleanSupplier sessionActive) {
        return probe(client, ignoredScreen, List.of(endpoint), sessionActive).thenApply(results ->
                results.isEmpty() ? Result.failed(endpoint, Failure.ERROR, "No probe result") : results.get(0));
    }

    static long cachedLatencyMillis(String endpoint) {
        if (endpoint == null) return -1L;
        LatencySample sample = LATENCY.get(ToolState.normalize(endpoint));
        if (sample == null || System.currentTimeMillis() - sample.atMillis > 60_000L) return -1L;
        return sample.latencyMs;
    }

    static int currentProtocol() {
        try {
            Class<?> shared = Class.forName("net.minecraft.SharedConstants");
            Method getCurrentVersion = shared.getMethod("getCurrentVersion");
            Object version = getCurrentVersion.invoke(null);
            Object value = Reflection.invokeQuiet(version, "protocolVersion");
            if (!(value instanceof Number)) value = Reflection.invokeQuiet(version, "getProtocolVersion");
            if (value instanceof Number n) return n.intValue();
        } catch (Throwable t) { rethrowIfFatal(t); }
        return FALLBACK_PROTOCOL_26_2;
    }

    private static Result probeEndpoint(String endpoint, int protocol) {
        long startedAt = System.nanoTime();
        try {
            Endpoint target = Endpoint.parse(endpoint);
            try (Socket socket = new Socket()) {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);

                InputStream input = new BufferedInputStream(socket.getInputStream());
                OutputStream output = new BufferedOutputStream(socket.getOutputStream());

                writeHandshake(output, target, protocol);
                writeStatusRequest(output);
                output.flush();

                byte[] statusPacket = readPacket(input);
                ByteArrayInputStream statusInput = new ByteArrayInputStream(statusPacket);
                if (readVarInt(statusInput) != 0) throw new IOException("Unexpected Minecraft status packet");
                String json = readString(statusInput);
                VersionInfo version = parseVersion(json);
                long latency = elapsedMillis(startedAt);

                try {
                    socket.setSoTimeout(PONG_TIMEOUT_MS);
                    long pingStarted = System.nanoTime();
                    long token = System.nanoTime();
                    writePing(output, token);
                    output.flush();
                    byte[] pongPacket = readPacket(input);
                    ByteArrayInputStream pongInput = new ByteArrayInputStream(pongPacket);
                    if (readVarInt(pongInput) == 1 && readLong(pongInput) == token) latency = elapsedMillis(pingStarted);
                } catch (IOException ignored) {
                    // A valid status response is sufficient proof that the server
                    // is live even if it closes before the optional pong reply.
                }

                LATENCY.put(ToolState.normalize(endpoint), new LatencySample(latency, System.currentTimeMillis()));
                return new Result(endpoint, true, version.name, version.protocol, latency, Failure.NONE, "");
            }
        } catch (UnknownHostException | UnresolvedAddressException failure) {
            return Result.failed(endpoint, Failure.DNS, rootMessage(failure));
        } catch (SocketTimeoutException failure) {
            return Result.failed(endpoint, Failure.TIMEOUT, rootMessage(failure));
        } catch (ConnectException | NoRouteToHostException | PortUnreachableException | EOFException failure) {
            return Result.failed(endpoint, Failure.UNREACHABLE, rootMessage(failure));
        } catch (SocketException failure) {
            return Result.failed(endpoint, Failure.UNREACHABLE, rootMessage(failure));
        } catch (IOException failure) {
            return Result.failed(endpoint, Failure.UNREACHABLE, rootMessage(failure));
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
            return Result.failed(endpoint, Failure.ERROR, rootMessage(failure));
        }
    }

    private static void writeHandshake(OutputStream output, Endpoint target, int protocol) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, 0);
        writeVarInt(packet, protocol);
        writeString(packet, target.host);
        packet.write((target.port >>> 8) & 0xff);
        packet.write(target.port & 0xff);
        writeVarInt(packet, 1);
        writePacket(output, packet.toByteArray());
    }

    private static void writeStatusRequest(OutputStream output) throws IOException {
        writePacket(output, new byte[]{0});
    }

    private static void writePing(OutputStream output, long token) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream(9);
        writeVarInt(packet, 1);
        for (int shift = 56; shift >= 0; shift -= 8) packet.write((int) (token >>> shift) & 0xff);
        writePacket(output, packet.toByteArray());
    }

    private static void writePacket(OutputStream output, byte[] packet) throws IOException {
        writeVarInt(output, packet.length);
        output.write(packet);
    }

    private static byte[] readPacket(InputStream input) throws IOException {
        int length = readVarInt(input);
        if (length <= 0 || length > MAX_PACKET_BYTES) throw new IOException("Invalid Minecraft packet length: " + length);
        return readExactly(input, length);
    }

    private static String readString(InputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > MAX_PACKET_BYTES) throw new IOException("Invalid Minecraft string length: " + length);
        return new String(readExactly(input, length), StandardCharsets.UTF_8);
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read < 0) throw new EOFException("Minecraft server closed the status connection");
            offset += read;
        }
        return bytes;
    }

    private static int readVarInt(InputStream input) throws IOException {
        int value = 0;
        int position = 0;
        while (position < 5) {
            int current = input.read();
            if (current < 0) throw new EOFException("Minecraft server closed the status connection");
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

    private static long readLong(InputStream input) throws IOException {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            int current = input.read();
            if (current < 0) throw new EOFException("Minecraft server closed the pong connection");
            value = (value << 8) | current;
        }
        return value;
    }

    private static VersionInfo parseVersion(String json) throws IOException {
        Matcher object = VERSION_OBJECT.matcher(json);
        if (!object.find()) throw new IOException("Minecraft status response has no version object");
        String body = object.group(1);
        Matcher name = VERSION_NAME.matcher(body);
        Matcher protocol = VERSION_PROTOCOL.matcher(body);
        if (!name.find() || !protocol.find()) throw new IOException("Minecraft status response has incomplete version data");
        try {
            return new VersionInfo(unescapeJson(name.group(1)), Integer.parseInt(protocol.group(1)));
        } catch (NumberFormatException invalid) {
            throw new IOException("Minecraft status protocol is invalid", invalid);
        }
    }

    private static String unescapeJson(String value) throws IOException {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') { out.append(c); continue; }
            if (++i >= value.length()) throw new IOException("Invalid JSON escape");
            char escaped = value.charAt(i);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 >= value.length()) throw new IOException("Invalid JSON unicode escape");
                    try { out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16)); }
                    catch (NumberFormatException invalid) { throw new IOException("Invalid JSON unicode escape", invalid); }
                    i += 4;
                }
                default -> throw new IOException("Invalid JSON escape");
            }
        }
        return out.toString();
    }

    private static boolean isActive(BooleanSupplier sessionActive) {
        if (sessionActive == null) return true;
        try { return sessionActive.getAsBoolean(); }
        catch (Throwable t) {
            rethrowIfFatal(t);
            return false;
        }
    }

    private static void completeAllFailed(List<String> targets, CompletableFuture<List<Result>> future,
                                          Failure failure, String detail) {
        List<Result> failed = new ArrayList<>(targets.size());
        for (String endpoint : targets) failed.add(Result.failed(endpoint, failure, detail));
        future.complete(List.copyOf(failed));
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(1L, Math.round((System.nanoTime() - startedAt) / 1_000_000.0));
    }

    private static String rootMessage(Throwable t) {
        Throwable root = Reflection.unwrap(t);
        String message = root == null ? "" : root.getMessage();
        return message == null || message.isBlank()
                ? (root == null ? "Unknown probe error" : root.getClass().getSimpleName()) : message;
    }

    private static void rethrowIfFatal(Throwable t) {
        Throwable root = Reflection.unwrap(t);
        if (root instanceof ThreadDeath death) throw death;
        if (root instanceof VirtualMachineError fatal) throw fatal;
    }

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

    private record VersionInfo(String name, int protocol) {}
    private record LatencySample(long latencyMs, long atMillis) {}
}
