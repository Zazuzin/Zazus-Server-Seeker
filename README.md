# Zazu's Server Tool v0.3.63

## v0.3.63 — Public release preparation

- Removes generated local configuration and server-history data from the distributable source.
- Ignores the runtime `config/` directory to prevent accidental API-key or endpoint disclosure.
- Adds public privacy, security, contributing, support and release documentation.
- Adds a Java 21 GitHub Actions build that runs verification and publishes the verified JAR as an artifact.
- Makes `gradle.properties` the single source of truth for build and verification version numbers.
- Updates the Finder HTTP user agent to the current mod version.

## v0.3.62

- Fixes the in-game **Edit Server Info** button for Minecraft 26.2's relocated `EditServerScreen`.
- Preserves Favourite, Scanned, health, and recent-server metadata when an edited server address changes.

## v0.3.61

- Restricts **Undo Last Delete** to the Servers and Scanned Servers tabs.
- Hides the control on Categories, Favourites, Recent Servers, and while the Finder is open.

## v0.3.60

- Removes the Protected Server feature and its row control.
- Fixes **Undo Last Delete** clicks by routing them through the Multiplayer mouse interceptor.
- Fixes health reset buttons using the same explicit click path as Favourite and Delete.
- Keeps automatic backups, Undo, persistent health history and all favourite protections.

## v0.3.59

- Creates rotating `servers.dat` snapshots before deletion and keeps the latest 10 backups.
- Adds **Undo Last Delete**, restoring the server name, address, category, favourite and protection state.
- Adds persistent **Protected Server** controls; protected entries cannot be removed automatically, in bulk, or with quick delete.
- Persists health failure counts and last success/failure timestamps per address.
- Adds compact row health status and a health-counter reset control.

## v0.3.58

- Adds **Edit Server Info** to the multiplayer pause menu using Minecraft's normal server editor.
- Direct Connect servers are saved before editing; the currently connected address is preserved.
- Favourite identity is persisted by server address, so renaming a favourite no longer moves it to Servers.
- Existing starred favourites migrate automatically to the new persistent identity.
- Gives Minecraft's initial visible-row status checks a 20-second head start before Scanned health cleanup begins.
- Reuses recent successful status results to avoid redundant background health probes.

## v0.3.57

- Favourites are now protected from whitelist auto-delete.
- Failed-health cleanup rechecks the live and persisted favourite state immediately before removal.
- The shared automatic-removal primitive refuses to remove favourite entries as a final safety boundary.
- Auto Join is now available in both **Servers** and **Scanned Servers**.
- Auto Join always excludes **Favourites**, and remembers which eligible category started the pass.


## v0.3.56

- Adds a **Favourite Server** control to the in-game pause menu while connected to a multiplayer server.
- The button shows **☆ Favourite Server** or **★ Unfavourite Server** based on the current saved-server state.
- Toggling updates Minecraft's normal `servers.dat`, so the Favourites category reflects the change immediately the next time Multiplayer is opened.
- If the current server was joined through Direct Connect and is not saved yet, favouriting it automatically saves it first.
- The pause-menu control is multiplayer-only and is not shown in singleplayer.

## v0.3.55

- Fixed whitelist auto-delete for entries joined from the **Servers** tab.
- On a definite whitelist rejection, the mod now removes the endpoint from the live JoinMultiplayerScreen ServerList **before** returning to Multiplayer, then verifies persistence through the saved server list.
- This prevents the still-open Multiplayer screen from re-saving a stale copy of the deleted server and making it appear again.
- The visible category backing rows are removed immediately as well.
- Deleted whitelist servers are also cleared from Recent Servers history.

## v0.3.54
- Adds a **Recent Servers** category showing the last 5 unique servers that reached a stable successful join.
- A join is recorded only after remaining in PLAY for 8 seconds, so failed/instant-disconnect attempts are not added.
- Rejoining a server moves it back to the top instead of creating a duplicate.
- Recent history persists in `config/zazus-server-tabs.properties`.

# Zazu's Server Tool

**Zazu's Server Tool** is a client-side Fabric mod for **Minecraft Java Edition 26.2** that makes discovering, organising and testing multiplayer servers easier from inside Minecraft.

The mod adds a multi-source server Finder, separates saved servers into useful categories, provides favourites and management controls, and includes sequential Auto Join for newly scanned servers. It is a standalone Fabric mod and does **not** require Meteor Client.

Source code and releases: [github.com/Zazuzin/Zazus-Server-Scanner](https://github.com/Zazuzin/Zazus-Server-Scanner)  
Bug reports and feature requests: [GitHub Issues](https://github.com/Zazuzin/Zazus-Server-Scanner/issues)

## What the mod does

Opening Minecraft's **Multiplayer** screen gives you a category hub with:

- **Favourites** — servers you have marked as favourites.
- **Servers** — manually added/existing servers and scanned servers that have been verified by a stable successful join.
- **Scanned Servers** — servers added through the Finder that have not yet been verified.
- **Recent Servers** — the last 5 unique saved servers that reached a stable successful join, newest first.
- **Zazu's Server Tool / Finder** — discovers servers from supported public server databases, verifies them directly with the Minecraft status protocol, and lets you inspect or add them.

The categories are views over Minecraft's normal `servers.dat`; the mod stores classification metadata separately rather than creating duplicate server databases.

## Key features

- **Multi-provider server discovery** with BreakBlocks, Cornbread and MineScan.
- **Auto source mode** that prefers BreakBlocks and falls back to another provider if it is unavailable, rate-limited or exhausted.
- **All Sources mode** that rotates between available providers and merges results.
- **Direct source selection** for BreakBlocks, Cornbread or MineScan.
- **Cross-provider de-duplication** by normalized `IP:port`.
- **Two-pass direct Minecraft status verification** before Finder results are displayed or Auto Added.
- **Optional BreakBlocks API key support**; Cornbread and MineScan do not require a key.
- **Configurable BreakBlocks recency window**: 1, 7, 14, 21 or 30 days (default 30).
- **Version**, **minimum/maximum player**, and **Any / Premium / Cracked** filtering.
- **Sorting options** for Finder results.
- **Add Server** and **View Details** controls, including the discovery source.
- **Continuous Auto Add** until stopped or the configured limit is reached.
- **Added-history tracking** so previously added servers can be skipped.
- **Favourites** with a dedicated category and an in-game pause-menu Favourite/Unfavourite control.
- **Recent Servers** history for the last 5 successful stable joins.
- **Per-server Delete** controls and **Delete Non-Favourites** for established Servers.
- **Whitelist cleanup** — definite whitelist rejections are automatically removed.
- **Sequential Auto Join** in Servers and Scanned Servers, always excluding Favourites.
- Ordinary Auto Join connection failures continue to the next eligible scanned server.
- A scanned server that stays connected for roughly eight seconds is automatically promoted to **Servers**.
- **ViaFabricPlus integration** when ViaFabricPlus is installed.
- **Finder latency display**, diagnostics, settings and statistics.

## Finder sources

The Finder source is selected from **Settings → Finder Source**.

- **Auto** — default. Tries BreakBlocks first; if that provider is unavailable, rate-limited, or its result window is exhausted, the same search continues with Cornbread and then MineScan.
- **All Sources** — rotates between all available providers, merges results, and removes duplicate endpoints.
- **BreakBlocks** — use only BreakBlocks.
- **Cornbread** — use only Cornbread's public random-server API.
- **MineScan** — use only MineScan's public random-server API.

For BreakBlocks, **Settings → BreakBlocks Age** controls how recently a server must have been pinged by BreakBlocks. Available values are **1, 7, 14, 21 and 30 days**, with **30 days** as the default.

Provider data is treated as discovery input rather than proof that a server is currently usable. Zazu's Server Tool requires two successful bounded pure-Java Minecraft status requests, roughly one second apart, before showing or Auto Adding a candidate. Known provider-reported whitelisted servers are skipped before that verification.

External providers have their own availability and rate limits. A provider outage therefore does not necessarily make the Finder unavailable when **Auto** or **All Sources** is selected.

## Requirements

- **Minecraft Java Edition 26.2**
- **Fabric Loader 0.19.3** or a newer compatible release
- **Fabric API** — tested target: `0.157.0+26.2`
- A Java runtime suitable for Minecraft 26.2
- **ViaFabricPlus 4.6.1+** is optional

## Installation

1. Install **Fabric Loader** for Minecraft 26.2.
2. Install the matching **Fabric API**.
3. Build the JAR from this repository.
4. Put the JAR in the Minecraft instance's `mods` folder.
5. Remove any older Zazu's Server Tool JAR so only one version is installed.
6. Start Minecraft using the Fabric profile / Fabric-enabled launcher instance.
7. Open **Multiplayer** to access the category hub and Finder.

Example:

```text
mods/
├── fabric-api-0.157.0+26.2.jar
├── Zazus-Server-Tool-0.3.63+mc26.2.jar
└── ViaFabricPlus-4.6.1.jar        # optional
```

## BreakBlocks API key (optional)

A BreakBlocks API key is **not required**, and it is used only by the BreakBlocks provider. Cornbread and MineScan require no key in this implementation.

To use your own BreakBlocks key, run the mod once and open:

```text
config/zazus-server-tool.properties
```

Set:

```properties
breakBlocksApiKey=YOUR_API_KEY
```

The key is sent only in the HTTP `Authorization: Bearer ...` header. It is not bundled in the mod, placed in request URLs, or written to Zazu's Server Tool log messages.

If BreakBlocks rejects a configured key with HTTP 401/403, its request is retried anonymously. If BreakBlocks is unavailable or rate-limited while **Auto** or **All Sources** is selected, the Finder can continue with another provider.

**Do not commit or share your API key.**

## Using the categories

### Favourites

Favourited servers appear only in this category. Unfavouriting from Favourites moves the server into the established Servers category.

### Servers

Contains existing/manually added servers and scanned servers that have been verified by a stable join.

### Scanned Servers

Finder-added servers begin here. **Auto Join** is available here and in Servers; Favourites are always excluded:

1. It attempts the first eligible scanned server.
2. An ordinary connection failure moves to the next server.
3. A definite whitelist rejection deletes that server and continues.
4. Pressing the real connection-screen Cancel button stops the run.
5. A stable successful connection stops Auto Join and promotes that server to Servers.

## Building from source

The repository contains the complete readable source and does not require a prebuilt Zazu's Server Tool JAR.

For the verified dependency-free release build, JDK 21+ is sufficient:

```bash
./build.sh
./verify.sh
```

Output:

```text
build/libs/Zazus-Server-Tool-0.3.63+mc26.2.jar
```

The release classes intentionally target Java 21 bytecode. Minecraft 26.2 itself uses a newer Java runtime.

The repository also includes Fabric Loom/Gradle files for IDE and development use. With a compatible Gradle installation and Java 25:

```bash
gradle build
```

## v0.3.52

- Restores **strict live verification** for Finder results: provider records are candidates only.
- Requires **two successful Minecraft Java status handshakes** roughly one second apart before a server can be displayed, manually added, or Auto Added.
- Finder rows now use **VERIFIED** instead of FOUND for admitted results.
- Adds live verification/rejection diagnostics to Finder status text.
- Adds **Scanned Servers health cleanup** while the Scanned Servers tab is open.
- A non-favourite scanned server is automatically removed only after **3 consecutive failed direct status checks**.
- Any successful status reply immediately resets that server's failure streak.
- Internal/local probe errors are ignored for auto-delete decisions.
- Existing **Delete All Scanned** remains confirmation-protected and unchanged.

## v0.3.51

- Adds a confirmation-protected **Delete All Scanned** button to the Scanned Servers tab.
- Deletes only non-favourite scanned entries; favourites and established Servers entries are preserved.
- Stops using the Servers-tab bulk-delete mode when the user changes category.

## v0.3.50

This release restores provider-authoritative server discovery after mandatory direct status probes caused valid search results to disappear on networks where those probes received no replies.

- Provider records that pass version, player-count, type, block-list, history, and saved-server filters are shown immediately.
- Manual Add and Auto Add no longer require a second direct status connection to succeed.
- Direct status checks remain bounded and supplementary, adding latency and a `LIVE` label when available.
- Results awaiting or failing that optional check are labelled `FOUND`, rather than being discarded.
- The pure-Java status client and its concurrency/time-out protections remain unchanged.

## v0.3.49

This release removes Finder from Minecraft/ViaFabricPlus's native status-pinger path after v0.3.46–v0.3.48 could still hard-exit the process without a Java exception.

- Finder verification now uses a bounded four-thread, daemon-only pure-Java status client.
- Finder no longer accesses `ServerStatusPinger`, Netty event loops, native transports, or ViaFabricPlus status-pinger mixins.
- The status client implements the normal Java handshake, status response, and optional ping/pong packets directly.
- Connect, read, and pong timeouts are bounded; DNS, timeout, unreachable, malformed-response, and internal failures remain diagnostic results rather than client exceptions.
- One global queue caps both batch verification and row-latency checks, preventing separate Finder paths from multiplying network concurrency.
- Provider support, deterministic Auto Add, age filters, categories, Auto Join, favourites, whitelist deletion, and the working Multiplayer UI are unchanged.

## v0.3.48

This release hardens the screen-owned vanilla status probe after v0.3.47 could still terminate the client while Finder was searching.

- Finder now limits vanilla status requests to **four in flight** instead of submitting an entire provider batch at once.
- Every queued probe receives its own five-second timeout when it starts.
- Probe launches, state reads, and callbacks stay on Minecraft's client thread.
- The Finder session and exact screen-owned pinger are revalidated before every launch, poll, and callback.
- Closing Finder or replacing/reinitializing the Multiplayer screen safely invalidates pending work and ignores stale callbacks.
- Finder never calls `ServerStatusPinger.tick()` or `removeAll()`; Minecraft's Multiplayer screen remains the sole lifecycle owner.
- Normal integration/reflection failures become Finder diagnostics rather than client crashes, while fatal JVM errors are not swallowed.
- Provider support, deterministic Auto Add, age filters, categories, Auto Join, favourites, whitelist deletion, and the working Multiplayer UI are unchanged.

## v0.3.46

This release replaces the Finder's custom raw-socket status client with Minecraft 26.2's own multiplayer status-ping path while preserving the working v0.3.45 UI and Auto Add flow.

- Finder verification now runs through Minecraft's `ServerStatusPinger` and `EventLoopGroupHolder.remote(...)`, using the same resolver/network stack as the Multiplayer screen.
- The probe honors Minecraft's `useNativeTransport` option and allows Minecraft/ViaFabricPlus networking hooks to participate instead of bypassing them with a separate socket implementation.
- Minecraft's current protocol is read dynamically from `SharedConstants`, with protocol 776 retained only as a 26.2 fallback.
- BreakBlocks diagnostics now distinguish **API / tried / replies / live**, plus DNS, network/unreachable, timeout, internal probe-error, and incompatible-version counts.
- Finder row latency now reuses the same vanilla pinger and its short-lived successful-latency cache.
- The old `MinecraftStatusProbe` raw-socket implementation has been removed.
- Provider selection, BreakBlocks age choices, optional API-key behavior, deterministic Auto Add, Multiplayer categories, Auto Join, favourites, whitelist deletion, and Minecraft's native footer controls are otherwise unchanged.

## v0.3.45

This release makes **Auto Add deterministic and Finder-owned** instead of depending on a separate Multiplayer-screen tick hook.

- Enabling Auto Add now starts a Finder search immediately when the Finder is idle.
- After a completed non-exhausted batch, the Finder explicitly schedules the next search after **2 seconds**.
- When the provider pool is exhausted, Auto Add waits **60 seconds**, resets the search pool, and starts again.
- A terminal provider failure while Auto Add is enabled is retried after **15 seconds** with a fresh provider pool.
- Delayed searches use a generation token so turning Auto Add off, closing the Finder, or starting another search invalidates stale scheduled callbacks.
- Re-enabling Auto Add starts a fresh Auto Add session counter for the configured 10 / 25 / 50 / Unlimited limit.
- The old `ContinuousAutoAddEntrypoint` and its screen-tick dependency have been removed.
- BreakBlocks age settings, multi-provider fallback, and the working Multiplayer/category UI are unchanged.

## v0.3.44

This release fixes the Finder's direct Minecraft live-status verification for Minecraft Java 26.2 while preserving the working Multiplayer/category UI.

- Sends Minecraft Java **26.2 protocol 776** in direct status handshakes instead of protocol 0.
- Uses protocol 776 as an authoritative 26.2 match when a proxy returns a customized version-name string.
- Expands BreakBlocks diagnostics to show **API results / probed / status replies / live matches**.
- Keeps the BreakBlocks age choices at **1, 7, 14, 21, 30 days**, with 30 days as the default.
- Keeps Cornbread and MineScan fallback/source modes unchanged.
- Does not alter Minecraft's Multiplayer footer, categories, Auto Join, favourites, or whitelist handling.

## v0.3.43

This release fixes BreakBlocks pagination/result narrowing while preserving the working v0.3.41 Multiplayer/category UI and the v0.3.42 provider system.

- Fixes BreakBlocks pagination to start at **page 1** instead of page 0.
- Adds **BreakBlocks Age** settings: **1, 7, 14, 21, 30 days**.
- Changes the default BreakBlocks age window from 1 day to **30 days**.
- Shows BreakBlocks page/API-result/live-verification diagnostics in Finder status.
- Keeps Cornbread and MineScan fallback/source modes unchanged.
- Leaves Minecraft's native Multiplayer Back/Refresh/footer controls under Minecraft's ownership.

## Project identity

- **Mod:** Zazu's Server Tool
- **Mod ID:** `zazus-server-tool`
- **Package:** `dev.zazuzin.zst`
- **Author:** Zazuzin
- **Current source version:** 0.3.63

## v0.3.53 Auto Join rate-limit cooldown

Auto Join recognises authentication/session failures such as `RateLimiter disallowed request` as a special condition. It returns to Multiplayer, waits **10 seconds by default**, then continues with the next scanned server. The server that triggered the rate limit is not deleted.

The generated `config/zazus-server-tool-autojoin.properties` preserves an optional `rateLimitCooldownSeconds` setting. Its default is `10`; values are clamped to 1–300 seconds.
