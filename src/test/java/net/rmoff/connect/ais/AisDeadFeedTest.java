package net.rmoff.connect.ais;

import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests against {@link FakeAisFeed} covering the feed states a unit suite previously
 * ignored — including the "dead/starved" connection that hid the 2026-06 incident.
 */
class AisDeadFeedTest {

    private static Map<String, String> props(int port, long idleMs) {
        Map<String, String> p = new HashMap<>();
        p.put("ais.hosts", "127.0.0.1:" + port);
        p.put("topic", "test-ais");
        p.put("task.host", "127.0.0.1:" + port);
        p.put("poll.timeout.ms", "200");
        p.put("batch.max.size", "100");
        p.put("fragment.timeout.ms", "5000");
        p.put("decode.common.only", "true");
        p.put("topic.per.type", "false");
        p.put("reconnect.backoff.initial.ms", "100");
        p.put("reconnect.backoff.max.ms", "200");
        p.put("idle.timeout.ms", String.valueOf(idleMs));
        p.put("no.data.log.interval.ms", "200");
        return p;
    }

    /** Baseline: a live feed yields records. */
    @Test
    void liveFeedProducesRecords() throws Exception {
        try (FakeAisFeed feed = new FakeAisFeed()) {
            feed.modeForConnection(n -> FakeAisFeed.Mode.LIVE);
            AisSourceTask task = new AisSourceTask();
            task.start(props(feed.port(), 60000));
            try {
                int total = 0;
                long deadline = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < deadline && total == 0) {
                    List<SourceRecord> r = task.poll();
                    if (r != null) total += r.size();
                }
                assertTrue(total > 0, "live feed must yield records");
            } finally { task.stop(); }
        }
    }

    /**
     * Dead feed: every connection is accepted but silent. The task must emit nothing
     * (there is no data), must NOT busy-spin, and must keep reconnecting (watchdog) —
     * not sit forever on one silently-dead socket like the original bug.
     */
    @Test
    void permanentlySilentFeedKeepsReconnectingWithoutBusySpin() throws Exception {
        try (FakeAisFeed feed = new FakeAisFeed()) {
            feed.modeForConnection(n -> FakeAisFeed.Mode.SILENT);
            AisSourceTask task = new AisSourceTask();
            task.start(props(feed.port(), 400));   // trip the watchdog quickly
            try {
                int polls = 0, records = 0;
                long deadline = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < deadline) {
                    List<SourceRecord> r = task.poll();
                    if (r != null) records += r.size();
                    polls++;
                }
                assertEquals(0, records, "a silent feed has no data to emit");
                assertTrue(feed.connectionCount() >= 2,
                        "watchdog must force reconnects on a silent feed (connections="
                                + feed.connectionCount() + ")");
                assertTrue(polls < 300, "poll() must not busy-spin on a silent feed: " + polls);
            } finally { task.stop(); }
        }
    }

    /**
     * Starved-by-close: every connection is accepted then immediately closed (EOF) — the
     * other shape of NCA feed starvation (distinct from SILENT, which stays open). Without
     * reconnect backoff this hammers the feed at one reconnect per poll cycle (dozens in a
     * few seconds — the ~35/min churn observed live). With exponential backoff the reconnect
     * count is bounded.
     */
    @Test
    void closeStormFeedBacksOffInsteadOfHammering() throws Exception {
        try (FakeAisFeed feed = new FakeAisFeed()) {
            feed.modeForConnection(n -> FakeAisFeed.Mode.CLOSE);
            AisSourceTask task = new AisSourceTask();
            Map<String, String> p = props(feed.port(), 60000);   // watchdog irrelevant: socket closes
            p.put("reconnect.backoff.initial.ms", "200");
            p.put("reconnect.backoff.max.ms", "2000");
            task.start(p);
            try {
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    task.poll();
                }
                int conns = feed.connectionCount();
                // Backoff 200→400→800→1600→2000(cap) sums to ~6 reconnects in 5s.
                // Unbounded hammering would be dozens (one per ~100ms poll cycle).
                assertTrue(conns <= 12,
                        "accept-then-close feed must back off, not hammer (connections=" + conns + ")");
                assertTrue(conns >= 2,
                        "must still keep retrying a starved feed (connections=" + conns + ")");
            } finally { task.stop(); }
        }
    }

    /**
     * Realistic recovery: the first two connections are starved (silent) and only the
     * third streams data — mirroring the real feed handing out silent connections at
     * random. The connector must keep cycling via the watchdog until it draws a live
     * connection, then produce. (The earlier recovery test guaranteed the *first*
     * reconnect delivered data, which over-promised recovery.)
     */
    @Test
    void recoversAfterSeveralStarvedConnections() throws Exception {
        try (FakeAisFeed feed = new FakeAisFeed()) {
            feed.modeForConnection(n -> n >= 3 ? FakeAisFeed.Mode.LIVE : FakeAisFeed.Mode.SILENT);
            AisSourceTask task = new AisSourceTask();
            task.start(props(feed.port(), 400));
            try {
                int total = 0;
                long deadline = System.currentTimeMillis() + 12000;
                while (System.currentTimeMillis() < deadline && total == 0) {
                    List<SourceRecord> r = task.poll();
                    if (r != null) total += r.size();
                }
                assertTrue(total > 0, "must recover once a live connection is drawn");
                assertTrue(feed.connectionCount() >= 3,
                        "must have cycled past the starved connections (connections="
                                + feed.connectionCount() + ")");
            } finally { task.stop(); }
        }
    }
}
