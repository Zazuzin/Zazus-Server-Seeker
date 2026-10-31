package dev.zazuzin.zst;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic tests of the production request budget; no API traffic. */
public final class MineScanRateBudgetTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        AtomicLong now = new AtomicLong(100_000_000L);
        MineScanRateBudget minute = new MineScanRateBudget(now::get);
        for (int i = 0; i < 60; i++) check(minute.tryReserveRequest(), "Minute budget stopped too early");
        check(!minute.tryReserveRequest() && minute.retryDelayMillis() == 60_000L, "61st minute request was allowed");
        now.addAndGet(59_999L);
        check(!minute.tryReserveRequest() && minute.retryDelayMillis() == 1L, "Minute budget reset early");
        now.incrementAndGet(); check(minute.tryReserveRequest(), "Minute allowance did not recover");

        long start = now.get();
        MineScanRateBudget hour = new MineScanRateBudget(now::get);
        for (int i = 0; i < 1_000; i++) {
            now.set(start + i * 3_000L);
            check(hour.tryReserveRequest(), "Hour budget stopped before 1,000 spaced requests");
        }
        check(!hour.tryReserveRequest() && hour.retryDelayMillis() > 60_000L, "1,001st hourly request was allowed");
        now.set(start + 3_600_000L); check(hour.tryReserveRequest(), "Hourly allowance did not recover");

        for (String header : new String[]{"", "invalid", "-5"}) {
            MineScanRateBudget limited = new MineScanRateBudget(now::get);
            limited.noteRateLimit(header);
            check(!limited.tryReserveRequest() && limited.retryDelayMillis() == 60_000L, "Missing/invalid Retry-After did not pause requests");
        }
        MineScanRateBudget seconds = new MineScanRateBudget(now::get);
        seconds.noteRateLimit("120"); seconds.noteRateLimit("15");
        check(seconds.retryDelayMillis() == 120_000L, "A second rate limit shortened an existing pause");
        now.addAndGet(120_000L); check(seconds.tryReserveRequest(), "Retry-After seconds did not expire");
        MineScanRateBudget date = new MineScanRateBudget(now::get);
        date.noteRateLimit(DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(now.get() + 90_000L).atZone(ZoneOffset.UTC)));
        check(date.retryDelayMillis() == 90_000L, "HTTP-date Retry-After was ignored");
        MineScanRateBudget concurrent = new MineScanRateBudget(now::get);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 200; i++) tasks.add(concurrent::tryReserveRequest);
            int accepted = 0;
            for (Future<Boolean> f : workers.invokeAll(tasks)) if (f.get()) accepted++;
            check(accepted == 60, "Concurrent reservations exceeded the minute cap");
        } finally { workers.shutdownNow(); }
        System.out.println("MineScan minute/hour allowance, expiry, Retry-After and concurrent reservation checks passed.");
    }
}
