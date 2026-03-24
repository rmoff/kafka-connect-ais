package net.rmoff.connect.ais;

import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AisSourceTaskTest {

    @Test
    void pollReturnsRecordsFromTcpStream() throws Exception {
        // Start a local TCP server that sends AIS data
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            // Send data in a background thread
            Thread sender = new Thread(() -> {
                try {
                    Socket client = server.accept();
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                    // Send real captured AIS messages
                    out.println("\\s:2573305,c:1774373593*04\\!BSVDM,1,1,,B,13mD7l0Oh10L3`DSh`>1AWWR0l1c,0*48");
                    out.println("\\s:2573465,c:1774373593*05\\!BSVDM,1,1,,A,13n3gF`0000vIA`W2viuCGpH0@7k,0*20");
                    // Keep connection open briefly
                    Thread.sleep(500);
                    client.close();
                } catch (Exception e) {
                    // Test server error
                }
            });
            sender.setDaemon(true);
            sender.start();

            // Configure and start task
            Map<String, String> props = new HashMap<>();
            props.put("ais.hosts", "127.0.0.1:" + port);
            props.put("topic", "test-ais");
            props.put("task.host", "127.0.0.1:" + port);
            props.put("poll.timeout.ms", "2000");
            props.put("batch.max.size", "10");
            props.put("reconnect.backoff.initial.ms", "100");
            props.put("reconnect.backoff.max.ms", "1000");
            props.put("fragment.timeout.ms", "5000");
            props.put("decode.common.only", "true");
            props.put("topic.per.type", "false");

            AisSourceTask task = new AisSourceTask();
            task.start(props);

            try {
                // Poll for records
                List<SourceRecord> records = task.poll();
                assertNotNull(records, "Should receive records from TCP stream");
                assertTrue(records.size() >= 1, "Should have at least 1 record");

                SourceRecord first = records.get(0);
                assertEquals("test-ais", first.topic());
                assertNotNull(first.key());
                assertNotNull(first.value());
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void configValidation() {
        Map<String, String> props = new HashMap<>();
        props.put("ais.hosts", "153.44.253.27:5631");
        props.put("topic", "ais");

        AisSourceConnectorConfig config = new AisSourceConnectorConfig(props);
        assertEquals("153.44.253.27:5631", config.getString("ais.hosts"));
        assertEquals("ais", config.getString("topic"));
        assertEquals(100L, config.getLong("poll.timeout.ms"));
        assertEquals(500, config.getInt("batch.max.size"));
        assertTrue(config.getBoolean("decode.common.only"));
        assertFalse(config.getBoolean("topic.per.type"));
    }

    @Test
    void configValidationRejectsInvalidHosts() {
        Map<String, String> props = new HashMap<>();
        props.put("ais.hosts", "invalid-no-port");
        props.put("topic", "ais");

        assertThrows(Exception.class, () -> new AisSourceConnectorConfig(props));
    }
}
