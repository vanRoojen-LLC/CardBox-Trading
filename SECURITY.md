# Security Policy

## Supported Versions

| Version | Supported |
|---|---|
| 5.0.x | Yes |
| < 5.0 | No |

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do not** open a public GitHub issue for security vulnerabilities.
2. Email the maintainers directly or use GitHub's private vulnerability reporting feature (Security tab → Report a vulnerability).
3. Include a description of the vulnerability, steps to reproduce, and any potential impact.

You should receive an acknowledgment within 72 hours. We will work with you to understand the issue and coordinate a fix before any public disclosure.

## Scope

This application runs as a local desktop program. Security concerns include but are not limited to:

- Local data integrity (SQLite database, trade files, configuration)
- Network folder synchronization (shared trade files, buy rates)
- External API communication (Scryfall catalog downloads)
- CSV/JSON import parsing (malformed input handling)
