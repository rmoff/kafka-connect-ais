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
