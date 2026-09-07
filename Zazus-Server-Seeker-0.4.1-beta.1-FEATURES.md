# Zazu's Server Seeker 0.4.1-beta.1 — Feature Overview

Prepared for the BreakBlocks owner on 6 September 2026.

## Release overview

Zazu's Server Seeker is a client-side Fabric mod for Minecraft Java Edition 26.2. It discovers public servers through BreakBlocks, Cornbread and MineScan, verifies candidates with the Minecraft status protocol, organises saved servers into categories and optionally contributes verified public endpoints back to BreakBlocks.

| Component | Version |
| --- | --- |
| Zazu's Server Seeker | `0.4.1-beta.1` |
| Minecraft | `26.2` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.157.0+26.2` |
| Java class target | Java 21 / major 65 |
| Licence | GPL-3.0-only |

## Main features

- Separate Favourites, Servers, Scanned Servers and Recent Servers screens.
- BreakBlocks, Cornbread and MineScan discovery with Auto, All Sources and direct-source modes.
- Cross-provider endpoint de-duplication.
- Verified Search using two direct Minecraft status checks.
- Quick Search for immediate unverified provider results.
- Version, player-count and Premium/Cracked filters, sorting and result pagination.
- Auto Add, server details, blocked-server management and Finder diagnostics.
- Sequential Auto Join for Servers and Scanned Servers while always excluding Favourites.
- Automatic promotion from Scanned Servers to Servers after a stable successful join.
- Favourite-safe health cleanup after three consecutive failed Scanned Server checks.
- Per-server deletion, Delete All and single/batch Undo Last Delete.
- Five-entry unique Recent Servers history.
- Pause-menu Favourite/Unfavourite, current server address and Copy IP controls.
- Optional ViaFabricPlus integration.

## BreakBlocks contribution system

### Eligible contributions

- Public servers found through Verified Search are queued only after passing both direct Minecraft status checks.
- Cornbread and MineScan discoveries use the same double-verification requirement before contribution.
- Manually added and Direct Connect servers are queued only after Minecraft enters PLAY and remains connected for approximately eight seconds.
- Quick Search results, failed joins and cancelled connections are never contributed.

### Endpoint and privacy protection

- Private IPv4 ranges, loopback, carrier-grade NAT, link-local, multicast/reserved ranges, local hostnames and private/link-local IPv6 addresses are never sent to BreakBlocks.
- Confirmed private or LAN endpoints are stored locally in `config/private-lan-servers.txt` for review.
- API keys are used only in the HTTP `Authorization` header and never appear in request URLs or contribution logs.
- The contribution preference is enabled by default for new installations while preserving an existing saved preference.

### Queue behaviour

- One background FIFO worker processes contributions without blocking Minecraft.
- The queue continues on menus and during gameplay and is restored after Minecraft restarts.
- A `status: refreshing` response schedules another check after ten seconds, with up to three follow-up checks.
- Due refresh checks are prioritised while untouched queued entries retain their order.
- HTTP `429` responses honour `Retry-After`, pause the worker and retain the endpoint for retry.
- Failed entries are persisted and can be requeued with Retry Failed.
- Successful contributions receive a six-hour local cooldown.
- Recent BreakBlocks `lastPing` values are checked so endpoints refreshed within six hours can be skipped.

### Rate-limit handling

- Finder searches and contributions share one request budget.
- Anonymous mode starts conservatively at approximately 20 requests per minute, reserves at least four requests for Finder searches and spaces contribution requests by approximately four seconds.
- API-key mode starts with a larger working allowance and spaces contributions by approximately one second.
- `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` and `Retry-After` values update the working budget when supplied.
- If BreakBlocks raises the ping allowance to approximately 100 requests per minute, the mod will adapt from the returned rate-limit headers without requiring a fixed client-side limit change.

### API use and diagnostics

- Finder discovery uses `https://api.breakblocks.com/api/v0.1/servers/find`.
- Contributions use `https://api.breakblocks.com/api/v0.1/status/ping/{host}/{port}`.
- The User-Agent for both paths is `ZazusServerSeeker/0.4.1-beta.1`.
- `config/breakblocks-contributions.csv` records `timestamp_utc`, `server`, `attempt`, `result`, `http_status` and `detail`.
- Contribution Stats displays the current endpoint, pending queue, allowance, reset time, accepted, refreshing, rate-limited and failed totals.

## Validation completed

- Verified scanner results progressed through the BreakBlocks refresh flow to accepted.
- Stable manual and Direct Connect sessions were contributed.
- Failed connections were excluded.
- Private/LAN endpoints remained local.
- Queue processing continued during gameplay and on menus.
- Pending entries resumed after Minecraft restarted.
- HTTP `429` pause and resume behaviour was confirmed.
- Stable Scanned Server promotion was confirmed across consecutive manual joins and Auto Join.
- The complete local regression suite passed before packaging.

## Project links

- Repository: <https://github.com/Zazuzin/Zazus-Server-Scanner>
- BreakBlocks: <https://breakblocks.com>
