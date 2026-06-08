# AIS Connector TCP Liveness + Busy-Spin Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `AisSourceTask` survive a silently-dropped upstream TCP feed — detect a stalled connection, reconnect, and never burn CPU while waiting.

**Architecture:** Add an idle-data watchdog to `TcpConnectionManager` (track time of last received line) and have `AisSourceTask.poll()` force a reconnect when the feed goes silent past a configurable timeout. Separately, stop `poll()` from returning `null` without first blocking, which currently lets Kafka Connect hot-loop the task at 100% CPU during reconnect backoff.

**Tech Stack:** Java 17, Kafka Connect `SourceTask` API, JUnit 5, raw `java.net.Socket` / `ServerSocket` test harness (already used in `AisSourceTaskTest`).

---

## STATUS — implemented 2026-06-05 ✅

Bugs #1–#3 reproduced with failing tests, then fixed (red→green). Proving runs:

- **Busy-spin proven:** `doesNotBusySpinWhenEndpointUnreachable` failed on the old
  code with **75,585,353 null-polls/sec**; passes after the fix (<200/sec).
- **Half-open stall proven:** `recoversWhenFeedGoesSilentWithoutClosing` hung
  (1 connection, never reconnected) on the old code; after the fix it reconnects
  and recovers in ~2 s.

Suite: **20 tests green** (`mvn test`), up from 15. Added:
`AisSourceTaskReconnectTest` (2), `TcpConnectionManagerTest` (2),
`AisConverterRealDataTest` (1, real captured feed), plus a config-default assertion.

Deployed to Confluent Cloud: new custom-plugin **`ccp-8dzqj7`**
(`rmoff-ais-source-liveness-fix`), connector **`ais-source`** with
`idle.timeout.ms=60000`, replacing the temporary `ais-source-2`.

### Audit of the rest of the codebase (asked: "any other glaring fubars?")

No other defect as severe as #1–#3. Lesser items found:

- **`exactlyOnceSupport` returns `SUPPORTED`** while task source offsets
  (`connection_epoch`/`message_count`) **reset on every reconnect** — now that the
  idle watchdog reconnects more often, the EOS claim is increasingly dubious. The
  feed has no replay, so it's really "no dupes within a connection." *Recommend*
  downgrading to a more honest support level, or documenting the caveat. (Not a
  crash; review-level.)
- **`AisRecordConverter.populateTypeFields()` swallows decode/cast errors at
  `debug`** (e.g. `(AisMessage4)` cast covers types 4 *and* 11). Good defensiveness
  (one bad message won't kill the stream) but invisible if a whole type breaks.
  `AisConverterRealDataTest` now exercises the live type mix
  (`{1,3,5,8,18,21,24}`) so a silent decode regression fails the build. Consider a
  throttled WARN counter for swallowed conversions.
- **`NmeaLineParser.normalizeTalkerId()` is dead code** — only referenced by a test
  asserting it's a no-op; the parse path never calls it. The `dk.dma` lib parses
  `!BSVDM` directly, so harmless, but the method is misleading. Remove or wire it in.

---

## Background: what happened (2026-06-05 incident)

The deployed connector (`ais-source`) stopped delivering records to the `ais`
topic at **2026-06-03 06:47 UTC** and never recovered (~34 h of downtime).
During the incident:

- The connector + task reported **`RUNNING`** with an **empty error trace**.
- The upstream feed `153.44.253.27:5631` was **live the whole time** (verified by
  connecting directly; fresh NMEA sentences with current timestamps).
- A **brand-new connector instance connected instantly** while the old one still
  existed — so the feed does *not* limit connections per client; the fault is
  entirely client-side.
- **`confluent connect logs` returned 0 WARN/ERROR over the whole window, and 0
  INFO at the stall onset** — the task never logged a reconnect attempt, an
  IOException, or a "connection closed by remote". It just went silent.
- A worker-metrics panel showed **CPU pinned at 100%** and **free memory → 0**.
- `pause`/`resume` (the only restart available for custom connectors via the API)
  did **not** recover it; only deploying a new instance did.

### Root cause — confirmed code defects

The evidence points at two distinct latent failure modes in the same code. The
silent-with-no-reconnect-attempts log signature matches **#1/#2** (the trigger);
the 100%-CPU panel matches **#3**. The fix must harden all of them.

1. **A half-open TCP connection is undetectable.**
   `TcpConnectionManager.isConnected()` returns
   `socket != null && socket.isConnected() && !socket.isClosed()`.
   `Socket.isConnected()` returns `true` for the life of the socket once it has
   *ever* connected and **never flips back** when the peer silently disappears
   (idle NAT/firewall drop, upstream half-close without FIN). `isClosed()` only
   reflects *our* `close()`. So a silently-dead socket reads as "connected"
   forever. In `poll()`, the reconnect block (`if (!connection.isConnected())`)
   is therefore never entered, `readLine()` just throws `SocketTimeoutException`
   every second (swallowed silently), and `poll()` returns `null` forever. This
   exactly matches the incident: RUNNING, no data, no logs, no reconnect.

2. **No idle-data watchdog.** Nothing tracks "how long since I last received a
   line." A healthy AIS feed delivers ~2000 msgs/min, so many minutes of total
   silence is unambiguously dead — but the code has no notion of it.

3. **`poll()` busy-spins by returning `null` without blocking.** On the backoff
   path (`return null` while `now < nextReconnectTime`), and Kafka Connect's
   `WorkerSourceTask` re-invokes `poll()` immediately when it returns `null`. So
   during any reconnect-backoff window the task burns a full CPU core. Returning
   `null` without sleeping is a documented Connect anti-pattern and is the likely
   source of the 100%-CPU panel.

4. **(Contributing) memory pressure = GC thrash, not a leak.** The busy-spin in
   #3 was measured at **75 million `poll()` calls/sec** (see the proving test),
   each allocating an `ArrayList` etc. That allocation storm is what drives the
   "free memory → 0" panel — it is a symptom of #3, not an independent leak.
   **Correction to an earlier hypothesis:** `NmeaLineParser.fragments` is *not* an
   unbounded leak — its key is `channel + ":" + seqId` (~2 channels × 10 sequence
   ids ≈ 20 keys), so a new first-fragment overwrites the same slot. Pruning on all
   paths (Task 4) is still worthwhile hygiene, but it was never the memory cause.

> **Honesty note for the implementer:** we could not *prove* the 100%-CPU path
> from logs (the connector is too quiet — it logged nothing even when healthy),
> and the metrics screenshot's time axis was unreadable. Treat #3 as
> strongly-suspected-not-proven and #1/#2 as the confirmed trigger. The plan
> fixes all of them and **adds logging** so the next incident is diagnosable.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `src/main/java/net/rmoff/connect/ais/AisSourceConnectorConfig.java` | Config schema | **Modify** — add `idle.timeout.ms` |
| `src/main/java/net/rmoff/connect/ais/TcpConnectionManager.java` | TCP socket lifecycle | **Modify** — track last-data time, expose `isStale()`, set keepalive |
| `src/main/java/net/rmoff/connect/ais/AisSourceTask.java` | `poll()` loop | **Modify** — idle watchdog + no-busy-spin sleep |
| `src/test/java/net/rmoff/connect/ais/TcpConnectionManagerTest.java` | Unit tests for staleness logic | **Create** |
| `src/test/java/net/rmoff/connect/ais/AisSourceTaskReconnectTest.java` | Integration tests with a fake TCP server | **Create** |

Run the whole suite at any point with: `mvn -q test`

---

## Task 1: Add `idle.timeout.ms` config

**Files:**
- Modify: `src/main/java/net/rmoff/connect/ais/AisSourceConnectorConfig.java`

- [ ] **Step 1: Add the config constant + define() entry**

In `AisSourceConnectorConfig.java`, after the `RECONNECT_BACKOFF_MAX_MS_CONFIG`
block, add the constant:

```java
    public static final String IDLE_TIMEOUT_MS_CONFIG = "idle.timeout.ms";
    private static final String IDLE_TIMEOUT_MS_DOC =
            "Force a reconnect if no data has been received from the feed for this many " +
            "milliseconds. Guards against silently half-open TCP connections that the OS " +
            "still reports as connected. Set to 0 to disable. Default 60000 (60s).";
```

In the `CONFIG_DEF` builder, add this `.define(...)` (e.g. after the
`FRAGMENT_TIMEOUT_MS_CONFIG` define):

```java
            .define(IDLE_TIMEOUT_MS_CONFIG, ConfigDef.Type.LONG, 60000L,
                    ConfigDef.Importance.MEDIUM, IDLE_TIMEOUT_MS_DOC)
```

- [ ] **Step 2: Add a test asserting the default**

In `src/test/java/net/rmoff/connect/ais/AisSourceTaskTest.java`, inside
`configValidation()`, add one assertion:

```java
        assertEquals(60000L, config.getLong("idle.timeout.ms"));
```

- [ ] **Step 3: Run the test, expect PASS**

Run: `mvn -q -Dtest=AisSourceTaskTest#configValidation test`
Expected: PASS (the default is wired).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/rmoff/connect/ais/AisSourceConnectorConfig.java \
        src/test/java/net/rmoff/connect/ais/AisSourceTaskTest.java
git commit -m "feat(config): add idle.timeout.ms for feed liveness watchdog"
```

---

## Task 2: Track last-data time + staleness in TcpConnectionManager

**Files:**
- Modify: `src/main/java/net/rmoff/connect/ais/TcpConnectionManager.java`
- Test: `src/test/java/net/rmoff/connect/ais/TcpConnectionManagerTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/net/rmoff/connect/ais/TcpConnectionManagerTest.java`:

```java
package net.rmoff.connect.ais;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

class TcpConnectionManagerTest {

    @Test
    void isStaleBecomesTrueAfterIdlePeriodAndResetsOnData() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            Thread sender = new Thread(() -> {
                try {
                    Socket client = server.accept();
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                    out.println("\\s:1,c:1774373593*04\\!BSVDM,1,1,,B,13mD7l0Oh10L3`DSh`>1AWWR0l1c,0*48");
                    Thread.sleep(2000); // then go silent but keep socket open
                    client.close();
                } catch (Exception ignored) { }
            });
            sender.setDaemon(true);
            sender.start();

            TcpConnectionManager conn = new TcpConnectionManager("127.0.0.1", port, 100, 1000);
            conn.connect();

            // Right after connect, not stale.
            assertFalse(conn.isStale(500), "fresh connection must not be stale");

            // Read the one line the server sent — refreshes last-data time.
            String line = conn.readLine();
            assertNotNull(line);

            // Idle timeout is short; after waiting past it with no new data → stale.
            Thread.sleep(700);
            assertTrue(conn.isStale(500), "connection idle past timeout must be stale");

            // idleTimeoutMs == 0 disables the check.
            assertFalse(conn.isStale(0), "idle timeout 0 disables staleness");

            conn.close();
        }
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -q -Dtest=TcpConnectionManagerTest test`
Expected: FAIL — `isStale` is not defined (compile error).

- [ ] **Step 3: Implement last-data tracking + isStale + keepalive**

In `TcpConnectionManager.java`:

Add a field near the other private fields:

```java
    private volatile long lastDataReceivedAtMs;
```

In `connect()`, enable keepalive and seed the timestamp (so a fresh, briefly-quiet
connection is not instantly judged stale). Replace the body of `connect()` with:

```java
    public void connect() throws IOException {
        log.info("Connecting to AIS endpoint {}:{}", host, port);
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(SO_TIMEOUT_MS);
        socket.setKeepAlive(true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        currentBackoffMs = initialBackoffMs;
        nextReconnectTime = 0;
        lastDataReceivedAtMs = System.currentTimeMillis();
        log.info("Connected to AIS endpoint {}:{}", host, port);
    }
```

Replace `readLine()` so it records when real data arrives:

```java
    public String readLine() throws IOException {
        String line = reader.readLine();
        if (line != null) {
            lastDataReceivedAtMs = System.currentTimeMillis();
        }
        return line;
    }
```

Add the staleness check (returns false when `idleTimeoutMs <= 0`, or when never
connected):

```java
    /**
     * @return true if a connection exists but no data has arrived for longer than
     *         idleTimeoutMs. Returns false when idleTimeoutMs <= 0 (disabled).
     */
    public boolean isStale(long idleTimeoutMs) {
        if (idleTimeoutMs <= 0 || lastDataReceivedAtMs == 0) {
            return false;
        }
        return System.currentTimeMillis() - lastDataReceivedAtMs > idleTimeoutMs;
    }
```

- [ ] **Step 4: Run the test, expect PASS**

Run: `mvn -q -Dtest=TcpConnectionManagerTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/rmoff/connect/ais/TcpConnectionManager.java \
        src/test/java/net/rmoff/connect/ais/TcpConnectionManagerTest.java
git commit -m "feat(tcp): track last-data time, add isStale(), enable TCP keepalive"
```

---

## Task 3: Force reconnect on idle, and stop the busy-spin in poll()

**Files:**
- Modify: `src/main/java/net/rmoff/connect/ais/AisSourceTask.java`
- Test: `src/test/java/net/rmoff/connect/ais/AisSourceTaskReconnectTest.java` (create)

- [ ] **Step 1: Write the failing integration test (idle → reconnect → recovery)**

Create `src/test/java/net/rmoff/connect/ais/AisSourceTaskReconnectTest.java`:

```java
package net.rmoff.connect.ais;

import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AisSourceTaskReconnectTest {

    private static final String MSG_A =
            "\\s:1,c:1774373593*04\\!BSVDM,1,1,,B,13mD7l0Oh10L3`DSh`>1AWWR0l1c,0*48";
    private static final String MSG_B =
            "\\s:2,c:1774373593*05\\!BSVDM,1,1,,A,13n3gF`0000vIA`W2viuCGpH0@7k,0*20";

    /**
     * Server accepts twice. First connection: send MSG_A then go SILENT but keep
     * the socket open (simulates a half-open feed). Second connection: send MSG_B.
     * A correct task must notice the first connection went idle, reconnect, and
     * deliver MSG_B.
     */
    @Test
    void forcesReconnectWhenFeedGoesSilent() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            AtomicInteger accepts = new AtomicInteger();

            Thread serverThread = new Thread(() -> {
                try {
                    // 1st connection: one message, then silence (no close).
                    Socket c1 = server.accept();
                    accepts.incrementAndGet();
                    PrintWriter o1 = new PrintWriter(c1.getOutputStream(), true);
                    o1.println(MSG_A);
                    // hold the socket open and silent
                    Thread.sleep(5000);
                    c1.close();
                } catch (Exception ignored) { }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            Thread serverThread2 = new Thread(() -> {
                try {
                    // 2nd connection (after task reconnects): fresh data.
                    Socket c2 = server.accept();
                    accepts.incrementAndGet();
                    PrintWriter o2 = new PrintWriter(c2.getOutputStream(), true);
                    for (int i = 0; i < 50; i++) o2.println(MSG_B);
                    Thread.sleep(2000);
                    c2.close();
                } catch (Exception ignored) { }
            });
            serverThread2.setDaemon(true);
            serverThread2.start();

            Map<String, String> props = baseProps(port);
            props.put("idle.timeout.ms", "500");          // short: trip quickly
            props.put("reconnect.backoff.initial.ms", "100");
            props.put("reconnect.backoff.max.ms", "200");

            AisSourceTask task = new AisSourceTask();
            task.start(props);
            try {
                boolean gotA = false, gotB = false;
                long deadline = System.currentTimeMillis() + 15000;
                while (System.currentTimeMillis() < deadline && !(gotA && gotB)) {
                    List<SourceRecord> recs = task.poll();
                    if (recs != null) {
                        for (SourceRecord r : recs) {
                            String raw = String.valueOf(r.value());
                            if (raw.contains("13mD7l0Oh")) gotA = true; // MSG_A
                            if (raw.contains("13n3gF`"))   gotB = true; // MSG_B
                        }
                    }
                }
                assertTrue(gotA, "should receive the first message");
                assertTrue(gotB, "should reconnect after idle and receive post-reconnect data");
                assertTrue(accepts.get() >= 2, "task must have reconnected (>=2 accepts)");
            } finally {
                task.stop();
            }
        }
    }

    /**
     * When the endpoint is unreachable, poll() must NOT hot-loop returning null.
     * Measure that repeated null-returning polls are paced (block/sleep), not instant.
     */
    @Test
    void pollDoesNotBusySpinWhenDisconnected() throws Exception {
        // Reserve a port then close it so connects are refused.
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) { deadPort = s.getLocalPort(); }

        Map<String, String> props = baseProps(deadPort);
        props.put("reconnect.backoff.initial.ms", "200");
        props.put("reconnect.backoff.max.ms", "200");

        AisSourceTask task = new AisSourceTask();
        task.start(props);
        try {
            long start = System.currentTimeMillis();
            int polls = 0;
            while (System.currentTimeMillis() - start < 1000) {
                assertNull(task.poll(), "no data available — poll must return null");
                polls++;
            }
            // Without a sleep, a hot-loop would do tens of thousands of polls/sec.
            // With pacing (>=~50ms per null poll), 1s should yield well under 200.
            assertTrue(polls < 200,
                    "poll() appears to busy-spin: " + polls + " null-polls in 1s");
        } finally {
            task.stop();
        }
    }

    private static Map<String, String> baseProps(int port) {
        Map<String, String> p = new HashMap<>();
        p.put("ais.hosts", "127.0.0.1:" + port);
        p.put("topic", "test-ais");
        p.put("task.host", "127.0.0.1:" + port);
        p.put("poll.timeout.ms", "100");
        p.put("batch.max.size", "100");
        p.put("fragment.timeout.ms", "5000");
        p.put("decode.common.only", "true");
        p.put("topic.per.type", "false");
        return p;
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn -q -Dtest=AisSourceTaskReconnectTest test`
Expected: FAIL — `forcesReconnectWhenFeedGoesSilent` hangs/times out (no idle
detection → never reconnects → `gotB` false), and `pollDoesNotBusySpinWhenDisconnected`
fails with a high poll count (hot-loop).

- [ ] **Step 3: Implement idle watchdog + no-busy-spin in `AisSourceTask`**

Add a field and read the new config in `start()`. After the existing
`batchMaxSize = ...` line, add:

```java
        idleTimeoutMs = config.getLong(AisSourceConnectorConfig.IDLE_TIMEOUT_MS_CONFIG);
```

Add the field with the others near the top of the class:

```java
    private long idleTimeoutMs;
    private static final long NO_DATA_SLEEP_MS = 100;
```

Replace the reconnect block at the top of `poll()` so the backoff path **sleeps**
before returning null (kills the busy-spin):

```java
        // Reconnect if needed
        if (!connection.isConnected()) {
            if (!connection.attemptReconnect()) {
                // Still in backoff. Sleep briefly so Connect doesn't hot-loop poll().
                Thread.sleep(NO_DATA_SLEEP_MS);
                return null;
            }
            connectionEpoch = System.currentTimeMillis();
            messageCount = 0;
            log.info("Reconnected to AIS endpoint");
        }

        // Idle watchdog: the OS may still report a half-open socket as connected.
        // If no data has arrived for idleTimeoutMs, force a reconnect next poll.
        if (connection.isStale(idleTimeoutMs)) {
            log.warn("No data from AIS feed for >{}ms — forcing reconnect", idleTimeoutMs);
            connection.disconnect();
            Thread.sleep(NO_DATA_SLEEP_MS);
            return null;
        }
```

At the very end of `poll()`, replace the final return so an empty poll also paces
itself (defense-in-depth against any other no-data path hot-looping):

```java
        parser.cleanStaleFragments();
        if (records.isEmpty()) {
            Thread.sleep(NO_DATA_SLEEP_MS);
            return null;
        }
        return records;
```

> Note: `poll()` already declares `throws InterruptedException`, so `Thread.sleep`
> needs no extra handling — an interrupt during shutdown propagates correctly.

- [ ] **Step 4: Run the new tests, expect PASS**

Run: `mvn -q -Dtest=AisSourceTaskReconnectTest test`
Expected: PASS — both tests green.

- [ ] **Step 5: Run the full suite (no regressions)**

Run: `mvn -q test`
Expected: PASS — including the original `AisSourceTaskTest#pollReturnsRecordsFromTcpStream`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/rmoff/connect/ais/AisSourceTask.java \
        src/test/java/net/rmoff/connect/ais/AisSourceTaskReconnectTest.java
git commit -m "fix(task): idle-data watchdog + stop poll() busy-spinning on no data

Detect half-open/silent feeds via idle.timeout.ms and force a reconnect;
sleep before returning null so Kafka Connect doesn't hot-loop the task at
100% CPU during backoff. Fixes the 2026-06-03 silent-stall incident."
```

---

## Task 4: Bound the fragment map (memory hardening)

**Files:**
- Modify: `src/main/java/net/rmoff/connect/ais/AisSourceTask.java`

The `fragments` map is only pruned at the end of `poll()`. The new early-return
paths (idle, backoff) now also skip pruning, so ensure pruning runs regardless.

- [ ] **Step 1: Write the failing test**

Add to `AisSourceTaskReconnectTest.java`:

```java
    @Test
    void staleFragmentsArePrunedEvenWhenNoCompletePollHappens() throws Exception {
        // A parser fed only first-parts of multipart messages must not grow forever.
        NmeaLineParser parser = new NmeaLineParser(50); // 50ms fragment timeout
        // First fragment of a 2-part message (sentenceNum 1 of 2):
        parser.parseLine("!AIVDM,2,1,3,B,55P5TL01VIaAL@7WKO@mBplU@<PDhh000000001S;AJ::4A80?4i@E53,0*3E");
        assertEquals(1, parser.getFragmentCount());
        Thread.sleep(80);
        parser.cleanStaleFragments();
        assertEquals(0, parser.getFragmentCount(), "stale fragment must be pruned");
    }
```

- [ ] **Step 2: Run it**

Run: `mvn -q -Dtest=AisSourceTaskReconnectTest#staleFragmentsArePrunedEvenWhenNoCompletePollHappens test`
Expected: PASS already (this verifies `cleanStaleFragments` works; the real fix is
ensuring it's *called*). If it fails because the sample sentence doesn't parse as a
first-fragment, swap in any real 2-part `!BSVDM,2,1,..` sentence captured from the
feed (`tcpdump`/`nc 153.44.253.27 5631 | grep ',2,1,'`).

- [ ] **Step 3: Ensure pruning runs on every poll path**

In `AisSourceTask.poll()`, move `parser.cleanStaleFragments();` so it runs before
*all* of the no-data early returns added in Task 3 (idle path, backoff path, empty
path). Simplest: call it once near the top of `poll()` right after the reconnect
block, instead of only at the bottom. Remove the bottom-of-method call to avoid
double-pruning.

- [ ] **Step 4: Run the full suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/rmoff/connect/ais/AisSourceTask.java \
        src/test/java/net/rmoff/connect/ais/AisSourceTaskReconnectTest.java
git commit -m "fix(parser): prune stale fragments on every poll path (memory)"
```

---

## Task 5: Manual / soak verification (pre-deploy)

These are not unit tests — run them by hand before shipping. Document results in
the PR description.

- [ ] **Step 1: Local run against the real feed**

```bash
# Build the plugin
mvn -q clean package
# Run with Connect standalone (or the project's existing local runner) pointed at
# 153.44.253.27:5631, idle.timeout.ms=60000. Confirm records flow.
```
Expected: steady records; logs show one "Connecting/Connected" pair, then quiet.

- [ ] **Step 2: Simulate a silent half-open drop**

Put a TCP proxy between the connector and the feed so you can freeze the stream
without sending a FIN/RST:

```bash
# Option A: socat relay, then SIGSTOP it to freeze (half-open simulation)
socat TCP-LISTEN:15631,reuseaddr,fork TCP:153.44.253.27:5631 &
SOCAT_PID=$!
# point the connector at 127.0.0.1:15631, let data flow, then:
kill -STOP $SOCAT_PID      # freeze — feed goes silent, socket stays open
# wait > idle.timeout.ms
```
Expected: within ~`idle.timeout.ms` the log prints
`No data from AIS feed for >60000ms — forcing reconnect`, the connector
reconnects (`kill -CONT $SOCAT_PID` to let it succeed), and records resume.
**Watch CPU: it must stay low throughout** (this is the busy-spin regression check).

- [ ] **Step 3: Deploy `ais-source-2`'s replacement with the fix to Confluent Cloud**

Repackage the custom-connector plugin, upload a new plugin version, and update the
running connector (or deploy alongside and cut over). After deploy, watch the
**CPU load** and **Production** health panels for an hour, and confirm
`received_records` on the `ais` topic stays steady.

- [ ] **Step 4: Add an ops alert (follow-up, optional)**

There is no alert today that would have caught 34 h of silence. File a follow-up to
add a metric/alert on `io.confluent.kafka.server/received_records` for the `ais`
topic dropping to 0 for > N minutes. (Out of scope for this code change.)

---

## Self-Review

- **Spec coverage:** #1 half-open (Task 2 `isStale` + Task 3 idle watchdog), #2 no
  watchdog (Task 1 config + Task 3), #3 busy-spin (Task 3 sleeps + dedicated test),
  #4 memory (Task 3 sleeps reduce churn; Task 4 fragment pruning). Logging added
  (Task 3 WARN) so the next incident is visible — addresses the "too quiet" gap. ✓
- **Placeholders:** none — every code step shows full code. The one soft spot is the
  sample multipart sentence in Task 4 Step 1 (noted: substitute a real captured
  2-part sentence if the placeholder doesn't parse). ✓
- **Type/name consistency:** `idle.timeout.ms` / `IDLE_TIMEOUT_MS_CONFIG` /
  `idleTimeoutMs`; `isStale(long)`, `disconnect()`, `attemptReconnect()`,
  `getFragmentCount()`, `cleanStaleFragments()` all match existing/added signatures. ✓
