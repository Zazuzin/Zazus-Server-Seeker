# Privacy

Zazu's Server Seeker is a client-side Minecraft mod. It does not include analytics, advertising, telemetry or a developer-operated tracking service.

## Network activity

The Finder requests candidate Minecraft server addresses from the public BreakBlocks, Cornbread and MineScan APIs. Search filters and normal HTTP metadata, including your public IP address, may be visible to those providers under their own privacy policies.

During Verified Search, public servers that pass both direct Minecraft status checks are submitted to BreakBlocks' public status endpoint. A manually added or Direct Connect public server is also submitted after Minecraft enters PLAY and the connection remains stable for roughly eight seconds. This allows BreakBlocks to add a newly discovered public server address to its index or refresh an existing record. Contributions are enabled by default and can be disabled with **Finder → Settings → Contribute Servers**. Quick Search results and failed connection attempts are not contributed.

Private, loopback and LAN endpoints are never submitted to BreakBlocks. If one passes both Finder checks or reaches the same stable successful connection, its normalized address is stored locally in `config/zazus-server-seeker/private-lan-servers.txt`. This separate list lets the behaviour be reviewed without publishing local-network addresses.

Candidates are checked using the standard Minecraft Java status protocol. Connecting or status-checking a server reveals your public IP address to that server in the same way that Minecraft's normal multiplayer screen does.

When **Auth Detection** is enabled, Server Seeker may also open a short-lived Minecraft login-phase connection to classify the public endpoint as Microsoft-authenticated, cracked/offline, or unknown. The probe uses a generated `ZazuAuth_...` username, does not use your Minecraft username, Microsoft account, password or access token, and disconnects before Minecraft reaches normal configuration/play. The destination server can still see the probe connection and your public IP address in its connection logs. Authentication results are cached locally in `config/zazus-server-seeker/server-auth.properties` for 14 days unless you manually request a recheck.

If configured, a BreakBlocks API key is sent only to BreakBlocks in an `Authorization: Bearer` header. It is not placed in request URLs or intentionally written to logs.

## Local data

The mod stores settings, category membership, favourites, recent servers, cleanup history and related state under the Minecraft instance's `config/zazus-server-seeker/` directory. BreakBlocks contribution attempts, queues, retries and cooldowns are also kept there. Confirmed private and LAN endpoints are stored separately in that directory and are not submitted. These files never contain the API key. Minecraft stores saved server entries in its normal `servers.dat` file. Automatic deletion recovery may create local backups of `servers.dat` under `config/zazus-server-seeker/backups/`.


Server Notes stores per-server notes, tags, Important state, saved coordinates/dimensions and historical player records locally in `config/zazus-server-seeker/server-notes/server-profiles.json`. Player records may contain usernames and UUIDs observed in the connected server's live player list; the local player's own account is excluded by the tracking logic. The file uses a local `.bak` backup and may preserve a corrupt copy during recovery. Server Notes data is not submitted to BreakBlocks or the other Finder providers.

No local configuration or server history is bundled with official source or binary releases.

## Removing data

Remove the mod and its `config/zazus-server-seeker/` directory to delete mod-owned local data. Minecraft's `servers.dat` remains under the instance directory unless you remove it separately.
