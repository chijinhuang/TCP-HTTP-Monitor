<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# TCP/HTTP Monitor plugin Changelog

## Unreleased

## 1.0.1

### Added

- Initial release: TCP/HTTP monitoring plugin for IntelliJ IDEA.
- TCP Monitor: forward raw TCP connections with full byte-level logging, ideal for debugging database, messaging, or custom binary protocols.
- HTTP Monitor: intercept HTTP requests and responses with detailed header and payload inspection, including Host header rewriting and Location header rewriting support.
- TLS/SSL Support: enable TLS on the incoming side, the target side, or both, with configurable certificate trust options.
- Multi-Tab Management: run multiple monitor instances simultaneously, each in its own tab with independent configuration and logs.
- Connection Filtering: quickly filter connections by keyword to locate the traffic you care about.
- Persistent Configuration: save monitor configurations to disk so they are automatically restored when you reopen the IDE.
- Custom Encoding: configure the character encoding used to display TCP payloads for human-readable output.
