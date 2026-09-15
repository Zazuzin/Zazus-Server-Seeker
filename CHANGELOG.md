# 1.0.0

## Stable Minecraft 26.2 release

- Promoted the user-tested `1.0.0-rc.9` candidate to stable `1.0.0` without
  further runtime, data-format or persistent-path changes.
- Completed the final source, dependency-reachability, credential, packaging,
  bytecode and real-API audits.
- Retains the clean Java 25 / Gradle 9.5.1 / Fabric Loom 1.17.21 build against
  Minecraft 26.2, Fabric Loader 0.19.3 and Fabric API 0.158.0+26.2.
- Preserves existing `servers.dat`, Server Seeker settings/categories/history,
  authentication cache, BreakBlocks state and Server Notes schema-v2 data.

# 1.0.0-rc.9

## Final source cleanup

- Removed the inaccessible Tags screens and their unused mutation service after
  Tags navigation was intentionally removed in RC.8.
- Removed the unused Server Notes profile-favourite service formerly associated
  with the separate Important control.
- Preserved the hidden `tags` and `favourite` values in Server Notes schema 2 so
  existing user data survives loading and saving unchanged.
- Added release checks preventing the removed classes from returning to source
  or packaged JARs.

# 1.0.0-rc.8

## Fresh-install release blockers and category polish

- Fixed unfavouriting from the Favourites category so every live copy of the
  server row loses its legacy star before the category refresh; the server no
  longer reappears when Favourites is reopened.
- Removed the Tags navigation/count and the separate Important control from the
  Server Notes overview while preserving all existing stored values and schema.
- Auto Join now saves non-whitelist connection failures as timestamped server
  notes containing Minecraft's concise disconnect reason.
- Removed the redundant Categories button from category screens and aligned
  Auto Join and Zazu's Server Seeker with the bottom footer rail without adding
  Auto Join to Favourites.
- Updated the clean-build toolchain within the approved Loom 1.17.x line from
  1.17.12 to 1.17.21 for current Java 25/Linux compatibility.

# 1.0.0-rc.7

## Complete row controls after RC.6 lazy loading

- Fixed Notes/Favourite/Auth/Delete controls appearing on only part of a large
  category after RC.6.
- Visible rows are now resolved using Minecraft's authoritative row coordinates
  with a logarithmic lookup, so scrolling no longer depends solely on one
  version-specific scroll accessor.
- Visible row controls still appear immediately, while one remaining off-screen
  row is materialized per tick as a bounded compatibility fallback.
- Preserved RC.6's prompt hub/category opening and RC.5's visible-window
  positioning limits.

# 1.0.0-rc.6

## Multiplayer/category opening performance

- Removed the redundant full server-list reconstruction performed while the
  category hub was opening.
- Removed the synchronous `servers.dat` sort/save pass repeated for the hub and
  every category; category membership already supplies the visible ordering.
- Row-control descriptors remain available for every category entry, but the
  four Minecraft widgets are now created lazily only for the viewport-sized
  visible range.
- Removed a duplicate row-control rebuild during in-place category refresh.
- Preserved scrolling alignment, status ping behavior and every existing data
  path/schema.

# 1.0.0-rc.5

## Multiplayer server-status loading performance

- Limited per-frame and tick-rate row-control geometry work to the visible
  server rows plus a small scrolling buffer.
- Off-screen Notes/Favourite/Auth/Delete controls are hidden without repeatedly
  querying every saved row through reflection.
- Preserved smooth row-control scrolling, low-frequency list-integrity checks,
  vanilla player count/version/ping behavior and all existing data paths.

# 1.0.0-rc.4

## Clean Minecraft 26.2 Loom build

- Fixed RC.3's release-blocking Loom configuration: Minecraft 26.2 is already
  non-obfuscated, so mappings and `remapJar` must not be applied.
- Added the Gradle 9.5.1 wrapper and guards for the non-obfuscated build path.
- Completed a clean JDK 25/Loom build against real Minecraft/Fabric APIs.
- Corrected documentation and metadata links to the Server Seeker repository.
- Preserved beta.10/RC.2 behavior and every existing data path/schema.

# 1.0.0-rc.3

## Server Notes runtime crash fix

- Fixed a release-blocking Minecraft 26.2 runtime crash when opening Server Notes.
- The packaged `ServerNotesScreen` now references `Component.literal(...)` with an interface method reference, matching Minecraft 26.2's `Component` type.
- Fixes both the multiplayer-row Notes/book control and the in-game `N` key entry path, which both construct `ServerNotesScreen`.
- Added a packaged-bytecode regression check so a class-method reference to `Component.literal(...)` fails release verification.
- No Server Notes schema/data-path changes and no feature changes.

# 1.0.0-rc.1

## 1.0 release preparation

- Started the 1.0.0 feature freeze from the tested 0.4.1-beta.10 baseline.
- Centralized release identity for runtime network User-Agent metadata.
- Added exact build/Minecraft version identification to Finder Settings.
- Removed the obsolete unused standalone Players screen left over from earlier beta fixes; the working integrated beta.10 Players view remains.
- Preserved all existing Server Seeker/Server Notes data paths and Server Notes schema version 2.
- Cleaned release documentation and source packaging for the release-candidate phase.
- No major feature changes.

# 0.4.1-beta.10

## Server Notes Players navigation

- Reworked **Server Notes → Players** so it no longer transitions into the separate `PlayersListScreen` lifecycle that was failing to open on the live Minecraft 26.2 client.
- The existing, proven `ServerNotesScreen` now has an integrated saved-Players view.
- Players uses persisted Server Notes history only; live ONLINE/offline state remains removed.
- Retains username, first-seen time, encounter count, pagination and **Copy** player-name controls.
- Player-history loading/rendering is defensive: malformed individual data can no longer stop the Players view itself from opening.
- Added explicit runtime logging for the Players navigation/load path.
- Clipboard handling now shares a dedicated compatibility helper using the same Minecraft keyboard clipboard path as Server Seeker's Copy IP behavior.

## Smooth multiplayer row controls

- Fixed Notes/Favourite/Auth/Delete controls lagging behind Minecraft's server rows while scrolling.
- Added a lightweight per-frame **before-extract** geometry pass so `[Book] [Star] [M/C/?] [Trash]` positions are updated before each screen frame is extracted.
- The per-frame pass performs only row geometry/visibility work; expensive server-list rescans, persistence checks, auth work and tooltip rebuilding remain cached/throttled.
- Kept a tick-rate positioning fallback if the per-frame screen hook is unavailable.
- Existing beta.5 performance optimisations remain in place.

## Retained behavior

- Manual click-to-recheck authentication remains unchanged and working.
- Existing Server Notes JSON data path/schema remain unchanged.
- Existing Server Seeker categories, Finder, auth detection, favourites, deletion/undo, Recent Servers, health cleanup, Auto Join and BreakBlocks contribution behavior are retained.

# 0.4.1-beta.9

- Restored Server Notes → Players to the known-working dev.6/beta.5 screen lifecycle after the beta.6 copy-name rewrite caused the Players screen to stop opening in Minecraft.
- Live ONLINE/offline state remains removed.
- Player-name Copy remains available as an isolated per-row button without caching/replacing the screen's player list.
- Manual M/C/? authentication recheck remains unchanged from beta.7.

# 0.4.1-beta.8

- Fixed saved-row Auth indicator clicks so `M/C/?/-` correctly trigger a manual authentication recheck.
- Hardened Server Notes Players screen opening and per-player Copy controls.

# Changelog

## v0.4.1-beta.8 — 2026-09-08

- The compact `M / C / ?` authentication control on saved multiplayer rows is now clickable. Clicking it forces an immediate authentication recheck for that server, shows `…` while the probe is running, then updates back to `M`, `C`, or `?`.
- Authentication tooltips now explain the current state and indicate that the button can be clicked to recheck.
- Server Notes → Players now provides a compact `Copy` button beside each visible player so the exact username can be copied to the clipboard.
- Retains all beta.5 multiplayer-menu performance optimisations and the compact `[Book] [Star] [Auth] [Trash]` row layout.

## v0.4.1-beta.5 — 2026-09-08

### Performance / lag fixes
- Cached repeated reflection field/method lookups used by the Multiplayer UI.
- Stopped rebuilding unchanged Favourite/Auth/Delete labels and tooltips every client tick.
- Row geometry is now recalculated only when scrolling, resizing, auth visibility, or periodic compatibility validation requires it.
- Reduced full saved-server/category rescans from every client tick to a 500 ms maintenance interval; explicit UI actions still refresh immediately.
- Reduced repeated category layout/widget cleanup to change-driven plus low-frequency maintenance.
- Throttled selected-server/ViaFabricPlus/Delete-protection checks to 200 ms.
- Server Notes no longer normalises the current server address every tick, and player-list profile resolution now occurs only on its existing one-second poll.
- No Server Seeker, authentication detection, Server Notes, Favourite, Delete, category, or scanning features were removed.

## v0.4.1-beta.4 — 2026-09-07

### Server Notes merged natively

- Merged **Zazu's Server Notes v0.1.0-dev.6** into Server Seeker as a native feature.
- Preserved the existing Server Notes schema-v2 data path: `config/zazus-server-notes/server-profiles.json`.
- Preserved atomic save, `.bak` backup and corrupt-file recovery behaviour.
- Retained notes CRUD, Quick Note, optional note categories, tags and the separate **Important** marker.
- Retained saved locations with current/manual capture, name, X/Y/Z, dimension, timestamp and edit/delete controls.
- Retained per-server player history: username, UUID when available, first/last seen and encounter count, while excluding the local account.
- Retained the configurable Server Notes keybind, default **N**, for the current connected server.
- Added a native **Notes book** button to Server Seeker's multiplayer row controls. It has its own slot before Favourite/Auth/Delete and opens that server profile without connecting.
- Removed the standalone `MultiplayerNotesIntegration` runtime layer; Server Seeker now owns row positioning, visibility, scrolling and click handling.
- Kept Server Notes **Important** separate from Server Seeker **Favourite**.
- Preserved Server Seeker's existing Microsoft/Cracked/Unknown authentication detector; Server Notes does not create a second detector.

### Existing beta.2 functionality retained

- Asynchronous Microsoft/Cracked/Unknown classification with 14-day recheck cache.
- Multi-provider Finder, Verified/Quick Search, Auto Add and sequential Auto Join.
- BreakBlocks contribution queue/rate-limit handling and local privacy protections.
- Favourites, Servers, Scanned Servers and Recent Servers category behaviour.
- Existing deletion, undo, whitelist handling, stable-join promotion and ViaFabricPlus integration.

### Validation completed outside Minecraft

- Existing Server Seeker regression suite passes.
- Server Notes schema-v2 load/save, address normalization, backup and corrupt-file recovery tests pass.
- Server Notes player encounter and self-exclusion semantics pass.
- Merged JAR structure/metadata, Java 21 class target, Notes resource, single mod identity and absence of the standalone integration class are verified.

Live Minecraft UI/interaction checks remain part of beta testing.

## v0.4.1-beta.2 — 2026-09-06

### Authentication detection

- Added asynchronous login-phase authentication detection for Finder results and saved Scanned Servers.
- Servers are classified as **Microsoft**, **Cracked**, or **Unknown** without requiring one of the user's Minecraft accounts.
- Authentication probes run in a separate bounded pool of 8 workers with a 3-second network timeout so Finder/status scanning does not wait for them.
- Successful probe results and Unknown outcomes are cached locally for **14 days** before automatic rechecking.
- Added a persistent `config/zazus-server-auth.properties` cache.
- Existing Scanned Servers feed successful health/status results into the classifier so older saved entries are populated progressively.
- Finder result rows now show an `AUTH:` marker and Server Details shows the detected authentication type and last-check time.
- Saved multiplayer rows show a compact `Auth:M`, `Auth:C`, `Auth:?` or pending/not-checked indicator beside Favourite/Delete controls.
- Added a manual **Recheck Auth** control and an **Auth Detection** settings toggle.
- Provider-reported offline-mode metadata remains visible separately from Server Seeker's direct authentication classification.
- Added regression tests covering Microsoft authentication requests, cracked Login Success, offline encryption with `Should Authenticate=false`, and compressed login responses.

## v0.4.1-beta.1 — 2026-09-06

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
