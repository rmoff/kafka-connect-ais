package net.rmoff.connect.ais;

/** JMX view of a task's runtime counters. */
public interface TaskMetricsMBean {
    long getMessagesEmitted();
    long getDecodeErrors();
    long getIncompleteFragments();
    long getUnsupportedTypes();
    long getReconnects();
}
