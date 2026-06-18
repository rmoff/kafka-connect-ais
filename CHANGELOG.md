# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] - 2026-06-18

### Added
- **Observability**: per-task counters (messages emitted, decode errors, incomplete
  fragments, unsupported types, reconnects, fragment-buffer size, connection uptime),
  emitted as a one-line INFO heartbeat every `metrics.log.interval.ms` (default 60000,
  0 disables) and registered as a JMX MBean (best-effort) for self-managed workers.
- **`tcp.connect.timeout.ms`** (default 10000) and **`tcp.socket.timeout.ms`**
  (default 1000): the previously hardcoded TCP timeouts are now configurable.

### Changed
- **Parse outcomes are now categorized** (`Parsed` / `IncompleteFragment` /
  `UnsupportedType` / `DecodeError`). Genuine decode/checksum failures now log at WARN
  (with the truncated raw line); benign cases (in-progress fragments, unsupported
  message types) stay at DEBUG. Previously every non-result was indistinguishable.

## [0.2.2] - 2026-06-18

### Security
- **Bounded the TCP line read.** `TcpConnectionManager.readLine()` previously used
  `BufferedReader.readLine()`, which buffers without limit — a feed (or MITM) sending an
  unterminated line could exhaust the heap. Reading is now char-by-char with a 1024-byte
  cap, throwing and dropping the connection past the limit. A partial line is carried in a
  persistent buffer so a mid-line `SocketTimeoutException` resumes correctly rather than
  corrupting the next sentence.
- **Capped in-flight multi-sentence fragments.** `NmeaLineParser` used an unbounded
  `HashMap`; a flood of never-completed fragment sentences could grow it faster than the
  time-based cleanup expires them. Now a size-capped `LinkedHashMap` (max 1000, LRU
  eviction with a warning).

### Fixed
- **Thread-safety on the connection.** `socket` and `reader` in `TcpConnectionManager` are
  now `volatile`; `stop()` (Connect thread) and `poll()` (task thread) access them across
  threads.

### Documentation
- README exactly-once description rewritten to state `UNSUPPORTED`, matching the code (the
  prose had lagged the 0.2.1 code change and was internally self-contradictory).
- Documented `idle.timeout.ms` and `no.data.log.interval.ms` in the README config table.

## [0.2.1] - 2026-06-09

### Fixed
- **Multi-reconnect bug: the connector could only reconnect once.**
  `attemptReconnect()` called `close()`, which sets the `stopping` flag (intended
  for task shutdown); the `if (stopping) return false` guard then blocked every
  subsequent reconnect. Against a feed that hands out silent/starved connections
  (accepts the socket but sends no data), the connector would reconnect once, draw
  another silent connection, and then sit `RUNNING` with zero data forever.
  Reconnect cleanup now uses `disconnect()` (which does not set `stopping`).
  This is the true cause of the v0.2.0 deploys that never produced; v0.2.0 should
  be considered superseded.

### Added
- **`no.data.log.interval.ms`** (default 30000): while connected but receiving no
  data, log an INFO heartbeat at most this often, so a starved/silent feed
  connection is visible in the logs instead of looking like a healthy idle
  connector. Set to 0 to disable.
- Test harness `FakeAisFeed` (LIVE / SILENT, per-connection control) and
  `AisDeadFeedTest` covering live, permanently-silent (reconnect without
  busy-spin), and starved-then-live recovery — the last of which is what caught
  the multi-reconnect bug above (the previous reconnect test only ever exercised a
  single reconnect, so it passed despite the bug).

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

[0.3.0]: https://github.com/rmoff/kafka-connect-ais/releases/tag/v0.3.0
[0.2.2]: https://github.com/rmoff/kafka-connect-ais/releases/tag/v0.2.2
[0.2.1]: https://github.com/rmoff/kafka-connect-ais/releases/tag/v0.2.1
[0.2.0]: https://github.com/rmoff/kafka-connect-ais/releases/tag/v0.2.0
