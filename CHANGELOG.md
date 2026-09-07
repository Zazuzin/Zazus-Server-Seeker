# Changelog

## v0.4.1-beta.1 — 2026-09-07

Major changes since the original **v0.4.0-beta.1** release.

### BreakBlocks contributions

- Added an optional contribution system that sends verified public Minecraft servers to BreakBlocks for indexing or status refresh.
- Contributions are enabled by default for new installations and can be disabled from **Finder → Settings → Contribute Servers**.
- Verified Search results are contributed only after passing both direct Minecraft status checks.
- Manually added and Direct Connect public servers are contributed only after Minecraft reaches PLAY and the connection remains stable for approximately eight seconds.
- Failed and cancelled connection attempts are not contributed.
- Added a six-hour local cooldown for successful contributions to avoid repeatedly submitting the same server.
- Recent BreakBlocks `lastPing` data is also checked so recently refreshed records can be skipped.
- Private, loopback and LAN servers are never sent to BreakBlocks. Confirmed local endpoints are stored separately in `config/private-lan-servers.txt` for review.
- Improved endpoint handling for servers using the default port and for IPv6 addresses.

### Contribution queue and rate limiting

- Added a single background FIFO contribution worker that continues while Minecraft remains open, including during gameplay and on menus.
- Added a persistent queue so unfinished contributions resume after Minecraft is restarted.
- Added ten-second follow-up requests when BreakBlocks reports that a server is still refreshing.
- Due refresh follow-ups are prioritised without losing the order of newly queued servers.
- Finder searches and contributions now share one adaptive BreakBlocks request budget.
- The request budget uses BreakBlocks rate-limit headers when available and adapts to larger API-key allowances.
- HTTP `429` and `Retry-After` responses now pause and resume the queue automatically instead of discarding pending servers.
- Finder searches retain reserved request capacity so background contributions cannot consume the entire allowance.
- Failed contributions are stored locally and can be queued again with **Retry Failed**.

### Contribution logging and statistics

- Added `config/breakblocks-contributions.csv`, recording queued servers, attempts, HTTP outcomes and completion status without recording the API key.
- Added persistent queue, failed-list and successful-cooldown files under the Minecraft instance's `config/` directory.
- Added a **Contribution Stats** screen showing:
  - current and queued endpoints;
  - anonymous or API-key mode;
  - estimated allowance and reset time;
  - session and overall accepted, refreshing, rate-limited and failed totals;
  - failed entries waiting to be retried.
- Added controls to refresh contribution statistics, retry failed entries, clear statistics and open the log folder.

### Finder and settings interface

- Reorganised the Finder controls into a clearer three-row layout:
  - **Find New Servers**, **Auto-add**, **Reset Search**, **Close Finder**;
  - **Version**, **Min Players**, **Max Players**, **Type**;
  - **Sort**, **Blocked Servers**, **Settings**.
- Removed the unexplained abbreviated statistics display from the main Finder screen.
- Added a dedicated **Server Seeker Stats** screen with clearly labelled added-history, added, deleted, favourite and blocked totals.
- Added readable tooltips to the main Finder filters and Settings controls.
- Renamed the contribution setting to **Contribute Servers** to reflect support for verified discoveries and stable manual connections.

### Multiplayer interface fixes

- Added the current server address and a **Copy IP** button beneath the pause-menu Favourite control, with the address box sized to its contents.
- Category totals now refresh immediately when returning to the Categories screen after adding, deleting, restoring, promoting or changing a favourite.
- Fixed stable joins occasionally reusing a previous connection address, which prevented the newly joined Scanned Server from moving to Servers after eight seconds.
- The custom category Refresh button now reuses Minecraft's native Refresh position and dimensions.
- Fixed the Refresh and Back buttons overlapping at some fullscreen resolutions and GUI scales while retaining the working in-place category refresh behaviour.

### Privacy and reliability

- BreakBlocks API keys remain restricted to the HTTP `Authorization` header and are never placed in request URLs or contribution logs.
- Added clear documentation covering contributed data, local queue files, private/LAN handling and removal of locally stored data.
- Expanded regression coverage for provider parsing, optional API authentication, rate limiting, bounded Minecraft status checks, category management, deletion recovery and private/LAN routing.
