package dev.zazuzin.zst;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.ServerList;

/** Runs the packaged Finder against controlled loopback Minecraft servers. */
public final class StreamingFinderTest {
    private static final Class<?> PROVIDER;
    private static final Object BREAKBLOCKS;
    private static final Method VERIFY;
    static {
        try {
            PROVIDER = Class.forName("dev.zazuzin.zst.ServerFinderClient$Provider");
            BREAKBLOCKS = Arrays.stream(PROVIDER.getEnumConstants())
                    .filter(v -> v.toString().equals("BREAKBLOCKS")).findFirst().orElseThrow();
            VERIFY = ServerFinderClient.class.getDeclaredMethod("verifyProviderCandidatesTwice",
                    ServerFinderClient.OverlayState.class, PROVIDER, List.class);
            VERIFY.setAccessible(true);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    // A real serial event queue catches accidental worker-thread state changes.
    public static final class Client implements AutoCloseable {
        private final ExecutorService main = Executors.newSingleThreadExecutor();
        public void execute(Runnable task) { main.execute(task); }
        <T> T call(Callable<T> task) throws Exception { return main.submit(task).get(10, TimeUnit.SECONDS); }
        void run(Runnable task) throws Exception { call(() -> { task.run(); return null; }); }
        @Override public void close() { main.shutdownNow(); }
    }

    private static void await(Client client, BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (client.call(condition::getAsBoolean)) return;
            Thread.sleep(10);
        }
        throw new AssertionError(message);
    }

    private static ServerFinderClient.OverlayState state(Client client) {
        var s = new ServerFinderClient.OverlayState(client, new Object(), 600, 400);
        s.open = true; s.loading = true; s.exhausted = true;
        return s;
    }

    private static ServerFinderClient.ServerRecord record(String host, int port) {
        return new ServerFinderClient.ServerRecord(host, port, "26.2 Test", 1, 20,
                "Loopback fixture", "", "", "", "", "", "", false, false, List.of(), 776, "BreakBlocks");
    }

    private static void start(Client client, ServerFinderClient.OverlayState state,
                              List<ServerFinderClient.ServerRecord> records) throws Exception {
        client.call(() -> { VERIFY.invoke(null, state, BREAKBLOCKS, records); return null; });
    }

    public static void main(String[] args) throws Exception {
        ToolState.load();
        ToolState.authDetectionEnabled = false;
        ToolState.contributeVerifiedServers = false;
        ToolState.recordPingPlayers = false;
        try (Client client = new Client()) {
            streamsBeforeSlowCandidate(client);
            retainsEveryResultAndHonoursAutoAddLimit(client);
            rejectsChangedVersion(client);
            ignoresCancelledReplies(client);
            cancelsDelayedConfirmation(client);
        }
        System.out.println("Streaming Finder: independent two-pass checks, immediate results, sorting, Auto Add limits, version filters and cancellation passed.");
    }

    private static void streamsBeforeSlowCandidate(Client client) throws Exception {
        try (StatusServer fast = new StatusServer(Mode.GATED_SECOND);
             StatusServer failsSecond = new StatusServer(Mode.FAIL_SECOND);
             StatusServer slow = new StatusServer(Mode.HOLD_FIRST)) {
            var s = state(client);
            start(client, s, List.of(record("127.0.0.1", fast.port()),
                    record("127.0.0.1", failsSecond.port()), record("127.0.0.1", slow.port())));
            check(fast.secondAccepted.await(10, TimeUnit.SECONDS), "Fast candidate waited for the stalled first-pass candidate");
            client.run(() -> {
                check(s.results.isEmpty() && s.liveVerified == 0, "A single successful check was published");
                check(s.loading, "Search ended with pending checks");
            });
            check(fast.secondAcceptedAt - fast.firstResponseAt >= TimeUnit.MILLISECONDS.toNanos(950),
                    "Independent confirmation lost the one-second separation");
            fast.release.countDown();
            await(client, () -> s.results.size() == 1 && s.statusRejected == 1,
                    "Confirmed server was not published while the slow candidate was pending");
            client.run(() -> {
                check(s.loading && slow.connections.get() == 1, "Slow candidate did not remain pending");
                check(s.results.get(0).port() == fast.port(), "A failed second check was accepted");
                check(ServerList.persistedVisible.isEmpty(), "Manual search saved a result without Add");
            });
            slow.release.countDown();
            await(client, () -> !s.loading, "Search did not finish after the last candidate");
            client.run(() -> {
                check(s.statusProbeAttempts == 5 && s.statusFirstPasses == 2 && s.statusSecondPasses == 1,
                        "Per-server probe counters are inconsistent");
                check(s.liveVerified == 1 && s.statusRejected == 2 && s.breakBlocksLiveVerified == 1,
                        "Accepted/rejected totals are inconsistent");
                check(s.results.size() == 1, "Final publication duplicated a streamed result");
            });
            System.out.println("Fast server completed both checks before the held slow server was released.");
        }
    }

    private static void retainsEveryResultAndHonoursAutoAddLimit(Client client) throws Exception {
        try (StatusServer fast = new StatusServer(Mode.NORMAL)) {
            var s = state(client);
            s.autoAdd = true; s.sortIndex = 3;
            ToolState.autoAddLimit = 2;
            List<ServerFinderClient.ServerRecord> records = new ArrayList<>();
            for (int i = 10; i >= 1; i--) records.add(record("127.0.0." + i, fast.port()));
            start(client, s, records);
            await(client, () -> !s.loading, "Ten live candidates did not complete");
            client.run(() -> {
                check(s.results.size() == 10 && s.currentBatch.size() == 10, "Results beyond the eight-result search target were discarded");
                check(s.liveVerified == 10 && s.statusProbeAttempts == 20, "Successful candidates did not receive two checks each");
                check(s.autoAddedThisSession == 2 && !s.autoAdd && ServerList.persistedVisible.size() == 2,
                        "Streaming Auto Add exceeded its configured limit");
                List<String> addresses = s.results.stream().map(ServerFinderClient.ServerRecord::address).toList();
                check(addresses.equals(addresses.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()), "Streaming results lost the selected sort order");
            });
        }
    }

    private static void rejectsChangedVersion(Client client) throws Exception {
        try (StatusServer server = new StatusServer(Mode.CHANGED_VERSION)) {
            var s = state(client); s.versionIndex = 2; // 26.2
            start(client, s, List.of(record("127.0.0.1", server.port())));
            await(client, () -> !s.loading, "Incompatible candidate did not finish");
            client.run(() -> check(s.results.isEmpty() && s.statusRejected == 1 && s.breakBlocksIncompatibleReplies == 1,
                    "A changed live version bypassed the version filter"));
        }
    }

    private static void ignoresCancelledReplies(Client client) throws Exception {
        try (StatusServer server = new StatusServer(Mode.GATED_SECOND)) {
            var s = state(client);
            start(client, s, List.of(record("127.0.0.1", server.port())));
            check(server.secondAccepted.await(10, TimeUnit.SECONDS), "Confirmation did not start");
            client.run(() -> s.searchGeneration++);
            server.release.countDown();
            check(server.secondFinished.await(10, TimeUnit.SECONDS), "Fixture confirmation did not finish");
            Field field = VanillaStatusProbe.class.getDeclaredField("EXECUTOR"); field.setAccessible(true);
            ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(null);
            await(client, () -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty(), "Probe workers did not settle");
            client.run(() -> check(s.results.isEmpty() && s.liveVerified == 0 && s.statusRejected == 0 && s.loading,
                    "A stale reply changed a newer search"));
        }
    }

    private static void cancelsDelayedConfirmation(Client client) throws Exception {
        try (StatusServer server = new StatusServer(Mode.NORMAL)) {
            var s = state(client);
            start(client, s, List.of(record("127.0.0.1", server.port())));
            await(client, () -> s.statusFirstPasses == 1, "First pass did not complete");
            client.run(() -> s.open = false);
            Thread.sleep(1_250);
            client.run(() -> check(server.connections.get() == 1 && s.results.isEmpty() && s.statusProbeAttempts == 1,
                    "Closing Finder allowed a delayed confirmation or publication"));
        }
    }

    enum Mode { NORMAL, GATED_SECOND, FAIL_SECOND, HOLD_FIRST, CHANGED_VERSION }

    private static final class StatusServer implements AutoCloseable {
        final ServerSocket listener;
        final Mode mode;
        final AtomicInteger connections = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch secondAccepted = new CountDownLatch(1), secondFinished = new CountDownLatch(1);
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        volatile long firstResponseAt, secondAcceptedAt;
        volatile boolean closed;

        StatusServer(Mode mode) throws IOException {
            this.mode = mode;
            listener = new ServerSocket(0);
            Thread accept = new Thread(() -> {
                while (!closed) {
                    try {
                        Socket socket = listener.accept(); sockets.add(socket);
                        int number = connections.incrementAndGet();
                        Thread handler = new Thread(() -> handle(socket, number), "streaming-fixture");
                        handler.setDaemon(true); handler.start();
                    } catch (IOException e) { if (!closed) throw new UncheckedIOException(e); }
                }
            }, "streaming-fixture-accept");
            accept.setDaemon(true); accept.start();
        }

        int port() { return listener.getLocalPort(); }
        private void handle(Socket socket, int number) {
            try (socket) {
                socket.setSoTimeout(10_000);
                InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream();
                readPacket(input); readPacket(input);
                if (number == 2) { secondAcceptedAt = System.nanoTime(); secondAccepted.countDown(); }
                if (mode == Mode.HOLD_FIRST) { release.await(10, TimeUnit.SECONDS); return; }
                if (number == 2 && mode == Mode.FAIL_SECOND) return;
                if (number == 2 && mode == Mode.GATED_SECOND) release.await(10, TimeUnit.SECONDS);
                String version = number == 2 && mode == Mode.CHANGED_VERSION ? "1.8" : "26.2 Test";
                int protocol = version.equals("1.8") ? 47 : 776;
                byte[] json = ("{\"version\":{\"name\":\"" + version + "\",\"protocol\":" + protocol
                        + "},\"players\":{\"online\":1,\"max\":20}}").getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream status = new ByteArrayOutputStream();
                varInt(status, 0); varInt(status, json.length); status.write(json);
                if (number == 1) firstResponseAt = System.nanoTime();
                packet(output, status.toByteArray()); output.flush();
                packet(output, readPacket(input)); output.flush();
            } catch (Exception e) { if (!closed) throw new RuntimeException(e); }
            finally { sockets.remove(socket); if (number == 2) secondFinished.countDown(); }
        }

        @Override public void close() throws IOException {
            closed = true; release.countDown(); listener.close();
            for (Socket socket : sockets) socket.close();
        }
        private static byte[] readPacket(InputStream input) throws IOException {
            int length = varInt(input);
            if (length < 0 || length > 4096) throw new IOException("Invalid fixture packet");
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) throw new EOFException();
            return bytes;
        }
        private static int varInt(InputStream input) throws IOException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = input.read(); if (b < 0) throw new EOFException();
                value |= (b & 127) << shift; if ((b & 128) == 0) return value;
            }
            throw new IOException("Invalid fixture VarInt");
        }
        private static void varInt(OutputStream output, int value) throws IOException {
            do { int b = value & 127; value >>>= 7; output.write(value == 0 ? b : b | 128); } while (value != 0);
        }
        private static void packet(OutputStream output, byte[] bytes) throws IOException {
            varInt(output, bytes.length); output.write(bytes);
        }
    }
}
