package dev.zazuzin.zst;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/** MineScan's documented per-IP minute/hour limits, shared by Finder sessions. */
final class MineScanRateBudget {
    static final MineScanRateBudget SHARED = new MineScanRateBudget(System::currentTimeMillis);
    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 3_600_000L;
    private static final int MINUTE_LIMIT = 60;
    private static final int HOUR_LIMIT = 1_000;
    private final LongSupplier clock;
    private final Deque<Long> requests = new ArrayDeque<>();
    private long blockedUntil;

    MineScanRateBudget(LongSupplier clock) { this.clock = clock; }

    synchronized boolean tryReserveRequest() {
        long now = clock.getAsLong();
        if (delayMillis(now) > 0L) return false;
        requests.addLast(now);
        return true;
    }

    synchronized long retryDelayMillis() { return delayMillis(clock.getAsLong()); }

    synchronized void noteRateLimit(String retryAfter) {
        long now = clock.getAsLong();
        long wait = MINUTE_MS; // MineScan does not promise a Retry-After header.
        if (retryAfter != null && !retryAfter.isBlank()) {
            String value = retryAfter.trim();
            try {
                long seconds = Long.parseLong(value);
                if (seconds >= 0L) wait = Math.min(seconds, 86_400L) * 1_000L;
            } catch (NumberFormatException ignored) {
                try {
                    wait = Math.max(0L, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli() - now);
                } catch (RuntimeException invalidDate) { /* Keep the one-minute fallback. */ }
            }
        }
        blockedUntil = Math.max(blockedUntil, now + Math.max(1_000L, wait));
    }

    private long delayMillis(long now) {
        while (!requests.isEmpty() && requests.peekFirst() <= now - HOUR_MS) requests.removeFirst();
        long delay = Math.max(0L, blockedUntil - now);
        if (requests.size() >= HOUR_LIMIT) delay = Math.max(delay, requests.peekFirst() + HOUR_MS - now);
        int inMinute = 0;
        long oldestInMinute = now;
        for (var it = requests.descendingIterator(); it.hasNext();) {
            long at = it.next();
            if (at <= now - MINUTE_MS) break;
            inMinute++;
            oldestInMinute = at;
        }
        if (inMinute >= MINUTE_LIMIT) delay = Math.max(delay, oldestInMinute + MINUTE_MS - now);
        return delay;
    }
}
