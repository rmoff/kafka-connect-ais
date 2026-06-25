package net.rmoff.connect.ais;

import org.apache.kafka.connect.source.ExactlyOnceSupport;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AisSourceConnectorTest {

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

    @Test
    void rejectsNonPositiveSocketTimeout() {
        Map<String, String> props = new HashMap<>();
        props.put(AisSourceConnectorConfig.AIS_HOSTS_CONFIG, "host:5000");
        props.put(AisSourceConnectorConfig.TOPIC_CONFIG, "ais");
        props.put(AisSourceConnectorConfig.SO_TIMEOUT_MS_CONFIG, "0");
        assertThrows(org.apache.kafka.common.config.ConfigException.class,
                () -> new AisSourceConnectorConfig(props));
    }

    @Test
    void noWarningsForSingleConnection() {
        assertTrue(AisSourceConnector.connectionWarnings(1, 1).isEmpty(),
                "one task, one host: nothing to warn about");
        // tasks.max=4 with a single host is safe — the connector caps to 1 connection.
        List<String> w = AisSourceConnector.connectionWarnings(4, 1);
        assertEquals(1, w.size(), "should note the excess tasks.max, but not the per-IP risk");
        assertTrue(w.get(0).toLowerCase().contains("tasks.max"),
                "excess-tasks warning should mention tasks.max: " + w);
        assertFalse(String.join(" ", w).toLowerCase().contains("source ip"),
                "single connection must NOT raise the per-IP-competition warning: " + w);
    }

    @Test
    void warnsAboutPerIpCompetitionWhenMultipleConnections() {
        // 2 hosts -> 2 simultaneous connections: warn about same-IP competition.
        List<String> w = AisSourceConnector.connectionWarnings(2, 2);
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).toLowerCase().contains("source ip"),
                "multi-connection warning should mention source IP competition: " + w);
    }

    @Test
    void warnsBothWhenExcessTasksAndMultipleConnections() {
        // tasks.max=4 but 3 hosts -> 3 connections AND excess tasks.max.
        List<String> w = AisSourceConnector.connectionWarnings(4, 3);
        assertEquals(2, w.size(), "expected both the excess-tasks and per-IP warnings: " + w);
        String joined = String.join(" || ", w).toLowerCase();
        assertTrue(joined.contains("tasks.max") && joined.contains("source ip"), joined);
    }

    @Test
    void exactlyOnceSupportIsUnsupported() {
        // The live TCP AIS feed has no replay and the task's source offsets
        // (connection_epoch / message_count) reset on every reconnect, so
        // exactly-once cannot be guaranteed. The connector must be honest.
        Map<String, String> props = new HashMap<>();
        props.put(AisSourceConnectorConfig.AIS_HOSTS_CONFIG, "localhost:5631");
        props.put(AisSourceConnectorConfig.TOPIC_CONFIG, "ais");

        assertEquals(ExactlyOnceSupport.UNSUPPORTED,
                new AisSourceConnector().exactlyOnceSupport(props));
    }
}
