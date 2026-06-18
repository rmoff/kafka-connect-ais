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
        m.recordOutcome(NmeaLineParser.ParseOutcome.Kind.DECODE_ERROR);
        m.recordOutcome(NmeaLineParser.ParseOutcome.Kind.INCOMPLETE_FRAGMENT);
        m.recordOutcome(NmeaLineParser.ParseOutcome.Kind.UNSUPPORTED_TYPE);
        m.recordReconnect();
        String s = m.summary(3, 12345L);
        assertTrue(s.contains("emitted=1"), s);
        assertTrue(s.contains("decodeErrors=1"), s);
        assertTrue(s.contains("incompleteFragments=1"), s);
        assertTrue(s.contains("unsupportedTypes=1"), s);
        assertTrue(s.contains("reconnects=1"), s);
        assertTrue(s.contains("fragmentBuffer=3"), s);
        assertTrue(s.contains("uptimeMs=12345"), s);
    }
}
