package net.rmoff.connect.ais;

/**
 * Cumulative per-task counters. There is a single writer (the poll thread); counters
 * are declared volatile so JMX reader threads see up-to-date values. Note: reads across
 * multiple counters (e.g. {@link #summary}) are not an atomic snapshot — a JMX reader may
 * observe one counter updated and another not. That is acceptable for an observability surface.
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
