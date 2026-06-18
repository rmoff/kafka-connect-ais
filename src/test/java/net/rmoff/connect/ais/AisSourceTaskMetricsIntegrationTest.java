package net.rmoff.connect.ais;

import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: drives poll() with a mix of valid and malformed AIS lines and
 * asserts that the task both emits records and increments the relevant metrics counters.
 *
 * <p>Uses an inline TCP server (same pattern as {@link AisSourceTaskReconnectTest}) so
 * the sequence of lines is fully deterministic.
 */
class AisSourceTaskMetricsIntegrationTest {

    /** A real, decodable single-sentence AIS line. */
    private static final String VALID_LINE =
            "\\s:1,c:1774373593*04\\!BSVDM,1,1,,B,13mD7l0Oh10L3`DSh`>1AWWR0l1c,0*48";

    /**
     * A line that looks like a NMEA sentence but carries a corrupt payload and a
     * wrong checksum — the parser will attempt to decode it and produce a DECODE_ERROR.
     */
    private static final String MALFORMED_LINE = "!BSVDM,1,1,,B,@@@@INVALID@@@@,0*00";

    @Test
    void pollEmitsRecordsAndUpdatesMetrics() throws Exception {
        ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();

        // Serve: many valid lines, then the malformed line, then keep the connection open.
        Thread srv = new Thread(() -> {
            try (Socket c = server.accept()) {
                PrintWriter out = new PrintWriter(c.getOutputStream(), true);
                // Emit enough valid lines to ensure at least one reaches poll() before timeout.
                for (int i = 0; i < 100; i++) {
                    out.println(VALID_LINE);
                }
                out.println(MALFORMED_LINE);
                // Keep the connection open so the task doesn't reconnect mid-test.
                for (int i = 0; i < 500; i++) {
                    out.println(VALID_LINE);
                    Thread.sleep(10);
                }
            } catch (Exception ignored) { }
        });
        srv.setDaemon(true);
        srv.start();

        AisSourceTask task = new AisSourceTask();
        task.start(baseProps(port));
        try {
            List<SourceRecord> allRecords = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 8000;
            // Poll until we have at least one record and know the malformed line was processed.
            // The malformed line is sent after 100 valid lines so once we have records the
            // decode-error counter should also be non-zero; give it up to the deadline.
            while (System.currentTimeMillis() < deadline) {
                List<SourceRecord> batch = task.poll();
                if (batch != null) allRecords.addAll(batch);
                TaskMetrics m = task.metrics();
                if (m.getMessagesEmitted() >= 1 && m.getDecodeErrors() >= 1) break;
            }

            TaskMetrics m = task.metrics();
            assertTrue(allRecords.size() >= 1,
                    "poll() must emit at least one record from the valid line");
            assertTrue(m.getMessagesEmitted() >= 1,
                    "messagesEmitted must be >= 1, got " + m.getMessagesEmitted());
            assertTrue(m.getDecodeErrors() >= 1,
                    "decodeErrors must be >= 1 after the malformed line, got " + m.getDecodeErrors());
        } finally {
            task.stop();
            server.close();
        }
    }

    private static Map<String, String> baseProps(int port) {
        Map<String, String> p = new HashMap<>();
        p.put("ais.hosts", "127.0.0.1:" + port);
        p.put("topic", "test-ais");
        p.put("task.host", "127.0.0.1:" + port);
        p.put("poll.timeout.ms", "200");
        p.put("batch.max.size", "200");
        p.put("fragment.timeout.ms", "5000");
        p.put("decode.common.only", "true");
        p.put("topic.per.type", "false");
        p.put("reconnect.backoff.initial.ms", "100");
        p.put("reconnect.backoff.max.ms", "200");
        p.put("idle.timeout.ms", "60000");
        p.put("no.data.log.interval.ms", "0");
        p.put("metrics.log.interval.ms", "0");
        return p;
    }
}
