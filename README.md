# Zazu's Server Tool

**Zazu's Server Tool** is a client-side Fabric mod for **Minecraft Java Edition 26.2** that makes discovering, organising and testing multiplayer servers easier from inside Minecraft.

The mod adds a server Finder powered by the BreakBlocks API, separates saved servers into useful categories, provides favourites and management tools, and includes an optional sequential Auto Join system for testing newly scanned servers. It is designed to work as a normal Fabric mod and does not require Meteor Client.

## What the mod does

Zazu's Server Tool expands Minecraft's Multiplayer screen with a category-based workflow:

- **Favourites** — servers you have marked as favourites.
- **Servers** — manual/existing servers and scanned servers that have been successfully verified by joining them.
- **Scanned Servers** — servers added through the Finder that have not yet been verified by a stable join.
- **Zazu's Server Tool / Finder** — searches BreakBlocks for servers matching your filters and lets you inspect or add them directly.

All three categories use Minecraft's normal `servers.dat` server list. The mod stores classification metadata separately instead of creating duplicate server databases.

## Key features

- **BreakBlocks server discovery** directly inside Minecraft.
- **Optional BreakBlocks API key support** for higher limits and any additional access provided by your BreakBlocks account/tier.
- **Works without an API key** using normal anonymous BreakBlocks access.
- **Live server verification** before Finder results are presented.
- **Version filtering** for supported Minecraft versions.
- **Minimum and maximum player filters**.
- **Server type filtering** for Any, Premium and Cracked servers.
- **Sorting options** for Finder results.
- **Add Server** and **View Details** controls for discovered servers.
- **Continuous Auto Add** for repeatedly finding and adding new servers until stopped or the configured limit is reached.
- **Added-history tracking** so previously added servers can be skipped automatically.
- **Favourites system** with favourites kept in their own category.
- **Per-server Delete** controls.
- **Delete Non-Favourites** management option.
- **Whitelist cleanup** — definite whitelist rejections are automatically removed from the saved server list.
- **Sequential Auto Join** available only in Scanned Servers.
- Auto Join continues through ordinary connection failures and stops on a successful stable join.
- A scanned server that joins successfully and remains in-game for roughly eight seconds is automatically promoted to **Servers**.
- A real Connect-screen **Cancel** stops an Auto Join run.
- **ViaFabricPlus integration** for connecting to servers using different protocol versions when ViaFabricPlus is installed.
- **Finder latency display**, diagnostics, settings and statistics.
- **Title-screen credit** for Zazu's Server Tool without adding an extra title-screen button.

## Requirements

- **Minecraft Java Edition 26.2**
- **Fabric Loader 0.19.3** or newer compatible version
- **Fabric API** — tested with `0.157.0+26.2`
- A Java runtime suitable for Minecraft 26.2
- **ViaFabricPlus 4.6.1+** is optional

## Installation

1. Install **Fabric Loader** for Minecraft 26.2.
2. Install the matching **Fabric API**.
3. Download the Zazu's Server Tool release JAR, or build the JAR from this repository.
4. Place the JAR in your Minecraft instance's `mods` folder.
5. Make sure older versions of Zazu's Server Tool are removed so only one version is installed.
6. Start Minecraft with the Fabric profile / Fabric-enabled Prism Launcher instance.
7. Open **Multiplayer** to access the Zazu's Server Tool category screen and Finder.

Example mods folder contents:

```text
mods/
├── fabric-api-0.157.0+26.2.jar
├── Zazus-Server-Tool-0.3.33+mc26.2.jar
└── ViaFabricPlus-4.6.1.jar        # optional
```

If you use **Prism Launcher**, open the instance, select **Edit → Mods**, then add the Zazu's Server Tool JAR and Fabric API there.

## BreakBlocks API key (optional)

An API key is **not required**. If no key is configured, the Finder continues to use BreakBlocks anonymously.

To use your own BreakBlocks API key, run the mod once and then open:

```text
config/zazus-server-tool.properties
```

Set:

```properties
breakBlocksApiKey=YOUR_API_KEY
```

When a key is configured, Zazu's Server Tool sends it only in the HTTP `Authorization: Bearer ...` header. The key is not bundled in the mod, placed in request URLs, or written to Zazu's Server Tool log messages.

If BreakBlocks rejects the key with HTTP 401/403, the Finder retries anonymously so the mod remains usable. HTTP 429 responses also respect BreakBlocks' `Retry-After` value when available.

**Do not commit or share your API key.**

## Using the server categories

### Favourites

Any server marked as a favourite appears only in the Favourites category. Unfavouriting it returns it to its original Servers or Scanned Servers classification.

### Servers

This category contains normal manually added/existing servers and scanned servers that have been verified through a stable successful join.

### Scanned Servers

Servers added by the Finder begin here. They remain Scanned until successfully verified.

**Auto Join** is available only in this category. It works through eligible scanned servers sequentially:

1. Attempts the first eligible scanned server.
2. Ordinary connection failure moves to the next server.
3. Definite whitelist rejection deletes that server and continues.
4. Pressing the real connection-screen Cancel button stops Auto Join.
5. A stable successful connection stops Auto Join and promotes that server to Servers.

## Building from source

This repository contains the complete readable source for the mod and does not depend on a prebuilt Zazu's Server Tool JAR.

### Verified release build

With JDK 21 or newer available:

```bash
./build.sh
```

The output is:

```text
build/libs/Zazus-Server-Tool-0.3.33+mc26.2.jar
```

You can then run:

```bash
./verify.sh
```

### Fabric Loom / Gradle

The repository also contains Fabric Loom / Gradle configuration for development and IDE use.

```bash
gradle build
```

The configured development dependencies target Minecraft 26.2, Fabric Loader 0.19.3 and Fabric API 0.157.0+26.2.

## Compatibility notes

- Zazu's Server Tool is a **client-side** mod.
- Meteor Client is **not required**.
- ViaFabricPlus is **optional** but supported.
- Category switching is designed to work on already-loaded Multiplayer server rows rather than repeatedly reloading `servers.dat` or doing synchronous network work every tick.

## Project information

- **Mod:** Zazu's Server Tool
- **Current version:** 0.3.33
- **Minecraft:** 26.2
- **Mod ID:** `zazus-server-tool`
- **Java package:** `dev.zazuzin.zst`
- **Author:** Zazuzin

## License

No license is granted by this repository unless a separate `LICENSE` file is added by the author.
