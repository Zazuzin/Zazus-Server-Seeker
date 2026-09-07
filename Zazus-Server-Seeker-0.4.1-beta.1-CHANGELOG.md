# Zazu's Server Seeker 0.4.1-beta.1 — Changelog

Changes since the public `0.4.0-beta.1` release.

## BreakBlocks contributions

- Added optional submission of double-verified public Finder discoveries to BreakBlocks.
- Added stable-join contribution for manually added and Direct Connect servers after approximately eight seconds in PLAY.
- Excluded Quick Search results, failed connections and cancelled connections.
- Added six-hour successful-contribution cooldowns and recent `lastPing` checks.
- Added private, loopback, link-local and LAN filtering with local-only recording.
- Kept API keys exclusively in the HTTP `Authorization` header.

## Queue and rate limiting

- Added a persistent FIFO contribution queue that continues during gameplay and on menus.
- Added restart recovery for unfinished contributions.
- Added ten-second follow-up checks for BreakBlocks `refreshing` responses.
- Added automatic HTTP `429` and `Retry-After` pause/resume behaviour.
- Added a shared adaptive budget for Finder and contribution traffic.
- Reserved request capacity for interactive Finder searches.
- Added persistent failed entries and a Retry Failed control.

## Logging and statistics

- Added `config/breakblocks-contributions.csv` with endpoint, attempt, result, HTTP status and detail fields.
- Added persistent queue, failed-list and cooldown files.
- Added Contribution Stats with current endpoint, queue, allowance, reset time and outcome totals.
- Added refresh, retry, clear-statistics and open-log-folder controls.

## Finder and settings

- Reorganised Finder controls into a clearer three-row layout.
- Added readable tooltips for filters and settings.
- Added a dedicated Server Seeker Stats screen.
- Improved Contribution Stats and renamed the preference to Contribute Servers.

## Multiplayer interface

- Added the current server address and Copy IP control below the pause-menu Favourite button.
- Sized the address box to its rendered contents while retaining a safe maximum and full tooltip.
- Fixed stable joins occasionally reusing the previous server address during Scanned Server promotion.
- Refreshed category totals immediately after additions, deletions, restores, promotions and favourite changes.
- Reused Minecraft's native Refresh position and fixed Refresh/Back overlap at affected resolutions and GUI scales.

## Reliability and testing

- Improved default-port and IPv6 endpoint handling.
- Expanded provider, optional-authentication, rate-limit, status-probe, category, whitelist, deletion-recovery and private/LAN regression coverage.
- Confirmed sequential manual and Auto Join promotion from Scanned Servers to Servers.
- Confirmed BreakBlocks accepted, refreshing, rate-limited, restart-recovery and stable-join contribution paths.

## Compatibility

- Minecraft `26.2`
- Fabric Loader `0.19.3`
- Fabric API `0.157.0+26.2`
- Java 21 class-file major 65
- Internal mod ID remains `zazus-server-tool` for configuration and upgrade compatibility.
- Licensed under GPL-3.0-only.
