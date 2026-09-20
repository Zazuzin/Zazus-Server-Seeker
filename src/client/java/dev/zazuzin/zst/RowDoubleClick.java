package dev.zazuzin.zst;

/** Pure same-row double-click timing shared by the Minecraft entry mixin. */
public final class RowDoubleClick {
    public static final long WINDOW_NANOS = 500_000_000L;

    private RowDoubleClick() {}

    public static boolean isSecondClick(String previousEndpoint, long previousAtNanos,
                                 String endpoint, long nowNanos) {
        long elapsed = nowNanos - previousAtNanos;
        return previousAtNanos != 0L && endpoint != null && !endpoint.isBlank()
                && endpoint.equals(previousEndpoint)
                && elapsed >= 0L && elapsed <= WINDOW_NANOS;
    }
}
