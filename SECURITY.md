# Security policy

## Supported version

Security fixes are provided for the newest published release of Zazu's Server Seeker.

## Reporting a vulnerability

Please use the repository's [private security-advisory form](https://github.com/Zazuzin/Zazus-Server-Scanner/security/advisories/new) rather than opening a public issue. Include the affected version, reproduction steps, expected impact and any relevant logs with API keys and access tokens removed.

Do not publish credentials, private server addresses or another person's personal information in an issue or log attachment.

## Credentials

BreakBlocks API keys belong only in the generated `config/zazus-server-tool.properties` file. The `config/` directory is ignored by Git and must not be included in releases.
