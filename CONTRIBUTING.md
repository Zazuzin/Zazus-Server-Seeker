# Contributing

Contributions and reproducible bug reports are welcome.

## Development setup

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.3 or newer compatible release
- Fabric API 0.158.0+26.2
- JDK 25
- Gradle 9.5.1 (the wrapper is pinned to this version)
- Fabric Loom 1.17.12

Minecraft 26.2 is distributed to Loom as a non-obfuscated development artifact,
so this project intentionally does not apply a mappings layer or run `remapJar`.

Run:

```bash
./build.sh
./verify.sh
```

The verification script checks metadata, Java bytecode, provider parsing and authentication, bounded status probing, Auto Join behaviour, whitelist handling, category persistence, deletion recovery and UI regression guards.

## Pull requests

- Keep the mod client-only.
- Do not commit `config/`, `servers.dat`, logs, API keys, account tokens or real server-history data.
- Explain user-visible behaviour changes and add or update regression coverage.
- Keep favourites protected from automatic and bulk deletion.
- Preserve bounded networking and avoid Minecraft/Netty lifecycle ownership from Finder probes.
