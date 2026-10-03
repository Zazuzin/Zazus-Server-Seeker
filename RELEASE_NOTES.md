# Zazu's Server Seeker 1.1.9 for Minecraft 26.2

This update adds Minecraft 26.3 server discovery to the Minecraft 26.2 build and
makes every multi-choice Finder filter quicker to use.

## What changed

- Added 26.3 to the version filter for scanning and verifying servers. Joining a
  26.3 server from the 26.2 client requires ViaFabricPlus.
- Ordered version choices newest-first: Any, 26.3, 26.2, 26.1, then older releases.
- Replaced click-to-cycle controls with direct selection menus for version,
  minimum/maximum players, login type, sorting, Auto-add limit, Finder source
  and BreakBlocks result age.
- Existing saved filter choices are preserved when updating from 1.1.8.
- Notes, Favourite, Auth and Delete row controls work consistently.
- Double-clicking a saved server joins it again.
- Refresh stays in its original footer position without overlapping Back,
  including after Auto Join returns.
- Auto Join continues through failed servers and promotes a scanned server
  after a stable connection.
- Finder-owned servers can be removed after confirmed whitelist or required-mod
  rejections. Current Forge, NeoForge, Fabric and Quilt loader messages are
  recognised. Favourites, manual servers, timeouts and version mismatches remain
  protected.
- Large transferred `servers.dat` lists are recovered into Scanned Servers
  when their Finder-generated names can be identified.
- Server Seeker configuration, Server Notes and backups now live together under
  `config/zazus-server-seeker/`.
- BreakBlocks pages and provider results are shuffled for each search so users
  are less likely to scan the same servers in the same order.
- Paid BreakBlocks API tiers can use all available result pages reported by the
  API. Verified-search and stable-connection contributions remain enabled and
  queued safely.

Existing server lists, settings, favourites, notes, categories, auth results,
recent servers and contribution state are preserved when upgrading.
