# Privacy

Zazu's Server Seeker is a client-side Minecraft mod. It does not include analytics, advertising, telemetry or a developer-operated tracking service.

## Network activity

The Finder requests candidate Minecraft server addresses from the public BreakBlocks, Cornbread and MineScan APIs. Search filters and normal HTTP metadata, including your public IP address, may be visible to those providers under their own privacy policies.

During Verified Search, public servers that pass both direct Minecraft status checks are submitted to BreakBlocks' public status endpoint. A manually added or Direct Connect public server is also submitted after Minecraft enters PLAY and the connection remains stable for roughly eight seconds. This allows BreakBlocks to add a newly discovered public server address to its index or refresh an existing record. Contributions are enabled by default and can be disabled with **Finder → Settings → Contribute Servers**. Quick Search results and failed connection attempts are not contributed.

Private, loopback and LAN endpoints are never submitted to BreakBlocks. If one passes both Finder checks or reaches the same stable successful connection, its normalized address is stored locally in `config/private-lan-servers.txt`. This separate list lets the behaviour be reviewed without publishing local-network addresses.

Candidates are checked using the standard Minecraft Java status protocol. Connecting or status-checking a server reveals your public IP address to that server in the same way that Minecraft's normal multiplayer screen does.

If configured, a BreakBlocks API key is sent only to BreakBlocks in an `Authorization: Bearer` header. It is not placed in request URLs or intentionally written to logs.

## Local data

The mod stores settings, category membership, favourites, recent servers, health state and server-management history under the Minecraft instance's `config/` directory. BreakBlocks contribution attempts and results are written to `config/breakblocks-contributions.csv`. Unfinished contribution endpoints are stored in `config/breakblocks-contribution-queue.txt` so they can resume after restarting Minecraft, failed endpoints awaiting an optional retry are stored in `config/breakblocks-contribution-failed.txt`, and successful contributions are held on a local six-hour cooldown in `config/breakblocks-contribution-cooldowns.txt`. Confirmed private and LAN endpoints are stored separately in `config/private-lan-servers.txt` and are not submitted. These files never contain the API key. Minecraft stores saved server entries in its normal `servers.dat` file. Automatic deletion recovery may create local backups of `servers.dat`.

No local configuration or server history is bundled with official source or binary releases.

## Removing data

Remove the mod and its `config/zazus-server-tool.properties`, `config/zazus-server-tabs.properties`, `config/breakblocks-contribution-*`, `config/breakblocks-contributions.csv`, `config/private-lan-servers.txt` and backup directory to delete mod-owned local data. Minecraft's `servers.dat` remains under the instance directory unless you remove it separately.
