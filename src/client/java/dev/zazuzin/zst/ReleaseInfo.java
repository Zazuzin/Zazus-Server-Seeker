package dev.zazuzin.zst;

/** Release identity shared by UI and network request metadata. */
final class ReleaseInfo {
    static final String VERSION = "1.0.0";
    static final String MINECRAFT_VERSION = "26.2";
    static final String USER_AGENT = "ZazusServerSeeker/" + VERSION;

    private ReleaseInfo() {}
}
