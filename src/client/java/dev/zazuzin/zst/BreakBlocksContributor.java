package dev.zazuzin.zst;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.awt.Desktop;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Sends locally verified discoveries to BreakBlocks without blocking Minecraft. */
final class BreakBlocksContributor {
    private static final String BASE_URL = "https://api.breakblocks.com/api/v0.1/status/ping/";
    private static final String USER_AGENT = ReleaseInfo.USER_AGENT;
    private static final Path AUDIT_LOG = ToolState.configDir().resolve("breakblocks-contributions.csv");
    private static final Path PENDING_FILE = ToolState.configDir().resolve("breakblocks-contribution-queue.txt");
    private static final Path FAILED_FILE = ToolState.configDir().resolve("breakblocks-contribution-failed.txt");
    private static final Path COOLDOWN_FILE = ToolState.configDir().resolve("breakblocks-contribution-cooldowns.txt");
    private static final Path PRIVATE_LAN_FILE = ToolState.configDir().resolve("private-lan-servers.txt");
    private static final int MAX_REFRESH_RETRIES = 3;
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final long REFRESH_DELAY_SECONDS = 10L;
    private static final long ANONYMOUS_SPACING_SECONDS = 4L;
    private static final long AUTHENTICATED_SPACING_SECONDS = 1L;
    private static final long SUCCESS_COOLDOWN_MS = TimeUnit.HOURS.toMillis(6L);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "zazu-breakblocks-contributor");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> QUEUED = new LinkedHashSet<>();
    private static final Set<String> FAILED = new LinkedHashSet<>();
    private static final Set<String> PRIVATE_LAN = new LinkedHashSet<>();
    private static final Map<String, Long> COOLDOWNS = new LinkedHashMap<>();
    private static final ArrayDeque<Task> READY = new ArrayDeque<>();
    private static final PriorityQueue<Task> DELAYED = new PriorityQueue<>(Comparator.comparingLong(Task::readyAtMs));
    private static final AtomicInteger ACCEPTED_SESSION = new AtomicInteger();
    private static final AtomicInteger REFRESHING_SESSION = new AtomicInteger();
    private static final AtomicInteger RATE_LIMITED_SESSION = new AtomicInteger();
    private static final AtomicInteger FAILED_SESSION = new AtomicInteger();
    private static boolean initialized;
    private static ScheduledFuture<?> pumpFuture;
    private static long pumpAtMs;
    private static boolean quotaPauseLogged;
    private static volatile String currentEndpoint = "";

    private BreakBlocksContributor() {}

    static void initialize() {
        synchronized (QUEUED) {
            if (initialized) return;
            initialized = true;
            try {
                loadFailedLocked();
                loadCooldownsLocked();
                loadPrivateLanLocked();
                if (!Files.isRegularFile(PENDING_FILE)) return;
                for (String raw : Files.readAllLines(PENDING_FILE, StandardCharsets.UTF_8)) {
                    Endpoint parsed = parseEndpoint(raw);
                    if (parsed == null || !QUEUED.add(parsed.normalized())) continue;
                    READY.addLast(Task.ready(parsed, 0, 0));
                    audit(parsed.normalized(), 0, "restored", 0,
                            "Restored unfinished BreakBlocks contribution after restart");
                }
                schedulePumpLocked(0L);
            } catch (Exception error) {
                System.err.println("[Zazu's Server Seeker] Could not restore BreakBlocks contribution queue: "
                        + error.getClass().getSimpleName());
            }
        }
    }

    static void submit(String address, int port) {
        submit(address, port, "", "Double-verified server queued for BreakBlocks");
    }

    static void submit(String address, int port, String breakBlocksLastPing) {
        submit(address, port, breakBlocksLastPing, "Double-verified server queued for BreakBlocks");
    }

    static void submitConnected(String endpoint) {
        Endpoint parsed = parseEndpoint(endpoint);
        if (parsed == null) return;
        submit(parsed.host(), parsed.port(), "", "Stable connected server queued for BreakBlocks");
    }

    private static void submit(String address, int port, String breakBlocksLastPing, String detail) {
        if (!ToolState.contributeVerifiedServers) return;
        initialize();
        String host = address == null ? "" : address.trim();
        if (host.isBlank() || port < 1 || port > 65535) return;
        Endpoint parsed = endpoint(host, port);
        if (parsed == null) return;
        String endpoint = parsed.normalized();
        synchronized (QUEUED) {
            if (isPrivateOrLan(parsed.host())) {
                if (PRIVATE_LAN.add(endpoint)) {
                    persistSetLocked(PRIVATE_LAN_FILE, PRIVATE_LAN, "private/LAN list");
                    log(endpoint, "private/LAN server recorded locally; not sent to BreakBlocks");
                }
                return;
            }
            purgeCooldownsLocked();
            if (isRecentBreakBlocksPing(breakBlocksLastPing)
                    || COOLDOWNS.getOrDefault(endpoint, 0L) > System.currentTimeMillis()) return;
            if (!QUEUED.add(endpoint)) return;
            READY.addLast(Task.ready(parsed, 0, 0));
            persistQueueLocked();
            schedulePumpLocked(0L);
        }
        audit(endpoint, 0, "queued", 0, detail);
    }

    static void onSettingChanged() {
        initialize();
        synchronized (QUEUED) {
            if (ToolState.contributeVerifiedServers) schedulePumpLocked(0L);
        }
    }

    private static void schedulePumpLocked(long delayMs) {
        if (!ToolState.contributeVerifiedServers || QUEUED.isEmpty()) return;
        long target = System.currentTimeMillis() + Math.max(0L, delayMs);
        if (pumpFuture != null && !pumpFuture.isDone() && pumpAtMs <= target) return;
        if (pumpFuture != null) pumpFuture.cancel(false);
        pumpAtMs = target;
        pumpFuture = WORKER.schedule(BreakBlocksContributor::pump,
                Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    private static void pump() {
        Task task;
        synchronized (QUEUED) {
            pumpFuture = null;
            pumpAtMs = 0L;
            if (!ToolState.contributeVerifiedServers) return;
            long now = System.currentTimeMillis();
            if (!DELAYED.isEmpty() && DELAYED.peek().readyAtMs() <= now) task = DELAYED.remove();
            else if (!READY.isEmpty()) task = READY.removeFirst();
            else {
                if (!DELAYED.isEmpty()) schedulePumpLocked(Math.max(1L, DELAYED.peek().readyAtMs() - now));
                return;
            }
        }

        String apiKey = ToolState.breakBlocksApiKey();
        BreakBlocksAccount.configure(apiKey);
        long quotaDelayMs = BreakBlocksRateBudget.contributionDelayMillis(!apiKey.isBlank());
        if (quotaDelayMs > 0L) {
            long waitSeconds = Math.max(1L, (quotaDelayMs + 999L) / 1_000L);
            synchronized (QUEUED) {
                READY.addFirst(task);
                if (!quotaPauseLogged) {
                    quotaPauseLogged = true;
                    log(task.endpoint(), "queue paused for quota; resuming in " + waitSeconds + " seconds");
                    audit(task.endpoint(), task.refreshRetries() + 1, "quota_wait", 0,
                            "Contribution worker resumes in " + waitSeconds + " seconds");
                }
                schedulePumpLocked(quotaDelayMs);
            }
            return;
        }

        synchronized (QUEUED) {
            quotaPauseLogged = false;
        }
        send(task, apiKey);
    }

    private static void send(Task task, String apiKey) {
        Endpoint server = task.server();
        BreakBlocksAccount.configure(apiKey);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(server.host(), server.port()))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET();
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        BreakBlocksRateBudget.recordContributionRequest(!apiKey.isBlank());
        currentEndpoint = task.endpoint();

        HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> WORKER.execute(() -> {
                    currentEndpoint = "";
                    if (error != null || response == null) {
                        String detail = "request failed: " + message(error);
                        log(task.endpoint(), detail);
                        audit(task.endpoint(), task.refreshRetries() + 1, "network_error", 0, detail);
                        markFailed(task.endpoint());
                        scheduleAfterResponse(apiKey);
                        return;
                    }

                    BreakBlocksRateBudget.observe(response, !apiKey.isBlank());

                    if (response.statusCode() == 429) {
                        RATE_LIMITED_SESSION.incrementAndGet();
                        BreakBlocksRateBudget.noteRateLimit(response);
                        long wait = retryAfterSeconds(response);
                        audit(task.endpoint(), task.refreshRetries() + 1, "rate_limited", 429, "Retry-After: " + wait + " seconds");
                        if (task.rateRetries() < MAX_RATE_LIMIT_RETRIES) {
                            log(task.endpoint(), "rate limited; retrying after the queue resumes in " + wait + " seconds");
                            delay(task.withRateRetry(), TimeUnit.SECONDS.toMillis(wait));
                        } else {
                            log(task.endpoint(), "rate limit retry limit reached; available from Retry Failed");
                            markFailed(task.endpoint());
                        }
                        scheduleAfterResponse(apiKey);
                        return;
                    }

                    if (response.statusCode() / 100 != 2) {
                        if (!apiKey.isBlank() && (response.statusCode() == 401 || response.statusCode() == 403)) {
                            BreakBlocksAccount.rejected();
                        }
                        log(task.endpoint(), "HTTP " + response.statusCode());
                        audit(task.endpoint(), task.refreshRetries() + 1, "http_error", response.statusCode(), "BreakBlocks returned a non-success response");
                        markFailed(task.endpoint());
                        scheduleAfterResponse(apiKey);
                        return;
                    }

                    BreakBlocksAccount.observeJson(response.body());

                    if (isRefreshing(response.body())) {
                        REFRESHING_SESSION.incrementAndGet();
                        if (task.refreshRetries() < MAX_REFRESH_RETRIES) {
                            int nextAttempt = task.refreshRetries() + 1;
                            log(task.endpoint(), "refreshing; follow-up " + nextAttempt + "/" + MAX_REFRESH_RETRIES + " in 10 seconds");
                            audit(task.endpoint(), task.refreshRetries() + 1, "refreshing", response.statusCode(), "Follow-up scheduled in 10 seconds");
                            delay(task.withRefreshRetry(), TimeUnit.SECONDS.toMillis(REFRESH_DELAY_SECONDS));
                        } else {
                            log(task.endpoint(), "still refreshing after three follow-up requests; leaving it for a future scan");
                            audit(task.endpoint(), task.refreshRetries() + 1, "refresh_timeout", response.statusCode(), "Still refreshing after three follow-up requests");
                            markFailed(task.endpoint());
                        }
                        scheduleAfterResponse(apiKey);
                        return;
                    }

                    log(task.endpoint(), "accepted by BreakBlocks");
                    ACCEPTED_SESSION.incrementAndGet();
                    audit(task.endpoint(), task.refreshRetries() + 1, "accepted", response.statusCode(), "BreakBlocks returned a completed response");
                    synchronized (QUEUED) {
                        if (FAILED.remove(task.endpoint())) persistFailedLocked();
                        COOLDOWNS.put(task.endpoint(), System.currentTimeMillis() + SUCCESS_COOLDOWN_MS);
                        persistCooldownsLocked();
                    }
                    finish(task.endpoint());
                    scheduleAfterResponse(apiKey);
                }));
    }

    private static void delay(Task task, long delayMs) {
        synchronized (QUEUED) {
            DELAYED.add(task.at(System.currentTimeMillis() + Math.max(1L, delayMs)));
        }
    }

    private static void scheduleAfterResponse(String apiKey) {
        synchronized (QUEUED) {
            schedulePumpLocked(TimeUnit.SECONDS.toMillis(spacingSeconds(apiKey)));
        }
    }

    private static URI uri(String host, int port) {
        String encodedHost = URLEncoder.encode(host, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(BASE_URL + encodedHost + "/" + port);
    }

    private static boolean isRefreshing(String body) {
        try {
            Object parsed = new ServerFinderClient.JsonParser(body == null ? "" : body).parse();
            if (!(parsed instanceof Map<?, ?> top)) return false;
            if (refreshingValue(top.get("status"))) return true;
            Object data = top.get("data");
            return data instanceof Map<?, ?> nested && refreshingValue(nested.get("status"));
        } catch (RuntimeException ignored) {
            return body != null && body.toLowerCase(Locale.ROOT).contains("\"status\":\"refreshing\"");
        }
    }

    private static boolean refreshingValue(Object value) {
        return value != null && "refreshing".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static long retryAfterSeconds(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("60").trim();
        try { return Math.max(1L, Long.parseLong(value)); }
        catch (NumberFormatException ignored) { return 60L; }
    }

    private static long spacingSeconds(String apiKey) {
        return apiKey == null || apiKey.isBlank() ? ANONYMOUS_SPACING_SECONDS : AUTHENTICATED_SPACING_SECONDS;
    }

    private static void finish(String endpoint) {
        synchronized (QUEUED) {
            QUEUED.remove(endpoint);
            persistQueueLocked();
        }
    }

    private static void markFailed(String endpoint) {
        FAILED_SESSION.incrementAndGet();
        synchronized (QUEUED) {
            QUEUED.remove(endpoint);
            FAILED.add(endpoint);
            persistQueueLocked();
            persistFailedLocked();
        }
    }

    static ContributionSnapshot snapshot() {
        initialize();
        BreakBlocksAccount.configure(ToolState.breakBlocksApiKey());
        List<String> queued;
        int failedCount;
        synchronized (QUEUED) {
            queued = new ArrayList<>(QUEUED);
            failedCount = FAILED.size();
        }
        AuditTotals totals = auditTotals();
        BreakBlocksRateBudget.Snapshot quota = BreakBlocksRateBudget.snapshot(!ToolState.breakBlocksApiKey().isBlank());
        return new ContributionSnapshot(queued, currentEndpoint,
                ACCEPTED_SESSION.get(), REFRESHING_SESSION.get(), RATE_LIMITED_SESSION.get(), FAILED_SESSION.get(),
                totals.accepted(), totals.refreshing(), totals.rateLimited(), totals.failed(), failedCount, quota);
    }

    static int retryFailed() {
        initialize();
        List<Endpoint> retry = new ArrayList<>();
        synchronized (QUEUED) {
            for (String raw : FAILED) {
                Endpoint parsed = parseEndpoint(raw);
                if (parsed != null && QUEUED.add(parsed.normalized())) {
                    retry.add(parsed);
                    READY.addLast(Task.ready(parsed, 0, 0));
                }
            }
            FAILED.clear();
            persistFailedLocked();
            persistQueueLocked();
            schedulePumpLocked(0L);
        }
        for (Endpoint endpoint : retry) {
            audit(endpoint.normalized(), 0, "retry_queued", 0, "Failed contribution queued for retry");
        }
        return retry.size();
    }

    static void clearStatistics() {
        ACCEPTED_SESSION.set(0);
        REFRESHING_SESSION.set(0);
        RATE_LIMITED_SESSION.set(0);
        FAILED_SESSION.set(0);
        synchronized (BreakBlocksContributor.class) {
            try {
                Files.createDirectories(AUDIT_LOG.getParent());
                Files.writeString(AUDIT_LOG, "timestamp_utc,server,attempt,result,http_status,detail\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception error) {
                System.err.println("[Zazu's Server Seeker] Could not clear BreakBlocks contribution statistics: "
                        + error.getClass().getSimpleName());
            }
        }
    }

    static boolean openLogFolder() {
        Path folder = AUDIT_LOG.getParent().toAbsolutePath();
        try {
            Files.createDirectories(folder);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(folder.toFile());
                return true;
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            List<String> command = os.contains("win") ? List.of("explorer", folder.toString())
                    : os.contains("mac") ? List.of("open", folder.toString())
                    : List.of("xdg-open", folder.toString());
            new ProcessBuilder(command).start();
            return true;
        } catch (Exception error) {
            System.err.println("[Zazu's Server Seeker] Could not open contribution log folder: "
                    + error.getClass().getSimpleName());
            return false;
        }
    }

    private static void persistQueueLocked() {
        persistSetLocked(PENDING_FILE, QUEUED, "queue");
    }

    private static void persistFailedLocked() {
        persistSetLocked(FAILED_FILE, FAILED, "failed list");
    }

    private static void persistCooldownsLocked() {
        try {
            Files.createDirectories(COOLDOWN_FILE.getParent());
            Path temporary = COOLDOWN_FILE.resolveSibling(COOLDOWN_FILE.getFileName() + ".tmp");
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Long> entry : COOLDOWNS.entrySet()) {
                lines.add(entry.getValue() + "\t" + entry.getKey());
            }
            Files.write(temporary, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, COOLDOWN_FILE, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, COOLDOWN_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            System.err.println("[Zazu's Server Seeker] Could not save BreakBlocks contribution cooldowns: "
                    + error.getClass().getSimpleName());
        }
    }

    private static void persistSetLocked(Path destination, Set<String> values, String label) {
        try {
            Files.createDirectories(destination.getParent());
            Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
            Files.write(temporary, values, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            System.err.println("[Zazu's Server Seeker] Could not save BreakBlocks contribution " + label + ": "
                    + error.getClass().getSimpleName());
        }
    }

    private static void loadFailedLocked() throws java.io.IOException {
        if (!Files.isRegularFile(FAILED_FILE)) return;
        for (String raw : Files.readAllLines(FAILED_FILE, StandardCharsets.UTF_8)) {
            Endpoint parsed = parseEndpoint(raw);
            if (parsed != null) FAILED.add(parsed.normalized());
        }
    }

    private static void loadPrivateLanLocked() throws java.io.IOException {
        if (!Files.isRegularFile(PRIVATE_LAN_FILE)) return;
        for (String raw : Files.readAllLines(PRIVATE_LAN_FILE, StandardCharsets.UTF_8)) {
            Endpoint parsed = parseEndpoint(raw);
            if (parsed != null && isPrivateOrLan(parsed.host())) PRIVATE_LAN.add(parsed.normalized());
        }
    }

    private static void loadCooldownsLocked() throws java.io.IOException {
        if (!Files.isRegularFile(COOLDOWN_FILE)) return;
        long now = System.currentTimeMillis();
        for (String raw : Files.readAllLines(COOLDOWN_FILE, StandardCharsets.UTF_8)) {
            int separator = raw.indexOf('\t');
            if (separator < 1 || separator == raw.length() - 1) continue;
            try {
                long expiresAt = Long.parseLong(raw.substring(0, separator));
                String endpoint = ToolState.normalize(raw.substring(separator + 1));
                if (expiresAt > now && parseEndpoint(endpoint) != null) COOLDOWNS.put(endpoint, expiresAt);
            } catch (NumberFormatException ignored) {}
        }
    }

    private static void purgeCooldownsLocked() {
        long now = System.currentTimeMillis();
        if (COOLDOWNS.entrySet().removeIf(entry -> entry.getValue() <= now)) persistCooldownsLocked();
    }

    private static boolean isRecentBreakBlocksPing(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            long raw = Long.parseLong(value.trim());
            long timestamp = raw > 10_000_000_000L ? raw : raw * 1_000L;
            return timestamp >= System.currentTimeMillis() - SUCCESS_COOLDOWN_MS;
        } catch (NumberFormatException ignored) {}
        try {
            return Instant.parse(value.trim()).toEpochMilli() >= System.currentTimeMillis() - SUCCESS_COOLDOWN_MS;
        } catch (RuntimeException ignored) {}
        try {
            return OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli()
                    >= System.currentTimeMillis() - SUCCESS_COOLDOWN_MS;
        } catch (RuntimeException ignored) {}
        try {
            String normalized = value.trim().replace(' ', 'T');
            return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC).toEpochMilli()
                    >= System.currentTimeMillis() - SUCCESS_COOLDOWN_MS;
        } catch (RuntimeException ignored) { return false; }
    }

    private static synchronized AuditTotals auditTotals() {
        int accepted = 0, refreshing = 0, rateLimited = 0, failed = 0;
        try {
            if (Files.isRegularFile(AUDIT_LOG)) {
                for (String line : Files.readAllLines(AUDIT_LOG, StandardCharsets.UTF_8)) {
                    if (line.contains(",\"accepted\",")) accepted++;
                    else if (line.contains(",\"refreshing\",")) refreshing++;
                    else if (line.contains(",\"rate_limited\",")) rateLimited++;
                    else if (line.contains(",\"network_error\",") || line.contains(",\"http_error\",")
                            || line.contains(",\"refresh_timeout\",")) failed++;
                }
            }
        } catch (Exception ignored) {}
        return new AuditTotals(accepted, refreshing, rateLimited, failed);
    }

    private static Endpoint parseEndpoint(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return null;

        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 2) return null;
            String host = value.substring(1, close);
            String remainder = value.substring(close + 1);
            if (remainder.isBlank()) return endpoint(host, 25565);
            if (!remainder.startsWith(":") || remainder.length() == 1) return null;
            return endpointWithPort(host, remainder.substring(1));
        }

        int first = value.indexOf(':');
        int last = value.lastIndexOf(':');
        if (first < 0) return endpoint(value, 25565);
        if (first != last) return endpoint(value, 25565);
        if (first == 0 || first == value.length() - 1) return null;
        return endpointWithPort(value.substring(0, first), value.substring(first + 1));
    }

    private static Endpoint endpointWithPort(String host, String rawPort) {
        try {
            return endpoint(host, Integer.parseInt(rawPort));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Endpoint endpoint(String rawHost, int port) {
        String host = rawHost == null ? "" : rawHost.trim();
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isBlank() || port < 1 || port > 65535) return null;
        String formatted = host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
        return new Endpoint(host, port, ToolState.normalize(formatted));
    }

    private static boolean isPrivateOrLan(String rawHost) {
        String host = rawHost == null ? "" : rawHost.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isBlank() || host.equals("localhost") || host.endsWith(".local")
                || host.endsWith(".lan") || host.endsWith(".home")
                || (!host.contains(".") && !host.contains(":"))) return true;

        int[] ipv4 = parseIpv4(host);
        if (ipv4 != null) {
            int first = ipv4[0], second = ipv4[1];
            return first == 0 || first == 10 || first == 127 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 198 && (second == 18 || second == 19));
        }

        if (!host.contains(":")) return false;
        String compact = host.replace("%25", "%");
        int scope = compact.indexOf('%');
        if (scope >= 0) compact = compact.substring(0, scope);
        if (compact.equals("::") || compact.equals("::1")
                || compact.equals("0:0:0:0:0:0:0:1") || compact.startsWith("fc") || compact.startsWith("fd")) {
            return true;
        }
        if (compact.length() >= 3 && compact.charAt(0) == 'f' && compact.charAt(1) == 'e'
                && "89ab".indexOf(compact.charAt(2)) >= 0) return true;
        int mapped = compact.lastIndexOf(':');
        return mapped >= 0 && parseIpv4(compact.substring(mapped + 1)) != null
                && isPrivateOrLan(compact.substring(mapped + 1));
    }

    private static int[] parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] out = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isBlank()) return null;
            try {
                out[i] = Integer.parseInt(parts[i]);
                if (out[i] < 0 || out[i] > 255) return null;
            } catch (NumberFormatException ignored) { return null; }
        }
        return out;
    }

    private record Endpoint(String host, int port, String normalized) {}
    private record Task(Endpoint server, int refreshRetries, int rateRetries, long readyAtMs) {
        static Task ready(Endpoint server, int refreshRetries, int rateRetries) {
            return new Task(server, refreshRetries, rateRetries, 0L);
        }

        String endpoint() { return server.normalized(); }
        Task withRefreshRetry() { return new Task(server, refreshRetries + 1, 0, 0L); }
        Task withRateRetry() { return new Task(server, refreshRetries, rateRetries + 1, 0L); }
        Task at(long time) { return new Task(server, refreshRetries, rateRetries, time); }
    }
    private record AuditTotals(int accepted, int refreshing, int rateLimited, int failed) {}
    record ContributionSnapshot(List<String> queued, String currentEndpoint,
                                int acceptedSession, int refreshingSession, int rateLimitedSession, int failedSession,
                                int acceptedOverall, int refreshingOverall, int rateLimitedOverall, int failedOverall,
                                int failedPending, BreakBlocksRateBudget.Snapshot quota) {}

    private static String message(Throwable error) {
        if (error == null) return "no response";
        Throwable cause = Reflection.unwrap(error);
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static void log(String endpoint, String message) {
        System.out.println("[Zazu's Server Seeker] BreakBlocks contribution " + endpoint + ": " + message);
    }

    private static synchronized void audit(String endpoint, int attempt, String result, int httpStatus, String detail) {
        try {
            Files.createDirectories(AUDIT_LOG.getParent());
            boolean needsHeader = !Files.exists(AUDIT_LOG) || Files.size(AUDIT_LOG) == 0L;
            StringBuilder entry = new StringBuilder();
            if (needsHeader) entry.append("timestamp_utc,server,attempt,result,http_status,detail\n");
            entry.append(csv(Instant.now().toString())).append(',')
                    .append(csv(endpoint)).append(',')
                    .append(attempt).append(',')
                    .append(csv(result)).append(',')
                    .append(httpStatus == 0 ? "" : httpStatus).append(',')
                    .append(csv(detail)).append('\n');
            Files.writeString(AUDIT_LOG, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception error) {
            System.err.println("[Zazu's Server Seeker] Could not write BreakBlocks contribution log: "
                    + error.getClass().getSimpleName());
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
