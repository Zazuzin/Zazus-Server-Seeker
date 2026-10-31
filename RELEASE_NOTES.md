# Zazu's Server Seeker 1.2.2

Choose the JAR matching your Minecraft version: **26.2** or **26.3**. Both require Java 25, Fabric Loader and Fabric API for the matching Minecraft version. Replace the older Server Seeker JAR in your instance's `mods` folder.

## Changes

- **Improved navigation:** Back and Escape return Seeker to the Multiplayer menu or category you opened it from. Blocked Servers is now in Settings, the BreakBlocks shortcut is larger, and the added-server message is clearer.
- **Faster verified results:** each candidate is checked independently and appears as soon as it passes two status checks at least one second apart. Slow candidates continue in the background. Sorting, pagination and Auto Add limits remain available.
- **MineScan support:** discovery prioritises BreakBlocks, then MineScan, then Cornbread. MineScan has a shared request allowance and rate-limit backoff.
- **Detailed filters:** save country, region, MOTD and modpack filters for BreakBlocks discovery and browsing.
- **Ping player history:** Server Notes can record names supplied in server status replies, separately from connected encounters. These samples are historical reports, not complete or verified live player lists. Recording can be disabled in Settings.
- **VIP player search:** search recorded player names or UUIDs and explicitly add associated servers. This requires a BreakBlocks API key confirmed as VIP Treatment; other accounts see the membership notice and Patreon link.
- **In-game API key settings:** edit, hide, save, clear and test your shared BreakBlocks API key from Settings. A Multiplayer Settings shortcut opens preferences directly.

Existing saved servers, settings and schema-v2 Server Notes remain compatible. The code was tested privately as rc.8 before promotion to this release. Both Minecraft builds pass the complete build, native API, navigation, provider, streaming, VIP-access, persistence and compatibility checks before publication.

The complete source ZIP contains both Minecraft versions. SHA-256 checksums are included for both JARs and the source archive.
