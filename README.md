# Zazu's Server Tool

**Zazu's Server Tool** is a client-side Fabric mod for **Minecraft Java Edition 26.2** that makes discovering, organising and testing multiplayer servers easier from inside Minecraft.

It adds a BreakBlocks-powered server Finder, separates saved servers into useful categories, provides favourites and management controls, and includes a sequential Auto Join system for newly scanned servers. It is a standalone Fabric mod and does **not** require Meteor Client.

## What the mod does

Opening Minecraft's **Multiplayer** screen gives you a category hub with:

- **Favourites** — servers you have marked as favourites.
- **Servers** — manually added/existing servers and scanned servers that have been verified by a stable successful join.
- **Scanned Servers** — servers added through the Finder that have not yet been verified.
- **Zazu's Server Tool / Finder** — searches BreakBlocks for servers matching your filters and lets you inspect or add them directly.

The categories are views over Minecraft's normal `servers.dat`; the mod stores only classification metadata separately rather than creating duplicate server databases.

## Key features

- **BreakBlocks server discovery** inside Minecraft.
- **Optional BreakBlocks API key support** for higher limits and any additional access provided by your BreakBlocks account/tier.
- **Works without an API key** using anonymous BreakBlocks access.
- **Live server verification** before Finder results are shown.
- **Version filtering**.
- **Minimum and maximum player filters**.
- **Any / Premium / Cracked server filtering**.
- **Sorting options** for Finder results.
- **Add Server** and **View Details** controls.
- **Continuous Auto Add** until stopped or the configured limit is reached.
- **Added-history tracking** so previously added servers can be skipped.
- **Favourites** with a dedicated category.
- **Per-server Delete** controls.
- **Delete Non-Favourites** for established Servers.
- **Whitelist cleanup** — definite whitelist rejections are automatically removed.
- **Sequential Auto Join** available only in Scanned Servers.
- Ordinary Auto Join connection failures continue to the next eligible scanned server.
- A scanned server that stays connected for roughly eight seconds is automatically promoted to **Servers**.
- **ViaFabricPlus integration** when ViaFabricPlus is installed.
- **Finder latency display**, diagnostics, settings and statistics.

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
├── Zazus-Server-Tool-0.3.34+mc26.2.jar
└── ViaFabricPlus-4.6.1.jar        # optional
```

## BreakBlocks API key (optional)

An API key is **not required**. Without one, the Finder continues to use BreakBlocks anonymously.

To use your own key, run the mod once and open:

```text
config/zazus-server-tool.properties
```

Set:

```properties
breakBlocksApiKey=YOUR_API_KEY
```

The key is sent only in the HTTP `Authorization: Bearer ...` header. It is not bundled in the mod, placed in request URLs, or written to Zazu's Server Tool log messages.

If BreakBlocks rejects a configured key with HTTP 401/403, the Finder retries anonymously so the mod remains usable. HTTP 429 responses respect `Retry-After` when supplied.

**Do not commit or share your API key.**

## Using the categories

### Favourites

Favourited servers appear only in this category. Unfavouriting returns the server to its retained Servers or Scanned Servers classification.

### Servers

Contains existing/manually added servers and scanned servers that have been verified by a stable join.

### Scanned Servers

Finder-added servers begin here. **Auto Join** is available only in this category:

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
build/libs/Zazus-Server-Tool-0.3.34+mc26.2.jar
```

The release classes intentionally target Java 21 bytecode. Minecraft 26.2 itself uses a newer Java runtime.

The repository also includes Fabric Loom/Gradle files for IDE and development use. With a compatible Gradle installation and Java 25:

```bash
gradle build
```

## v0.3.34 fixes

This version focuses on repairing the Multiplayer UI/runtime regressions found during testing:

- Restores category server rows using Minecraft's own in-memory `ServerSelectionList.updateOnlineServers(...)` path instead of mutating fragile internal row lists.
- Keeps category switching free of `servers.dat` reloads and synchronous network work.
- Fixes Minecraft 26.2 current-screen detection used by Auto Join and row-control updates.
- Fixes Auto Join failing to start from Scanned Servers.
- Keeps Refresh in the current category rather than dropping back into the category hub when the Multiplayer screen reinitialises.
- Removes stale duplicate controls on Multiplayer reinitialisation.
- Prevents the custom Back button from overlapping Minecraft's own Back control.
- Positions Favourite/Delete controls using each row's actual layout Y coordinate so they stay aligned while scrolling.
- Moves Favourite/Delete controls outside the server row content when space allows so player count and ping remain visible.

## Project identity

- **Mod:** Zazu's Server Tool
- **Mod ID:** `zazus-server-tool`
- **Package:** `dev.zazuzin.zst`
- **Author:** Zazuzin
- **Current source version:** 0.3.34
