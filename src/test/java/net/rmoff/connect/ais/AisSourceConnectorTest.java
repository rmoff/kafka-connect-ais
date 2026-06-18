package net.rmoff.connect.ais;

import org.apache.kafka.connect.source.ExactlyOnceSupport;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
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
