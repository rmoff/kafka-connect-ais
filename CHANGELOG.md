# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.1] - 2026-06-08

### Changed
- **`exactlyOnceSupport` now reports `UNSUPPORTED`** (was `SUPPORTED`). This is
  honest: the connector reads a live TCP feed that has no replay, and the task's
  source offsets (`connection_epoch` / `message_count`) reset on every reconnect,
  so exactly-once delivery cannot be guaranteed — it is best-effort at-least-once.

### Removed
- Unused `NmeaLineParser.normalizeTalkerId()` (and its now-orphaned
  `TALKER_PATTERN`). The `dk.dma` parser handles `!BSVDM` natively; the method
  was only referenced by a test.

## [0.2.0] - 2026-06-08

First tagged release. Fixes a production incident where the connector silently
stopped delivering data for ~34 hours while still reporting `RUNNING`.

### Fixed
- **Silent stall on a half-open TCP feed.** `TcpConnectionManager.isConnected()`
  relied on `Socket.isConnected()`, which stays `true` once connected and never
  reflects a peer that has silently gone away (idle NAT/firewall drop). The task
  therefore never reconnected when the upstream feed went quiet — it sat
  `RUNNING` with zero records and zero logs. Now detected via an idle-data
  watchdog (see Added).
- **100% CPU busy-spin while disconnected.** `poll()` returned `null` without
  blocking on the reconnect-backoff path; Kafka Connect re-invokes `poll()`
  immediately on `null`, pinning a CPU core (measured at ~75M calls/sec in a
  reproduction test) and exhausting heap via allocation churn. `poll()` now
  paces itself (sleeps briefly) before returning `null` on every no-data path.

### Added
- **`idle.timeout.ms`** config (default `60000`). If no data arrives from the
  feed within this window, the connection is treated as dead and reconnected.
  Set to `0` to disable. A `WARN` is logged when the watchdog fires, so a future
  stall is visible in `confluent connect logs` (the previous failure logged
  nothing).
- TCP keepalive (`SO_KEEPALIVE`) on the feed socket (defense in depth).
- Tests: `AisSourceTaskReconnectTest` (reproduces + verifies both bugs),
  `TcpConnectionManagerTest` (idle/staleness logic), and
  `AisConverterRealDataTest` (runs ~450 real captured AIS sentences through the
  full parse→convert path to guard the type-specific decoders against silent
  regressions). Suite: 15 → 20 tests.

### Changed
- `version()` now reports `0.2.0` (was hardcoded `0.1.0`).

### Known issues / follow-ups
- `exactlyOnceSupport` still reports `SUPPORTED` although task source offsets
  reset on every reconnect; the live TCP feed has no replay, so this is really
  "no duplicates within a connection". To be revisited.
- `NmeaLineParser.normalizeTalkerId()` is currently unused (the `dk.dma` parser
  handles `!BSVDM` directly).

[0.2.1]: https://github.com/rmoff/kafka-connect-ais/releases/tag/v0.2.1
[0.2.0]: https://github.com/rmoff/kafka-connect-ais/releases/tag/v0.2.0
