package net.rmoff.connect.ais;

import org.apache.kafka.connect.source.ExactlyOnceSupport;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AisSourceConnectorTest {

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
