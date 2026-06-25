# Design: Production-readiness for the AIS connector (v0.3.0)

**Date**: 2026-06-18
**Status**: Approved (design phase)
**Target version**: 0.3.0

## Goal

Make the Kafka Connect AIS source connector production-grade for **both**
deployment targets:

- **Confluent Cloud Custom Connector** — no JMX access; the app-logs topic is the
  only useful failure surface (see README "Deploy to Confluent Cloud Custom
  Connectors").
- **Self-managed Kafka Connect** — full JMX access, operator controls the network.

Design consequence: **logs are the baseline observability surface; JMX is an
optional add-on** carrying the same numbers. Anything that only works via JMX
cannot be the primary mechanism.

## Scope

In scope (agreed):

1. Configurable TCP timeouts (#3)
2. Parser outcome categorization (#2, "rich result type")
3. Observability — counters + periodic structured log + optional JMX (#1)

Explicitly **deferred** (not in this release): source-side error-routing topic
(#4), TLS (#5), rate limiting (#6). Rationale: error routing is a real custom
design (Kafka Connect's native `errors.deadletterqueue` is a *sink*-only
feature and does not apply to source-task-internal parse failures); TLS is
typically handled by the platform/network; rate limiting is speculative.

---

## 1. Configurable TCP timeouts (#3)

Currently hardcoded in `TcpConnectionManager`:

```java
private static final int CONNECT_TIMEOUT_MS = 10000;
private static final int SO_TIMEOUT_MS = 1000;
```

Replace with two new connector configs:

| Config | Type | Default | Notes |
|---|---|---|---|
| `tcp.connect.timeout.ms` | INT | `10000` | socket connect timeout |
| `tcp.socket.timeout.ms` | INT | `1000` | `SO_TIMEOUT`; bounds per-`poll()` read |

- Defined in `AisSourceConnectorConfig` with `ConfigDef`, importance LOW,
  validated as positive integers (a `Range.atLeast(1)` validator).
- Passed from `AisSourceTask` into the `TcpConnectionManager` constructor,
  following the existing pattern (backoff values are already passed this way).
- Used in `connect()` (`socket.connect(addr, connectTimeoutMs)`) and
  `socket.setSoTimeout(soTimeoutMs)`.
- README config table updated with both rows.

**Risk**: low, isolated. No behavior change at default values.

---

## 2. Parser outcome categorization (#2, Option A)

### Problem

`NmeaLineParser.parseLine()` returns `Optional<ParseResult>`. An empty Optional
collapses three semantically different situations into one:

1. Multi-sentence message still being assembled (normal, frequent)
2. A message type we deliberately do not decode (normal)
3. A genuine decode/checksum failure (a real error worth surfacing)

Against the real feed ~12% of lines yield "empty", but almost all of that is
cases 1 and 2. This is why a blanket DEBUG→WARN change (the original Bob
suggestion) is wrong — it would flood logs with benign noise and bury real
errors. Honest error metrics require distinguishing the cases.

### Solution: a sealed `ParseOutcome`

`parseLine()` returns `ParseOutcome` instead of `Optional<ParseResult>`:

| Variant | Meaning | Log level | Metric counter |
|---|---|---|---|
| `Parsed(ParseResult)` | message decoded | (none) | `messagesEmitted` |
| `IncompleteFragment` | multi-sentence assembly in progress | DEBUG | `incompleteFragments` |
| `UnsupportedType` | type intentionally skipped | DEBUG | `unsupportedTypes` |
| `DecodeError(String reason)` | checksum/decode failure | **WARN** (with raw line truncated to ~100 chars) | `decodeErrors` |

Implementation note: the build targets **Java 11** (`pom.xml`:
`<java.version>11</java.version>`), so neither `sealed` (17) nor `record` (16)
is available. Model `ParseOutcome` as an **abstract base class** exposing
`Kind kind()` (enum: `PARSED`, `INCOMPLETE_FRAGMENT`, `UNSUPPORTED_TYPE`,
`DECODE_ERROR`) with four concrete subclasses: `Parsed` (holds `ParseResult`),
`IncompleteFragment`, `UnsupportedType`, and `DecodeError` (holds `String
reason`). `poll()` dispatches on `kind()`. Provide static factory methods and,
where helpful, singletons for the payload-free variants.

`AisSourceTask.poll()` switches on the outcome to decide both the log level and
which counter to increment. This is the **only** user-visible behavior change:
real failures now WARN; benign misses stay at DEBUG as before.

### Files touched
- `NmeaLineParser.java` — return type, new outcome classes, categorize the
  current `catch`/empty paths into the right variants.
- `AisSourceTask.java` — consume `ParseOutcome` in `poll()`.

---

## 3. Observability (#1)

### Counters

A small `TaskMetrics` holder owned by `AisSourceTask`, tracking:

- Cumulative: `messagesEmitted`, `decodeErrors`, `incompleteFragments`,
  `unsupportedTypes`, `reconnects`
- Point-in-time: `fragmentBufferSize` (from `NmeaLineParser`),
  `connectionUptimeMs` (derived from `connectionEpoch`)

Counters are plain `long`s incremented on the single task thread inside
`poll()`. They never throw.

### Log surface (baseline — works everywhere, primary)

One structured INFO heartbeat at a configurable interval:

| Config | Type | Default | Notes |
|---|---|---|---|
| `metrics.log.interval.ms` | LONG | `60000` | `0` disables |

Reuses the existing periodic-logging cadence pattern already used for the
no-data heartbeat (`dueForNoDataLog`-style time check in `poll()`). This is the
only observability available on Cloud Custom Connectors, hence it is primary.

Example line (single structured INFO):

```
AIS task metrics: emitted=12345 decodeErrors=2 incompleteFragments=410 unsupportedTypes=88 reconnects=1 fragmentBuffer=3 uptimeMs=600123
```

### JMX surface (optional — self-managed)

Register the same counters as Kafka Connect metrics via the task's
`MetricGroup` (from the source task context). Pure addition: if the runtime
restricts metric registration (e.g. Cloud), the log surface still works and the
connector must not fail because JMX registration was unavailable — registration
is best-effort and guarded.

### Files touched
- New `TaskMetrics.java`
- `AisSourceTask.java` — own a `TaskMetrics`, increment per outcome, emit the
  periodic log, register JMX (best-effort).
- `NmeaLineParser.java` — expose current fragment-buffer size (getter).
- `AisSourceConnectorConfig.java` — `metrics.log.interval.ms`.
- README config table.

---

## Error handling

No new failure modes are introduced. The parser refactor *reduces* silent drops
by making `DecodeError` visible. Metrics increments cannot throw. JMX
registration is best-effort and guarded so a restricted runtime cannot crash the
task.

## Testing

- `NmeaLineParserTest`: assert each of the four `ParseOutcome` variants from
  crafted inputs — incomplete fragment, unsupported type, bad-checksum →
  `DecodeError`, valid sentence → `Parsed`.
- New `TaskMetricsTest`: counter increments map correctly to each outcome.
- Existing real-data decode test updated to the new return type; assert the
  ~12% non-`Parsed` lines classify as `IncompleteFragment`/`UnsupportedType`
  with **near-zero `DecodeError`**. This assertion is the proof that #2 is
  "done right".
- Config validation tests for the three new configs (positive values).
- Docker smoke test per CLAUDE.md (connector RUNNING, ≥200 records) before
  claiming done; additionally eyeball one `AIS task metrics:` log line.

## Versioning

- Bump to **0.3.0** (new features, backward-compatible config additions).
- Update all version locations: `pom.xml`, `AisSourceConnector.version()`,
  `AisSourceTask.version()`, README artifact-name references.
- CHANGELOG `[0.3.0]` entry under Added / Changed.

## Out of scope (deferred)

- Source-side error-routing topic (#4) — needs a deliberate design and an
  honest name (e.g. `parse.errors.topic.name`), not the misleading
  `errors.deadletterqueue.*`.
- TLS (#5) — platform/network concern for now.
- Rate limiting (#6) — speculative.
