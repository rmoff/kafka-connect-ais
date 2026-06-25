# Production-readiness (v0.3.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the AIS source connector production-grade with configurable TCP timeouts, categorized parse outcomes (so only real failures warn), and counter-based observability (periodic log + best-effort JMX), working on both Confluent Cloud Custom Connector and self-managed Connect.

**Architecture:** Three largely independent changes. (1) Two new timeout configs threaded into `TcpConnectionManager`. (2) `NmeaLineParser.parseLine()` returns a new `ParseOutcome` (abstract base + 4 subclasses, Java 11 — no `sealed`/`record`) instead of `Optional<ParseResult>`; `AisSourceTask.poll()` switches on it for log level and metric counting. (3) A `TaskMetrics` MBean owned by `AisSourceTask`, emitted as a periodic INFO line (primary, works on Cloud) and registered as a JMX MBean best-effort (self-managed).

**Tech Stack:** Java 11, Maven, Kafka Connect API, AisLib (dk.dma.ais), JUnit 5, SLF4J, `java.lang.management` JMX.

**Spec:** `docs/superpowers/specs/2026-06-18-production-readiness-design.md`

**Pre-existing facts (verified):**
- `pom.xml`: `<java.version>11</java.version>` — no `sealed`/`record`.
- `NmeaLineParser` already has `public int getFragmentCount()` (line 208).
- `TcpConnectionManager` constructor: `(String host, int port, long initialBackoffMs, long maxBackoffMs)`. Hardcoded `CONNECT_TIMEOUT_MS=10000` (used at line 50), `SO_TIMEOUT_MS=1000` (line 51).
- `TcpConnectionManager` is constructed in `AisSourceTask.start()` (line 61) and in `TcpConnectionManagerTest` lines 35, 57.
- `parseLine` is called in: `NmeaLineParserTest`, `AisConverterRealDataTest`, and `AisRecordConverterTest` uses a `parseLine` result via a helper. All must migrate to `ParseOutcome`.
- Config defaults already present: `IDLE_TIMEOUT_MS`=60000, `NO_DATA_LOG_INTERVAL_MS`=30000, `FRAGMENT_TIMEOUT_MS`=30000.
- Current version string `0.2.2` in `pom.xml`, `AisSourceConnector.version()`, `AisSourceTask.version()`.

---

## File Structure

**Create:**
- `src/test/java/net/rmoff/connect/ais/TaskMetricsTest.java` — unit tests for the metrics counters/MBean.
- `src/main/java/net/rmoff/connect/ais/TaskMetrics.java` — counter holder + JMX MBean impl.
- `src/main/java/net/rmoff/connect/ais/TaskMetricsMBean.java` — JMX MBean interface.

**Modify:**
- `src/main/java/net/rmoff/connect/ais/AisSourceConnectorConfig.java` — 3 new configs.
- `src/main/java/net/rmoff/connect/ais/TcpConnectionManager.java` — timeout fields from constructor.
- `src/main/java/net/rmoff/connect/ais/NmeaLineParser.java` — `ParseOutcome` return type + nested outcome classes.
- `src/main/java/net/rmoff/connect/ais/AisSourceTask.java` — pass timeouts, consume `ParseOutcome`, own `TaskMetrics`, periodic metrics log, JMX register/unregister.
- `src/test/java/net/rmoff/connect/ais/NmeaLineParserTest.java` — migrate to `ParseOutcome`.
- `src/test/java/net/rmoff/connect/ais/AisRecordConverterTest.java` — migrate to `ParseOutcome`.
- `src/test/java/net/rmoff/connect/ais/AisConverterRealDataTest.java` — migrate + add categorization assertion.
- `src/test/java/net/rmoff/connect/ais/TcpConnectionManagerTest.java` — constructor arg updates.
- `README.adoc` — 3 new config rows, version refs.
- `CHANGELOG.md` — `[0.3.0]` entry.
- `pom.xml`, `AisSourceConnector.java` — version bump.

---

## Task 1: Configurable TCP timeouts (#3)

**Files:**
- Modify: `src/main/java/net/rmoff/connect/ais/AisSourceConnectorConfig.java`
- Modify: `src/main/java/net/rmoff/connect/ais/TcpConnectionManager.java`
- Modify: `src/main/java/net/rmoff/connect/ais/AisSourceTask.java:61-65`
- Modify: `src/test/java/net/rmoff/connect/ais/TcpConnectionManagerTest.java:35,57`
- Test: `src/test/java/net/rmoff/connect/ais/AisSourceConnectorTest.java`

- [ ] **Step 1: Write the failing config test**

Add to `AisSourceConnectorTest.java`:

```java
    @Test
    void tcpTimeoutConfigsHaveDefaults() {
        Map<String, String> props = new HashMap<>();
        props.put(AisSourceConnectorConfig.AIS_HOSTS_CONFIG, "host:5000");
        props.put(AisSourceConnectorConfig.TOPIC_CONFIG, "ais");
        AisSourceConnectorConfig config = new AisSourceConnectorConfig(props);
        assertEquals(10000, config.getInt(AisSourceConnectorConfig.CONNECT_TIMEOUT_MS_CONFIG));
        assertEquals(1000, config.getInt(AisSourceConnectorConfig.SO_TIMEOUT_MS_CONFIG));
    }

    @Test
    void rejectsNonPositiveTcpTimeouts() {
        Map<String, String> props = new HashMap<>();
        props.put(AisSourceConnectorConfig.AIS_HOSTS_CONFIG, "host:5000");
        props.put(AisSourceConnectorConfig.TOPIC_CONFIG, "ais");
        props.put(AisSourceConnectorConfig.CONNECT_TIMEOUT_MS_CONFIG, "0");
        assertThrows(org.apache.kafka.common.config.ConfigException.class,
                () -> new AisSourceConnectorConfig(props));
    }
```

Ensure imports exist in the test file: `java.util.HashMap`, `java.util.Map`, `static org.junit.jupiter.api.Assertions.*`. (Check the file header; add any missing.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=AisSourceConnectorTest`
Expected: FAIL — `CONNECT_TIMEOUT_MS_CONFIG` does not exist (compile error).

- [ ] **Step 3: Add the configs**

In `AisSourceConnectorConfig.java`, after the `DECODE_COMMON_ONLY_*` constants (around line 48) add:

```java
    public static final String CONNECT_TIMEOUT_MS_CONFIG = "tcp.connect.timeout.ms";
    private static final String CONNECT_TIMEOUT_MS_DOC =
            "TCP connect timeout in milliseconds.";

    public static final String SO_TIMEOUT_MS_CONFIG = "tcp.socket.timeout.ms";
    private static final String SO_TIMEOUT_MS_DOC =
            "Socket read timeout (SO_TIMEOUT) in milliseconds; bounds how long a single "
            + "poll() read blocks waiting for data.";
```

In the `CONFIG_DEF` builder, before the final `DECODE_COMMON_ONLY_CONFIG` `.define(...)` add:

```java
            .define(CONNECT_TIMEOUT_MS_CONFIG, ConfigDef.Type.INT, 10000,
                    ConfigDef.Range.atLeast(1), ConfigDef.Importance.LOW, CONNECT_TIMEOUT_MS_DOC)
            .define(SO_TIMEOUT_MS_CONFIG, ConfigDef.Type.INT, 1000,
                    ConfigDef.Range.atLeast(1), ConfigDef.Importance.LOW, SO_TIMEOUT_MS_DOC)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=AisSourceConnectorTest`
Expected: PASS.

- [ ] **Step 5: Thread timeouts through TcpConnectionManager**

In `TcpConnectionManager.java`:

Delete the two hardcoded constants (lines 17-18):
```java
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int SO_TIMEOUT_MS = 1000;
```
Keep `MAX_LINE_LENGTH`. Add fields near the other finals:
```java
    private final int connectTimeoutMs;
    private final int soTimeoutMs;
```
Change the constructor signature and body:
```java
    public TcpConnectionManager(String host, int port, long initialBackoffMs, long maxBackoffMs,
                                int connectTimeoutMs, int soTimeoutMs) {
        this.host = host;
        this.port = port;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.currentBackoffMs = initialBackoffMs;
        this.nextReconnectTime = 0;
        this.connectTimeoutMs = connectTimeoutMs;
        this.soTimeoutMs = soTimeoutMs;
    }
```
In `connect()` replace the two timeout uses:
```java
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(soTimeoutMs);
```

- [ ] **Step 6: Update the construction site in AisSourceTask**

In `AisSourceTask.start()` replace the `new TcpConnectionManager(...)` block (lines 61-65):
```java
        connection = new TcpConnectionManager(
                host, port,
                config.getLong(AisSourceConnectorConfig.RECONNECT_BACKOFF_INITIAL_MS_CONFIG),
                config.getLong(AisSourceConnectorConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG),
                config.getInt(AisSourceConnectorConfig.CONNECT_TIMEOUT_MS_CONFIG),
                config.getInt(AisSourceConnectorConfig.SO_TIMEOUT_MS_CONFIG)
        );
```

- [ ] **Step 7: Update TcpConnectionManagerTest constructor calls**

In `TcpConnectionManagerTest.java` line 35 and line 57, add the two new args (use the prior hardcoded defaults so behavior is unchanged):
```java
            TcpConnectionManager conn = new TcpConnectionManager("127.0.0.1", port, 100, 1000, 10000, 1000);
```
```java
        TcpConnectionManager conn = new TcpConnectionManager("127.0.0.1", 1, 100, 1000, 10000, 1000);
```

- [ ] **Step 8: Run the full test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all existing tests still pass.

- [ ] **Step 9: Document the configs in README**

In `README.adoc`, in the config table (after the `fragment.timeout.ms` row block, before the `idle.timeout.ms` rows added earlier), add:
```adoc
|`tcp.connect.timeout.ms`
|INT
|`10000`
|TCP connect timeout in milliseconds.

|`tcp.socket.timeout.ms`
|INT
|`1000`
|Socket read timeout (SO_TIMEOUT) in ms; bounds how long a single poll() read blocks.
```

- [ ] **Step 10: Commit**

```bash
git add src/main/java/net/rmoff/connect/ais/AisSourceConnectorConfig.java \
        src/main/java/net/rmoff/connect/ais/TcpConnectionManager.java \
        src/main/java/net/rmoff/connect/ais/AisSourceTask.java \
        src/test/java/net/rmoff/connect/ais/TcpConnectionManagerTest.java \
        src/test/java/net/rmoff/connect/ais/AisSourceConnectorTest.java \
        README.adoc
git commit -m "feat: configurable TCP connect/socket timeouts"
```

---

## Task 2: ParseOutcome categorization (#2)

**Files:**
- Modify: `src/main/java/net/rmoff/connect/ais/NmeaLineParser.java`
- Modify: `src/test/java/net/rmoff/connect/ais/NmeaLineParserTest.java`
- Modify: `src/test/java/net/rmoff/connect/ais/AisRecordConverterTest.java`
- Modify: `src/test/java/net/rmoff/connect/ais/AisConverterRealDataTest.java`
- Modify: `src/main/java/net/rmoff/connect/ais/AisSourceTask.java:136-145`

### Outcome categorization rules (authoritative)

| Situation in current `parseLine` | New outcome |
|---|---|
| null/empty line | `IncompleteFragment` (benign; nothing to decode) |
| too few fields / invalid sentence numbering | `DecodeError("malformed NMEA")` / `DecodeError("invalid sentence numbering")` |
| single sentence, `vdm.parse` returns != 0 | `DecodeError("vdm parse result N")` |
| single/multi sentence, `vdm.parse` throws `SentenceException` | `DecodeError(<msg>)` |
| `AisMessage.getInstance` throws `AisMessageException`/`SixbitException` | `UnsupportedType` |
| any other exception | `DecodeError(<exception msg>)` |
| multi-sentence first fragment stored | `IncompleteFragment` |
| continuation without stored first part | `IncompleteFragment` |
| continuation, `vdm.parse` != 0 (need more) | `IncompleteFragment` |
| complete message decoded | `Parsed(result)` |

- [ ] **Step 1: Write the failing parser outcome test**

Replace the body of `NmeaLineParserTest.java` migrating existing assertions and adding category assertions. Replace the existing tests as follows (keep the constants and `setUp`):

```java
    @Test
    void parsesSingleSentenceWithTagBlock() {
        NmeaLineParser.ParseOutcome outcome = parser.parseLine(TYPE1_WITH_TAG);
        assertEquals(NmeaLineParser.ParseOutcome.Kind.PARSED, outcome.kind());
        NmeaLineParser.ParseResult parsed = ((NmeaLineParser.Parsed) outcome).result;
        assertEquals("2573305", parsed.sourceStation);
        assertEquals(1774373593000L, parsed.receiveTimestampMs);
        assertEquals(1, parsed.message.getMsgId());
        assertEquals(257230800, parsed.message.getUserId());
    }

    @Test
    void parsesSingleSentenceWithoutTagBlock() {
        NmeaLineParser.ParseOutcome outcome = parser.parseLine(TYPE1_BARE);
        assertEquals(NmeaLineParser.ParseOutcome.Kind.PARSED, outcome.kind());
        NmeaLineParser.ParseResult parsed = ((NmeaLineParser.Parsed) outcome).result;
        assertNull(parsed.sourceStation);
        assertEquals(1, parsed.message.getMsgId());
    }

    @Test
    void handlesMultiSentenceMessages() {
        NmeaLineParser.ParseOutcome r1 = parser.parseLine(TYPE5_SENT1);
        assertEquals(NmeaLineParser.ParseOutcome.Kind.INCOMPLETE_FRAGMENT, r1.kind());
        assertEquals(1, parser.getFragmentCount());

        NmeaLineParser.ParseOutcome r2 = parser.parseLine(TYPE5_SENT2);
        assertEquals(NmeaLineParser.ParseOutcome.Kind.PARSED, r2.kind());
        assertEquals(0, parser.getFragmentCount());
        assertEquals(5, ((NmeaLineParser.Parsed) r2).result.message.getMsgId());
    }

    @Test
    void nullAndEmptyAreIncompleteFragment() {
        assertEquals(NmeaLineParser.ParseOutcome.Kind.INCOMPLETE_FRAGMENT, parser.parseLine(null).kind());
        assertEquals(NmeaLineParser.ParseOutcome.Kind.INCOMPLETE_FRAGMENT, parser.parseLine("").kind());
    }

    @Test
    void malformedLinesAreDecodeError() {
        assertEquals(NmeaLineParser.ParseOutcome.Kind.DECODE_ERROR, parser.parseLine("garbage data").kind());
        assertEquals(NmeaLineParser.ParseOutcome.Kind.DECODE_ERROR, parser.parseLine("!AIVDM,bad").kind());
    }
```

For the stale-fragment test further down (lines ~80-87), replace the `parseLine(frag1)` result handling to use `.kind()` and keep the `getFragmentCount()` assertions unchanged. Concretely, change `Optional<NmeaLineParser.ParseResult> r = shortTimeoutParser.parseLine(frag1);` style lines to `shortTimeoutParser.parseLine(frag1);` (ignore the return) — the test only asserts fragment counts. Remove the now-unused `import java.util.Optional;`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=NmeaLineParserTest`
Expected: FAIL — `ParseOutcome` / `Parsed` / `kind()` do not exist (compile error).

- [ ] **Step 3: Add the ParseOutcome types to NmeaLineParser**

In `NmeaLineParser.java`, add imports:
```java
import dk.dma.ais.message.AisMessageException;
import dk.dma.ais.binary.SixbitException;
```
Remove `import java.util.Optional;`.

Add these nested classes (alongside the existing `ParseResult` nested class):
```java
    /** Categorized outcome of parsing one raw line. */
    public abstract static class ParseOutcome {
        public enum Kind { PARSED, INCOMPLETE_FRAGMENT, UNSUPPORTED_TYPE, DECODE_ERROR }
        public abstract Kind kind();

        public static final IncompleteFragment INCOMPLETE = new IncompleteFragment();
        public static final UnsupportedType UNSUPPORTED = new UnsupportedType();
    }

    public static final class Parsed extends ParseOutcome {
        public final ParseResult result;
        public Parsed(ParseResult result) { this.result = result; }
        @Override public Kind kind() { return Kind.PARSED; }
    }

    public static final class IncompleteFragment extends ParseOutcome {
        @Override public Kind kind() { return Kind.INCOMPLETE_FRAGMENT; }
    }

    public static final class UnsupportedType extends ParseOutcome {
        @Override public Kind kind() { return Kind.UNSUPPORTED_TYPE; }
    }

    public static final class DecodeError extends ParseOutcome {
        public final String reason;
        public DecodeError(String reason) { this.reason = reason; }
        @Override public Kind kind() { return Kind.DECODE_ERROR; }
    }
```

- [ ] **Step 4: Rewrite parseLine and helpers to return ParseOutcome**

Replace `parseLine`, `parseSingleSentence`, `parseMultiSentence` with:

```java
    public ParseOutcome parseLine(String line) {
        if (line == null || line.isEmpty()) {
            return ParseOutcome.INCOMPLETE;
        }

        String sourceStation = null;
        long receiveTimestampMs = System.currentTimeMillis();
        String nmeaSentence;

        Matcher tagMatcher = TAG_BLOCK_PATTERN.matcher(line);
        if (tagMatcher.matches()) {
            String tagContent = tagMatcher.group(1);
            nmeaSentence = tagMatcher.group(2);
            for (String field : tagContent.split(",")) {
                if (field.startsWith("s:")) {
                    sourceStation = field.substring(2);
                } else if (field.startsWith("c:")) {
                    String tsStr = field.substring(2);
                    int starIdx = tsStr.indexOf('*');
                    if (starIdx >= 0) {
                        tsStr = tsStr.substring(0, starIdx);
                    }
                    try {
                        receiveTimestampMs = Long.parseLong(tsStr) * 1000L;
                    } catch (NumberFormatException e) {
                        log.warn("Invalid tag block timestamp: {}", tsStr);
                    }
                }
            }
        } else {
            nmeaSentence = line;
        }

        String[] fields = nmeaSentence.split(",", 7);
        if (fields.length < 6) {
            log.debug("Malformed NMEA sentence (too few fields): {}", nmeaSentence);
            return new DecodeError("malformed NMEA (too few fields)");
        }

        int numSentences;
        int sentenceNum;
        try {
            numSentences = Integer.parseInt(fields[1]);
            sentenceNum = Integer.parseInt(fields[2]);
        } catch (NumberFormatException e) {
            log.debug("Invalid sentence numbering: {}", nmeaSentence);
            return new DecodeError("invalid sentence numbering");
        }

        try {
            if (numSentences == 1) {
                return parseSingleSentence(nmeaSentence, sourceStation, receiveTimestampMs, line);
            } else {
                return parseMultiSentence(nmeaSentence, sentenceNum, fields, sourceStation, receiveTimestampMs, line);
            }
        } catch (AisMessageException | SixbitException e) {
            log.debug("Unsupported AIS message type: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            return ParseOutcome.UNSUPPORTED;
        } catch (Exception e) {
            log.debug("Failed to parse AIS message: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            return new DecodeError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ParseOutcome parseSingleSentence(String nmea, String station, long timestampMs, String rawLine)
            throws Exception {
        Vdm vdm = new Vdm();
        int result = vdm.parse(nmea);
        if (result != 0) {
            log.debug("Unexpected parse result {} for single sentence: {}", result, nmea);
            return new DecodeError("vdm parse result " + result);
        }
        AisMessage msg = AisMessage.getInstance(vdm);
        return new Parsed(new ParseResult(msg, station, timestampMs, rawLine));
    }

    private ParseOutcome parseMultiSentence(String nmea, int sentenceNum, String[] fields,
                                            String station, long timestampMs, String rawLine)
            throws Exception {
        String channel = fields.length > 4 ? fields[4] : "";
        String seqId = fields[3];
        String fragKey = channel + ":" + seqId;

        if (sentenceNum == 1) {
            Vdm vdm = new Vdm();
            vdm.parse(nmea);
            fragments.put(fragKey, new FragmentEntry(vdm, rawLine, station, timestampMs));
            return ParseOutcome.INCOMPLETE;
        } else {
            FragmentEntry entry = fragments.remove(fragKey);
            if (entry == null) {
                log.debug("Received continuation fragment without first part, key={}", fragKey);
                return ParseOutcome.INCOMPLETE;
            }
            int result = entry.vdm.parse(nmea);
            if (result != 0) {
                fragments.put(fragKey, entry);
                return ParseOutcome.INCOMPLETE;
            }
            AisMessage msg = AisMessage.getInstance(entry.vdm);
            String fullRaw = entry.firstLineRaw + "\n" + rawLine;
            return new Parsed(new ParseResult(msg, entry.sourceStation, entry.receiveTimestampMs, fullRaw));
        }
    }
```

Note: `AisMessage.getInstance` is what throws `AisMessageException`/`SixbitException`; the `try` in `parseLine` wraps both helpers, so both single- and multi-sentence unsupported types are categorized correctly.

- [ ] **Step 5: Run parser test to verify it passes**

Run: `mvn -q test -Dtest=NmeaLineParserTest`
Expected: PASS. If `AisMessageException`/`SixbitException` import paths are wrong, the compiler will say so — correct the package (they are `dk.dma.ais.message.AisMessageException` and `dk.dma.ais.binary.SixbitException` in AisLib).

- [ ] **Step 6: Migrate AisRecordConverterTest**

In `AisRecordConverterTest.java`, the three `NmeaLineParser.ParseResult parsed = ...parseLine(...)...` sites (lines ~29, 51, 62) currently obtain a `ParseResult`. Change each to:
```java
        NmeaLineParser.ParseResult parsed =
                ((NmeaLineParser.Parsed) parser.parseLine(<sameArg>)).result;
```
Use whatever local parser variable / argument each call already uses (do not change the inputs). Remove any now-unused `Optional` import.

- [ ] **Step 7: Migrate AisConverterRealDataTest + add categorization assertion**

In `AisConverterRealDataTest.java`, find the loop calling `parser.parseLine(line)`. Replace the result handling so it switches on the outcome and tallies categories. Replace the per-line body with:
```java
            NmeaLineParser.ParseOutcome outcome = parser.parseLine(line);
            switch (outcome.kind()) {
                case PARSED:
                    parsed++;
                    NmeaLineParser.ParseResult pr = ((NmeaLineParser.Parsed) outcome).result;
                    // (retain any existing per-message accounting that used the ParseResult here)
                    break;
                case INCOMPLETE_FRAGMENT: incomplete++; break;
                case UNSUPPORTED_TYPE:    unsupported++; break;
                case DECODE_ERROR:        decodeErrors++; break;
            }
```
Declare `int incomplete = 0, unsupported = 0, decodeErrors = 0;` next to the existing `parsed` counter. After the loop add the load-bearing assertion that proves #2 is "done right":
```java
        System.out.printf("Categorized: parsed=%d incomplete=%d unsupported=%d decodeErrors=%d%n",
                parsed, incomplete, unsupported, decodeErrors);
        assertTrue(decodeErrors <= 5,
                "Real feed data should yield near-zero decode errors, got " + decodeErrors);
```
Ensure `import static org.junit.jupiter.api.Assertions.assertTrue;` is present.

- [ ] **Step 8: Consume ParseOutcome in AisSourceTask.poll()**

In `AisSourceTask.poll()`, replace the parse block (lines 136-145):
```java
                NmeaLineParser.ParseOutcome outcome = parser.parseLine(line);
                switch (outcome.kind()) {
                    case PARSED:
                        messageCount++;
                        Map<String, Object> sourceOffset = new HashMap<>();
                        sourceOffset.put("connection_epoch", connectionEpoch);
                        sourceOffset.put("message_count", messageCount);
                        NmeaLineParser.ParseResult pr = ((NmeaLineParser.Parsed) outcome).result;
                        records.add(converter.convert(pr, sourcePartition, sourceOffset));
                        break;
                    case DECODE_ERROR:
                        log.warn("AIS decode error: {} | raw: {}",
                                ((NmeaLineParser.DecodeError) outcome).reason, truncate(line, 100));
                        break;
                    case UNSUPPORTED_TYPE:
                    case INCOMPLETE_FRAGMENT:
                    default:
                        break; // benign; counted in Task 3
                }
```
Add a private helper at the bottom of the class:
```java
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
```

- [ ] **Step 9: Run the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS; `AisConverterRealDataTest` prints the categorized line and passes the `decodeErrors <= 5` assertion.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/net/rmoff/connect/ais/NmeaLineParser.java \
        src/main/java/net/rmoff/connect/ais/AisSourceTask.java \
        src/test/java/net/rmoff/connect/ais/NmeaLineParserTest.java \
        src/test/java/net/rmoff/connect/ais/AisRecordConverterTest.java \
        src/test/java/net/rmoff/connect/ais/AisConverterRealDataTest.java
git commit -m "feat: categorize parse outcomes; WARN only on real decode errors"
```

---

## Task 3: Observability — TaskMetrics + periodic log + JMX (#1)

**Files:**
- Create: `src/main/java/net/rmoff/connect/ais/TaskMetricsMBean.java`
- Create: `src/main/java/net/rmoff/connect/ais/TaskMetrics.java`
- Create: `src/test/java/net/rmoff/connect/ais/TaskMetricsTest.java`
- Modify: `src/main/java/net/rmoff/connect/ais/AisSourceConnectorConfig.java`
- Modify: `src/main/java/net/rmoff/connect/ais/AisSourceTask.java`
- Modify: `README.adoc`

- [ ] **Step 1: Write the failing TaskMetrics test**

Create `src/test/java/net/rmoff/connect/ais/TaskMetricsTest.java`:
```java
package net.rmoff.connect.ais;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskMetricsTest {

    @Test
    void countersStartAtZero() {
        TaskMetrics m = new TaskMetrics();
        assertEquals(0, m.getMessagesEmitted());
        assertEquals(0, m.getDecodeErrors());
        assertEquals(0, m.getIncompleteFragments());
        assertEquals(0, m.getUnsupportedTypes());
        assertEquals(0, m.getReconnects());
    }

    @Test
    void recordOutcomeIncrementsCorrectCounter() {
        TaskMetrics m = new TaskMetrics();
        m.recordOutcome(NmeaLineParser.ParseOutcome.Kind.PARSED);
        m.recordOutcome(NmeaLineParser.ParseOutcome.Kind.PARSED);
        m.recordOutcome(NmeaLineParser.ParseOutcome.Kind.DECODE_ERROR);
        m.recordOutcome(NmeaLineParser.ParseOutcome.Kind.INCOMPLETE_FRAGMENT);
        m.recordOutcome(NmeaLineParser.ParseOutcome.Kind.UNSUPPORTED_TYPE);
        assertEquals(2, m.getMessagesEmitted());
        assertEquals(1, m.getDecodeErrors());
        assertEquals(1, m.getIncompleteFragments());
        assertEquals(1, m.getUnsupportedTypes());
    }

    @Test
    void reconnectCounterIncrements() {
        TaskMetrics m = new TaskMetrics();
        m.recordReconnect();
        m.recordReconnect();
        assertEquals(2, m.getReconnects());
    }

    @Test
    void summaryLineContainsAllCounters() {
        TaskMetrics m = new TaskMetrics();
        m.recordOutcome(NmeaLineParser.ParseOutcome.Kind.PARSED);
        String s = m.summary(3, 12345L);
        assertTrue(s.contains("emitted=1"), s);
        assertTrue(s.contains("fragmentBuffer=3"), s);
        assertTrue(s.contains("uptimeMs=12345"), s);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=TaskMetricsTest`
Expected: FAIL — `TaskMetrics` does not exist.

- [ ] **Step 3: Create the MBean interface**

Create `src/main/java/net/rmoff/connect/ais/TaskMetricsMBean.java`:
```java
package net.rmoff.connect.ais;

/** JMX view of a task's runtime counters. */
public interface TaskMetricsMBean {
    long getMessagesEmitted();
    long getDecodeErrors();
    long getIncompleteFragments();
    long getUnsupportedTypes();
    long getReconnects();
}
```

- [ ] **Step 4: Create TaskMetrics**

Create `src/main/java/net/rmoff/connect/ais/TaskMetrics.java`:
```java
package net.rmoff.connect.ais;

/**
 * Cumulative per-task counters. Mutated only on the single task (poll) thread;
 * counters are read by JMX, so they are declared volatile for visibility.
 */
public class TaskMetrics implements TaskMetricsMBean {
    private volatile long messagesEmitted;
    private volatile long decodeErrors;
    private volatile long incompleteFragments;
    private volatile long unsupportedTypes;
    private volatile long reconnects;

    public void recordOutcome(NmeaLineParser.ParseOutcome.Kind kind) {
        switch (kind) {
            case PARSED:              messagesEmitted++;     break;
            case DECODE_ERROR:        decodeErrors++;        break;
            case INCOMPLETE_FRAGMENT: incompleteFragments++; break;
            case UNSUPPORTED_TYPE:    unsupportedTypes++;    break;
            default: break;
        }
    }

    public void recordReconnect() { reconnects++; }

    @Override public long getMessagesEmitted()    { return messagesEmitted; }
    @Override public long getDecodeErrors()        { return decodeErrors; }
    @Override public long getIncompleteFragments() { return incompleteFragments; }
    @Override public long getUnsupportedTypes()    { return unsupportedTypes; }
    @Override public long getReconnects()          { return reconnects; }

    /** Structured one-line summary for the periodic log heartbeat. */
    public String summary(int fragmentBufferSize, long uptimeMs) {
        return "AIS task metrics: emitted=" + messagesEmitted
                + " decodeErrors=" + decodeErrors
                + " incompleteFragments=" + incompleteFragments
                + " unsupportedTypes=" + unsupportedTypes
                + " reconnects=" + reconnects
                + " fragmentBuffer=" + fragmentBufferSize
                + " uptimeMs=" + uptimeMs;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=TaskMetricsTest`
Expected: PASS.

- [ ] **Step 6: Add the metrics-log-interval config**

In `AisSourceConnectorConfig.java`, after the SO_TIMEOUT constants add:
```java
    public static final String METRICS_LOG_INTERVAL_MS_CONFIG = "metrics.log.interval.ms";
    private static final String METRICS_LOG_INTERVAL_MS_DOC =
            "Emit a one-line INFO metrics summary at most this often (ms). Set to 0 to disable. "
            + "This is the primary observability surface on runtimes without JMX access "
            + "(e.g. Confluent Cloud Custom Connectors).";
```
In `CONFIG_DEF`, before the `DECODE_COMMON_ONLY_CONFIG` `.define`:
```java
            .define(METRICS_LOG_INTERVAL_MS_CONFIG, ConfigDef.Type.LONG, 60000L,
                    ConfigDef.Importance.LOW, METRICS_LOG_INTERVAL_MS_DOC)
```

- [ ] **Step 7: Write the failing due-for-metrics-log test**

In `AisSourceTaskTest.java`, add (mirrors the existing `dueForNoDataLog` helper test pattern). Semantics: enabled when `intervalMs > 0` and `now - lastLogMs >= intervalMs`.
```java
    @Test
    void dueForMetricsLogRespectsInterval() {
        assertTrue(AisSourceTask.dueForMetricsLog(60000, 0, 60000));    // interval elapsed since last
        assertFalse(AisSourceTask.dueForMetricsLog(60000, 30000, 60000)); // only 30s since last
        assertFalse(AisSourceTask.dueForMetricsLog(99999, 50000, 0));   // 0 disables
    }
```

- [ ] **Step 8: Run test to verify it fails**

Run: `mvn -q test -Dtest=AisSourceTaskTest`
Expected: FAIL — `dueForMetricsLog` does not exist.

- [ ] **Step 9: Wire TaskMetrics into AisSourceTask**

In `AisSourceTask.java`:

Add imports:
```java
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
```
Add fields (near the other fields):
```java
    private TaskMetrics metrics;
    private long metricsLogIntervalMs;
    private long lastMetricsLogAtMs;
    private ObjectName metricsObjectName;
```
Add the static helper next to `dueForNoDataLog`:
```java
    /** True when metrics logging is enabled (intervalMs &gt; 0) and intervalMs has elapsed since last log. */
    static boolean dueForMetricsLog(long nowMs, long lastLogMs, long intervalMs) {
        if (intervalMs <= 0) return false;
        return nowMs - lastLogMs >= intervalMs;
    }
```
In `start()`, after reading `noDataLogIntervalMs`, add:
```java
        metrics = new TaskMetrics();
        metricsLogIntervalMs = config.getLong(AisSourceConnectorConfig.METRICS_LOG_INTERVAL_MS_CONFIG);
        lastMetricsLogAtMs = 0;
```
At the end of `start()` (after the connect try/catch), register JMX best-effort:
```java
        try {
            metricsObjectName = new ObjectName(
                    "net.rmoff.connect.ais:type=TaskMetrics,host=" + host + ",port=" + port);
            ManagementFactory.getPlatformMBeanServer().registerMBean(metrics, metricsObjectName);
        } catch (Exception e) {
            log.info("JMX metrics registration unavailable ({}); relying on log metrics only",
                    e.getClass().getSimpleName());
            metricsObjectName = null;
        }
```
In `poll()`, in the reconnect branch where it currently sets `connectionEpoch`/`messageCount` and logs "Reconnected", add `metrics.recordReconnect();`.

In the parse `switch` from Task 2 Step 8, add `metrics.recordOutcome(outcome.kind());` as the first line inside the `try` after obtaining the outcome (before the switch), so every outcome is counted exactly once:
```java
                NmeaLineParser.ParseOutcome outcome = parser.parseLine(line);
                metrics.recordOutcome(outcome.kind());
                switch (outcome.kind()) {
                    ...
```
At the end of `poll()`, after the no-data heartbeat block and before/after the existing logic, add a metrics heartbeat. Place it just after `parser.cleanStaleFragments();` (line 159) so it fires regardless of records:
```java
        long nowForMetrics = System.currentTimeMillis();
        if (dueForMetricsLog(nowForMetrics, lastMetricsLogAtMs, metricsLogIntervalMs)) {
            log.info(metrics.summary(parser.getFragmentCount(), nowForMetrics - connectionEpoch));
            lastMetricsLogAtMs = nowForMetrics;
        }
```
In `stop()`, unregister JMX best-effort:
```java
        if (metricsObjectName != null) {
            try {
                ManagementFactory.getPlatformMBeanServer().unregisterMBean(metricsObjectName);
            } catch (Exception ignored) {
                // best effort
            }
        }
```

- [ ] **Step 10: Run test to verify it passes**

Run: `mvn -q test -Dtest=AisSourceTaskTest`
Expected: PASS.

- [ ] **Step 11: Run the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, 24+ tests pass.

- [ ] **Step 12: Document metrics.log.interval.ms in README**

In the config table add:
```adoc
|`metrics.log.interval.ms`
|LONG
|`60000`
|Emit a one-line INFO metrics summary at most this often (ms). `0` disables. Primary observability surface where JMX is unavailable (e.g. Confluent Cloud Custom Connectors).
```
Add a short "Observability" subsection after the config table noting: counters are emitted to the log periodically and also exposed via JMX (ObjectName `net.rmoff.connect.ais:type=TaskMetrics,host=<host>,port=<port>`) on self-managed workers.

- [ ] **Step 13: Commit**

```bash
git add src/main/java/net/rmoff/connect/ais/TaskMetrics.java \
        src/main/java/net/rmoff/connect/ais/TaskMetricsMBean.java \
        src/test/java/net/rmoff/connect/ais/TaskMetricsTest.java \
        src/main/java/net/rmoff/connect/ais/AisSourceConnectorConfig.java \
        src/main/java/net/rmoff/connect/ais/AisSourceTask.java \
        src/test/java/net/rmoff/connect/ais/AisSourceTaskTest.java \
        README.adoc
git commit -m "feat: task metrics with periodic log heartbeat and best-effort JMX"
```

---

## Task 4: Version bump, CHANGELOG, full verification

**Files:**
- Modify: `pom.xml:9`, `AisSourceConnector.java:23`, `AisSourceTask.java:46`, `README.adoc`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Bump version to 0.3.0**

- `pom.xml` line 9: `<version>0.3.0</version>`
- `AisSourceConnector.java` `version()`: `return "0.3.0";`
- `AisSourceTask.java` `version()`: `return "0.3.0";`
- `README.adoc`: change the `rmoff-kafka-connect-ais-0.2.2.zip` example and the `e.g. 0.2.2` reference to `0.3.0`.

- [ ] **Step 2: Add CHANGELOG entry**

In `CHANGELOG.md`, above `## [0.2.2]`, add:
```markdown
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
```
And add the link reference near the bottom alongside the others:
```markdown
[0.3.0]: https://github.com/rmoff/kafka-connect-ais/releases/tag/v0.3.0
```

- [ ] **Step 3: Full build + package**

Run: `mvn clean package`
Expected: BUILD SUCCESS; `target/components/packages/rmoff-kafka-connect-ais-0.3.0.zip` exists.

- [ ] **Step 4: Docker smoke test (per CLAUDE.md)**

```bash
docker compose down --remove-orphans
rm -rf plugins/rmoff-kafka-connect-ais-*
unzip -q -o target/components/packages/rmoff-kafka-connect-ais-0.3.0.zip -d plugins/
docker compose up -d
until curl -sf http://localhost:8083/ >/dev/null; do sleep 2; done
curl -X POST -H 'Content-Type: application/json' \
    --data @configs/connector-ais.json http://localhost:8083/connectors
sleep 30
docker exec broker /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server broker:29092 --topic ais
```
Expected: ≥200 records, connector RUNNING.

- [ ] **Step 5: Verify the metrics heartbeat appears**

Run: `docker logs kafka-connect 2>&1 | grep "AIS task metrics:" | tail -2`
Expected: at least one line like `AIS task metrics: emitted=... decodeErrors=0 ... fragmentBuffer=... uptimeMs=...`. Confirm `decodeErrors` is low (near 0) against the live feed.

- [ ] **Step 6: Tear down and commit**

```bash
docker compose down --remove-orphans
rm -rf plugins/rmoff-kafka-connect-ais-*
git add pom.xml CHANGELOG.md README.adoc \
        src/main/java/net/rmoff/connect/ais/AisSourceConnector.java \
        src/main/java/net/rmoff/connect/ais/AisSourceTask.java
git commit -m "chore: release 0.3.0 (production-readiness)"
```

---

## Self-review notes

- **Spec coverage**: #3 → Task 1; #2 → Task 2; #1 → Task 3; versioning/CHANGELOG/smoke → Task 4. All spec sections mapped.
- **Type consistency**: `ParseOutcome.Kind` enum values (`PARSED`, `INCOMPLETE_FRAGMENT`, `UNSUPPORTED_TYPE`, `DECODE_ERROR`) are used identically in `NmeaLineParser`, `TaskMetrics.recordOutcome`, `TaskMetricsTest`, and `AisSourceTask.poll`. `Parsed.result`, `DecodeError.reason` referenced consistently. `TcpConnectionManager` 6-arg constructor used in `AisSourceTask` and both `TcpConnectionManagerTest` sites.
- **Known risk**: AisLib exception class names (`AisMessageException`, `SixbitException`) assumed from package layout; if compilation fails on the import, locate the correct types via `unzip -l` on the AisLib jar or `javap`, and adjust the catch in Task 2 Step 4 (the categorization logic is unaffected, only the type names).
