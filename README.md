# Zazu's Server Seeker

![Zazu's Server Seeker logo](src/main/resources/assets/zazus-server-tool/icon.png)

Zazu's Server Seeker is a client-side Fabric mod for Minecraft Java Edition 26.2. It adds a server finder, useful Multiplayer categories, server management tools and per-server notes without requiring Meteor Client.

**Current release:** `1.1.8` for Minecraft 26.2.

- [Releases](https://github.com/Zazuzin/Zazus-Server-Seeker/releases)
- [Issues](https://github.com/Zazuzin/Zazus-Server-Seeker/issues)
- [Discord support](https://discord.gg/TC4PhPx9sf)

## Features

- Finds servers through BreakBlocks, Cornbread and MineScan.
- Randomizes BreakBlocks page traversal and provider-result order so simultaneous searches are less likely to test the same servers first.
- Verified Search checks results directly before showing or adding them.
- Quick Search shows provider results immediately for manual review.
- Filters by version, player count and authentication type.
- Supports continuous Auto Add with a configurable limit.
- Organises saved servers into Favourites, Servers, Scanned Servers and Recent Servers.
- Adds compact Notes, Favourite, authentication and Delete controls to server rows.
- Detects Microsoft, cracked or unknown authentication in the background. Results are cached for 14 days and can be checked again manually.
- Auto Join works through eligible Servers or Scanned Servers, skips Favourites and continues after failed connections.
- Conservative automatic cleanup can remove Finder-owned servers after confirmed whitelist or required-client-mod rejections, or after three eligible DNS/unreachable failures.
- Favourites, manually added servers, timeouts and all client/version mismatches are protected so ViaFabricPlus can still retry with another protocol.
- Recently Removed records the reason and time for automatic removals and can restore an entry; normal Undo and `servers.dat` backups remain available.
- Promotes a scanned server to Servers after a stable successful connection.
- Keeps the last five successful unique connections in Recent Servers.
- Includes Undo for supported server deletions.
- Integrates with ViaFabricPlus when it is installed.

## Server Notes

Use the book button beside a saved server, or press **N** while connected, to open its profile.

Each profile can store:

- Notes and Quick Notes
- Named locations with XYZ coordinates and dimension
- Historical player records with first seen, last seen and encounter count
- Auto Join failure reasons

Player names can be copied from the history list. The player history is a record of past encounters and does not show a live online/offline state.

Server Notes data is stored at:

```text
config/zazus-server-seeker/server-notes/server-profiles.json
```

The mod keeps a `.bak` backup and retains the existing schema-v2 data when upgrading.

## Finder sources

Choose a source under **Finder → Settings**:

- **Auto** starts with BreakBlocks and falls back when needed.
- **All Sources** rotates through the available providers and removes duplicate addresses.
- **BreakBlocks**, **Cornbread** or **MineScan** uses only that provider.

BreakBlocks supports an optional API key. When BreakBlocks confirms an
authenticated paid tier, Server Seeker shows the tier and uses every available
page reported by the search instead of the public page cap. Page and result
orders remain randomized without repeating pages. Cornbread and MineScan do not
require an API key.

Verified Search uses two direct Minecraft status checks before a result is shown or Auto Added. Quick Search skips those checks, so Auto Add is disabled in Quick mode.

## BreakBlocks contributions

Public servers that pass both Verified Search checks, and public servers that remain connected for a stable session, can be contributed to BreakBlocks. This can be turned off in Finder settings.

Private and LAN addresses are kept locally. Failed joins are not contributed. Contribution requests are queued and paced so Finder searches keep priority, and unfinished work is restored after restarting Minecraft.

An optional BreakBlocks API key can be set in:

```text
config/zazus-server-seeker/server-tool.properties
```

```properties
breakBlocksApiKey=YOUR_API_KEY
```

Do not commit or share your API key.

## Requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.3 or newer compatible release
- Fabric API 0.158.0+26.2
- Java 25
- ViaFabricPlus 4.6.1+ (optional)

## Installation

1. Install Fabric Loader and Fabric API for Minecraft 26.2.
2. Download `Zazus-Server-Seeker-1.1.8-mc26.2.jar`.
3. Put it in the instance's `mods` folder.
4. Remove older Server Seeker JARs so only one version is installed.
5. Start Minecraft and open Multiplayer.

Existing servers, settings, favourites, categories, auth cache, Recent Servers, BreakBlocks state and Server Notes data are preserved when updating.

All Server Seeker configuration and data now lives under:

```text
config/zazus-server-seeker/
```

On first launch, legacy Server Seeker files and folders are moved there without overwriting an existing destination. When moving instances, copy this whole folder together with `servers.dat`. If only `servers.dat` was copied, Finder-generated names are used to recover a bulk imported list into **Scanned Servers** instead of leaving hundreds of entries in **Servers**.

## Building

Use JDK 25 with the included Gradle 9.5.1 wrapper and Fabric Loom project:

```bash
./build.sh
./verify.sh build/libs/Zazus-Server-Seeker-1.1.8-mc26.2.jar
```

The built JAR is written to `build/libs/`.

## Credits

Created by Zazuzin.

Special thanks to [BreakBlocks](https://breakblocks.com) for its server-discovery service and contribution support. You can also find Zazuzin in the [BreakBlocks Discord](https://breakblocks.com/discord).

## License

Licensed under the [GNU General Public License v3.0 only](LICENSE).
