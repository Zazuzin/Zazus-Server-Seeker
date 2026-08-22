# Zazu's Server Tool

Fully buildable source for **Zazu's Server Tool 0.3.33** targeting Minecraft Java Edition **26.2** with Fabric.

This repository is the source of truth for the mod. It does **not** depend on a prebuilt Zazu's Server Tool JAR and contains no preserved obfuscated `a`–`h` core classes or bytecode patching step.

## Requirements

Runtime/test target:

- Minecraft 26.2
- Fabric Loader 0.19.3 or newer compatible release
- Fabric API (tested target: 0.157.0+26.2)
- Java 25 for the Minecraft 26.2 runtime/development environment
- Release classes are intentionally compiled to Java 21 bytecode
- ViaFabricPlus is optional

## Build

### Verified local build

For the dependency-free release build, JDK 21+ is sufficient:

```bash
./build.sh
```

Output:

```text
build/libs/Zazus-Server-Tool-0.3.33+mc26.2.jar
```

`build.sh` compiles the complete Java source and packages the mod. Because the project intentionally accesses Minecraft/Fabric APIs reflectively for mapping compatibility, it uses a compile-only local stub for Fabric's `ClientModInitializer`; that stub is removed before the JAR is packaged. The real Fabric Loader interface is supplied at runtime.

Then verify the artifact with:

```bash
./verify.sh
```

### Fabric Loom / Gradle

The repository also includes normal Fabric Loom files for IDE/development use. With a compatible Gradle installation and Java 25, run:

```bash
gradle build
```

The configured dependencies are Minecraft 26.2, Fabric Loader 0.19.3, and Fabric API 0.157.0+26.2. Minecraft 26.2 is unobfuscated, so the project uses the 26.2 `net.fabricmc.fabric-loom` setup without a mappings dependency.

## BreakBlocks API key (optional)

The Finder works without an API key exactly as before. BreakBlocks documents `/servers/find` authentication as optional.

To enable authenticated requests, open:

```text
config/zazus-server-tool.properties
```

and set:

```properties
breakBlocksApiKey=YOUR_API_KEY
```

Do not commit or share your key. The key is never bundled in the mod, never placed in request URLs, and is never written to Zazu's Server Tool log messages. On POSIX systems the mod attempts to restrict the settings file to owner read/write permissions.

When a key is configured, the Finder sends it only as `Authorization: Bearer …`. This lets BreakBlocks apply the higher rate limits and any additional `/servers/find` access granted by the account/tier. The mod keeps the per-request batch at 20 to avoid creating a large burst of Minecraft status probes, but authenticated searches may traverse a deeper result window when BreakBlocks permits it.

If BreakBlocks rejects the key with HTTP 401/403, the same request is retried anonymously and the Finder continues working. HTTP 429 responses use BreakBlocks' `Retry-After` value when available.

## Multiplayer workflow

Opening Multiplayer enters the category hub. Favourites, Servers and Scanned Servers are filtered views over the same vanilla `servers.dat`; they are not duplicate databases.

- **Favourites**: only favourite servers. Unfavouriting returns a server to its retained Servers/Scanned classification.
- **Servers**: manual/established servers and scanned servers that survive a real connection for about eight seconds.
- **Scanned Servers**: Finder-added servers not yet verified by a stable join.
- **Auto Join** exists only in Scanned Servers. Ordinary failures continue to the next eligible scanned server; a real Connect-screen Cancel stops the run; whitelist rejection deletes that server; stable success stops Auto Join and promotes it to Servers.
- Whitelist deletion deliberately overrides favourite deletion protection.

## Release-hardening notes

0.3.33 continues the fully readable source baseline introduced in 0.3.32 and adds optional BreakBlocks API-key authentication without making the key mandatory. Legacy hidden Auto Join UI, bytecode strippers, patch-only enhancement entrypoints, direct reflection against Fabric's package-private `ArrayBackedEvent`, and the obsolete direct `Minecraft.setScreen(Screen)` assumption are not part of this source tree.

Category switching operates on already-loaded Multiplayer row collections and does not reload `servers.dat` or perform network work every tick.

## Project identity

- Mod: Zazu's Server Tool
- Mod ID: `zazus-server-tool`
- Package: `dev.zazuzin.zst`
- Author: Zazuzin
- License: no license is granted by this repository unless a separate LICENSE file is added by the author.
