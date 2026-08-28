# Privacy

Zazu's Server Tool is a client-side Minecraft mod. It does not include analytics, advertising, telemetry or a developer-operated tracking service.

## Network activity

The Finder requests candidate Minecraft server addresses from the public BreakBlocks, Cornbread and MineScan APIs. Search filters and normal HTTP metadata, including your public IP address, may be visible to those providers under their own privacy policies.

Candidates are checked using the standard Minecraft Java status protocol. Connecting or status-checking a server reveals your public IP address to that server in the same way that Minecraft's normal multiplayer screen does.

If configured, a BreakBlocks API key is sent only to BreakBlocks in an `Authorization: Bearer` header. It is not placed in request URLs or intentionally written to logs.

## Local data

The mod stores settings, category membership, favourites, recent servers, health state and server-management history under the Minecraft instance's `config/` directory. Minecraft stores saved server entries in its normal `servers.dat` file. Automatic deletion recovery may create local backups of `servers.dat`.

No local configuration or server history is bundled with official source or binary releases.

## Removing data

Remove the mod and its `config/zazus-server-tool.properties`, `config/zazus-server-tabs.properties` and backup directory to delete mod-owned local data. Minecraft's `servers.dat` remains under the instance directory unless you remove it separately.
