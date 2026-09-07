package dev.zazuzin.zst;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/** Shared request allowance for BreakBlocks discovery and contribution traffic. */
final class BreakBlocksRateBudget {
    private static final long WINDOW_MS = 60_000L;
    private static final int ANONYMOUS_LIMIT = 20;
    private static final int API_KEY_LIMIT = 100;
    private static final Deque<Long> REQUESTS = new ArrayDeque<>();

    private static int reportedLimit;
    private static int reportedRemaining = -1;
    private static long reportedResetAtMs;
    private static long blockedUntilMs;
    private static Boolean reportedAuthenticated;

    private BreakBlocksRateBudget() {}

    static synchronized void recordSearchRequest(boolean authenticated) {
        recordRequest(authenticated);
    }

    static synchronized void recordContributionRequest(boolean authenticated) {
        recordRequest(authenticated);
    }

    private static void recordRequest(boolean authenticated) {
        long now = System.currentTimeMillis();
        purge(now);
        switchModeIfNeeded(authenticated);
        REQUESTS.addLast(now);
        if (reportedRemaining >= 0) reportedRemaining = Math.max(0, reportedRemaining - 1);
    }

    static synchronized long contributionDelayMillis(boolean authenticated) {
        long now = System.currentTimeMillis();
        purge(now);
        switchModeIfNeeded(authenticated);
        if (blockedUntilMs > now) return blockedUntilMs - now;

        int limit = reportedLimit > 0 ? reportedLimit : authenticated ? API_KEY_LIMIT : ANONYMOUS_LIMIT;
        int reserve = limit >= 20 ? Math.max(4, limit / 10) : 1;
        int contributionCeiling = Math.max(1, limit - reserve);

        if (reportedRemaining >= 0 && reportedResetAtMs > now && reportedRemaining <= reserve) {
            return Math.max(250L, reportedResetAtMs - now + 250L);
        }
        if (REQUESTS.size() >= contributionCeiling) {
            Long oldest = REQUESTS.peekFirst();
            return oldest == null ? WINDOW_MS : Math.max(250L, oldest + WINDOW_MS - now + 250L);
        }
        return 0L;
    }

    static synchronized void observe(HttpResponse<?> response, boolean authenticated) {
        if (response == null) return;
        long now = System.currentTimeMillis();
        switchModeIfNeeded(authenticated);
        int fallbackLimit = reportedLimit > 0
                ? reportedLimit
                : authenticated ? API_KEY_LIMIT : ANONYMOUS_LIMIT;
        reportedLimit = positiveHeader(response, "X-RateLimit-Limit", fallbackLimit);
        reportedRemaining = nonNegativeHeader(response, "X-RateLimit-Remaining", reportedRemaining);
        reportedResetAtMs = resetHeaderMillis(response, now, reportedResetAtMs);
        if (reportedResetAtMs > 0L && reportedResetAtMs <= now) {
            reportedRemaining = reportedLimit;
            reportedResetAtMs = 0L;
        }
    }

    static synchronized void noteRateLimit(HttpResponse<?> response) {
        long now = System.currentTimeMillis();
        int successfulBeforeLimit = Math.max(1, REQUESTS.size() - 1);
        int reducedLimit = Math.max(1, reportedLimit / 2);
        reportedLimit = Math.min(reducedLimit, successfulBeforeLimit);
        long retryMs = retryAfterMillis(response);
        long headerReset = resetHeaderMillis(response, now, 0L);
        blockedUntilMs = Math.max(blockedUntilMs, Math.max(now + retryMs, headerReset));
        reportedRemaining = 0;
        if (headerReset > 0L) reportedResetAtMs = headerReset;
    }

    static synchronized Snapshot snapshot(boolean authenticated) {
        long now = System.currentTimeMillis();
        purge(now);
        switchModeIfNeeded(authenticated);
        int limit = Math.max(1, reportedLimit);
        int remaining = reportedRemaining >= 0
                ? reportedRemaining
                : Math.max(0, limit - REQUESTS.size());
        long resetAt = Math.max(blockedUntilMs, reportedResetAtMs);
        if (resetAt <= now && !REQUESTS.isEmpty()) resetAt = REQUESTS.peekFirst() + WINDOW_MS;
        long resetSeconds = resetAt <= now ? 0L : Math.max(1L, (resetAt - now + 999L) / 1_000L);
        return new Snapshot(limit, remaining, resetSeconds, blockedUntilMs > now, REQUESTS.size(), authenticated);
    }

    private static void purge(long now) {
        while (!REQUESTS.isEmpty() && REQUESTS.peekFirst() <= now - WINDOW_MS) REQUESTS.removeFirst();
        if (blockedUntilMs <= now) blockedUntilMs = 0L;
        if (reportedResetAtMs > 0L && reportedResetAtMs <= now) {
            reportedRemaining = reportedLimit > 0 ? reportedLimit : -1;
            reportedResetAtMs = 0L;
        }
    }

    private static void switchModeIfNeeded(boolean authenticated) {
        if (reportedAuthenticated != null && reportedAuthenticated == authenticated) return;
        reportedAuthenticated = authenticated;
        reportedLimit = authenticated ? API_KEY_LIMIT : ANONYMOUS_LIMIT;
        reportedRemaining = -1;
        reportedResetAtMs = 0L;
    }

    private static int positiveHeader(HttpResponse<?> response, String name, int fallback) {
        try {
            int value = Integer.parseInt(response.headers().firstValue(name).orElse("").trim());
            return value > 0 ? value : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int nonNegativeHeader(HttpResponse<?> response, String name, int fallback) {
        try {
            int value = Integer.parseInt(response.headers().firstValue(name).orElse("").trim());
            return Math.max(0, value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long resetHeaderMillis(HttpResponse<?> response, long now, long fallback) {
        try {
            long raw = Long.parseLong(response.headers().firstValue("X-RateLimit-Reset").orElse("").trim());
            if (raw <= 0L) return fallback;
            // BreakBlocks documents this as a Unix timestamp. Accept a relative
            // number as a compatibility fallback if the server ever changes it.
            long epochMs = raw > Instant.now().getEpochSecond() / 2L ? raw * 1_000L : now + raw * 1_000L;
            return Math.max(now, epochMs);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long retryAfterMillis(HttpResponse<?> response) {
        try {
            long seconds = Long.parseLong(response.headers().firstValue("Retry-After").orElse("60").trim());
            return Math.max(1_000L, seconds * 1_000L);
        } catch (RuntimeException ignored) {
            return WINDOW_MS;
        }
    }

    record Snapshot(int limit, int remaining, long resetSeconds, boolean paused,
                    int requestsThisWindow, boolean authenticated) {}
}
