# Zazu's Server Seeker v0.4.1-beta.1

The second public beta for Minecraft 26.2 and Fabric Loader 0.19.3.

## Highlights

- Added an optional BreakBlocks contribution system for double-verified public discoveries and stable successful joins.
- Added a persistent, rate-aware contribution queue with ten-second refresh follow-ups, `429` recovery, six-hour cooldowns and Retry Failed support.
- Added Contribution Stats and CSV audit logging without exposing the BreakBlocks API key.
- Added private, loopback, link-local and LAN protection; confirmed local endpoints remain on the user's device.
- Improved the Finder layout, settings descriptions, tooltips and statistics screens.
- Fixed stable joins occasionally reusing the previous connection address when promoting a Scanned Server.
- Added the current server address and Copy IP control to the pause menu.
- Improved category count refreshes and resolved Refresh/Back layout overlap at affected resolutions.
- Expanded automated regression coverage for providers, authentication, rate limiting, status checks, categories, deletion recovery and private/LAN routing.

## Beta note

This is a beta release. Back up `servers.dat` before testing and report reproducible issues with the Minecraft `latest.log`, selected Finder settings and screenshots where relevant.

Support and bug reports are available in the dedicated Server Seeker section of the [Zazu's EyeBot Network Discord](https://discord.gg/TC4PhPx9sf). Zazuzin can also be found in the [BreakBlocks Discord](https://breakblocks.com/discord).

## Credits

BreakBlocks provides the backbone of the server-discovery workflow: [breakblocks.com](https://breakblocks.com) and [breakblocks.com/discord](https://breakblocks.com/discord).

## License

GNU General Public License v3.0 only.
