# Zazu's Server Seeker

[![Downloads](https://img.shields.io/github/downloads/Zazuzin/Zazus-Server-Seeker/total?label=Downloads&color=7c3aed)](https://github.com/Zazuzin/Zazus-Server-Seeker/releases)

![Zazu's Server Seeker logo](src/main/resources/assets/zazus-server-tool/icon.png)

Zazu's Server Seeker is a client-side Fabric mod for Minecraft Java Edition 26.2 and 26.3. It adds a server finder, useful Multiplayer categories, server management tools and per-server notes without requiring Meteor Client.

**Current release:** `1.2.1` with separate JARs for Minecraft 26.2 and 26.3.

- [Releases](https://github.com/Zazuzin/Zazus-Server-Seeker/releases)
- [Issues](https://github.com/Zazuzin/Zazus-Server-Seeker/issues)
- [Discord support](https://breakblocks.com/discord)

## Download and install

Choose the download for your Minecraft version:

| Minecraft | Download | Fabric Loader |
| --- | --- | --- |
| 26.2 | [Zazus-Server-Seeker-1.2.1-mc26.2.jar](https://github.com/Zazuzin/Zazus-Server-Seeker/releases/download/v1.2.1/Zazus-Server-Seeker-1.2.1-mc26.2.jar) | 0.19.3 or newer |
| 26.3 | [Zazus-Server-Seeker-1.2.1-mc26.3.jar](https://github.com/Zazuzin/Zazus-Server-Seeker/releases/download/v1.2.1/Zazus-Server-Seeker-1.2.1-mc26.3.jar) | 0.19.5 or newer |

Both versions require **Java 25** and [Fabric API](https://modrinth.com/mod/fabric-api) for the matching Minecraft version: `0.158.0+26.2` or newer for 26.2, or `0.163.0+26.3` or newer for 26.3.

Install [Fabric Loader](https://fabricmc.net/use/installer/) for your Minecraft version, then:

1. Put the downloaded JAR in your Minecraft instance's `mods` folder alongside Fabric API.
2. Remove older Server Seeker JARs so only one version is installed.
3. Start Minecraft and open **Multiplayer → Zazu's Server Seeker**.

Install the mod on your client. The server does not need it.

ViaFabricPlus 4.6.1+ is optional; it is required to join 26.3 servers from a 26.2 client.

## Features

- Finds servers through BreakBlocks, Cornbread and MineScan.
- Browse BreakBlocks listings page by page in Multiplayer; save chosen entries to Servers with Add. Browsing does not start a scan or save the whole page.
- Randomizes BreakBlocks page traversal and provider-result order so simultaneous searches are less likely to test the same servers first.
- Verified Search checks results directly before showing or adding them.
- Quick Search shows provider results immediately for manual review.
- Filters by version, player count and authentication type using direct selection menus.
- Can scan for Minecraft 26.3 servers from the 26.2 client; ViaFabricPlus is required to join them.
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

Use **Browse BreakBlocks** on the main Multiplayer screen to inspect one API page at a time. Each row shows its address, version, player count and MOTD, with Add and Details controls. Version, player count, login type, result order and record age have direct selection menus. Browsed listings are provider data and have not passed Verified Search's two live checks. Only pressing Add writes an entry to `servers.dat`, in the Servers category. The browser uses the configured BreakBlocks API key and respects the page cap and shared request allowance. Known provider-reported whitelisted results are hidden; servers with unknown whitelist status can still appear.

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

## Updating and data

Existing servers, settings, favourites, categories, auth cache, Recent Servers, BreakBlocks state and Server Notes data are preserved when updating.

All Server Seeker configuration and data now lives under:

```text
config/zazus-server-seeker/
```
## Building

Use JDK 25 with the included Gradle 9.5.1 wrapper and Fabric Loom project:

```bash
./build.sh
./verify.sh build/libs/Zazus-Server-Seeker-1.2.1-mc26.2.jar
```

The built JAR is written to `build/libs/`. The 26.3 source is in [`versions/mc26.3/`](versions/mc26.3/) on this branch. To build it, run `cd versions/mc26.3` followed by `./build.sh` and `./verify.sh`.

## Credits

Created by Zazuzin.

Special thanks to Sheepy @ [BreakBlocks](https://breakblocks.com) for your server-discovery service and contribution support. You can also find Me in the [BreakBlocks Discord](https://breakblocks.com/discord).

## License

Licensed under the [GNU General Public License v3.0 only](LICENSE).

