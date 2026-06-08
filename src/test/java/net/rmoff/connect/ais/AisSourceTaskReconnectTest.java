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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the 2026-06-03 production incident: a feed that goes silent without
 * closing the TCP connection (half-open) stalls the task forever, and the task
 * busy-spins (returns null without blocking) while disconnected.
 *
 * These tests exercise the real {@link TcpConnectionManager} + {@link AisSourceTask}
 * against a controllable fake TCP server.
 */
class AisSourceTaskReconnectTest {

    private static final String MSG_A =
            "\\s:1,c:1774373593*04\\!BSVDM,1,1,,B,13mD7l0Oh10L3`DSh`>1AWWR0l1c,0*48";
    private static final String MSG_B =
            "\\s:2,c:1774373593*05\\!BSVDM,1,1,,A,13n3gF`0000vIA`W2viuCGpH0@7k,0*20";

    /**
     * Connection #1: send one message, then stay open and SILENT (never close) for
     * longer than the whole test — a half-open feed. Connection #2 (only reached if
     * the task reconnects): stream fresh messages.
     *
     * Correct behaviour: idle watchdog trips, task reconnects, receives MSG_B.
     * Buggy behaviour (current code): isConnected() stays true, no reconnect, only
     * MSG_A ever arrives → assertions fail.
     */
    @Test
    void recoversWhenFeedGoesSilentWithoutClosing() throws Exception {
        AtomicInteger connectionCount = new AtomicInteger();
        List<Socket> keepAlive = new CopyOnWriteArrayList<>(); // stop GC closing sockets
        ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();

        Thread srv = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted() && !server.isClosed()) {
                    Socket c = server.accept();
                    keepAlive.add(c);
                    int n = connectionCount.incrementAndGet();
                    PrintWriter out = new PrintWriter(c.getOutputStream(), true);
                    if (n == 1) {
                        out.println(MSG_A);       // one message, then silence (no close)
                    } else {
                        for (int i = 0; i < 300; i++) {
                            out.println(MSG_B);   // post-reconnect: plenty of data
                        }
                    }
                }
            } catch (Exception ignored) { }
        });
        srv.setDaemon(true);
        srv.start();

        Map<String, String> props = baseProps(port);
        props.put("idle.timeout.ms", "500");                  // trip quickly when silent
        props.put("reconnect.backoff.initial.ms", "100");
        props.put("reconnect.backoff.max.ms", "200");

        AisSourceTask task = new AisSourceTask();
        task.start(props);
        try {
            int total = 0;
            long deadline = System.currentTimeMillis() + 12000;
            while (System.currentTimeMillis() < deadline) {
                List<SourceRecord> recs = task.poll();
                if (recs != null) total += recs.size();
                if (total >= 2 && connectionCount.get() >= 2) break;
            }
            assertTrue(connectionCount.get() >= 2,
                    "task must reconnect after the feed goes silent (connections="
                            + connectionCount.get() + ")");
            assertTrue(total >= 2,
                    "task must receive data after reconnect (records=" + total + ")");
        } finally {
            task.stop();
            server.close();
        }
    }

    /**
     * When the endpoint is unreachable, poll() must not hot-loop returning null —
     * Kafka Connect re-invokes poll() immediately on null, so an unpaced null pins a
     * CPU core. We measure poll throughput: a paced poll() does well under 200
     * null-returns/sec; a busy-spin does tens of thousands.
     */
    @Test
    void doesNotBusySpinWhenEndpointUnreachable() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) { deadPort = s.getLocalPort(); }

        Map<String, String> props = baseProps(deadPort);
        props.put("reconnect.backoff.initial.ms", "200");
        props.put("reconnect.backoff.max.ms", "200");

        AisSourceTask task = new AisSourceTask();
        task.start(props);
        try {
            long start = System.currentTimeMillis();
            int polls = 0;
            while (System.currentTimeMillis() - start < 1000) {
                assertNull(task.poll(), "no data available — poll must return null");
                polls++;
            }
            assertTrue(polls < 200,
                    "poll() busy-spins while disconnected: " + polls + " null-polls in 1s");
        } finally {
            task.stop();
        }
    }

    private static Map<String, String> baseProps(int port) {
        Map<String, String> p = new HashMap<>();
        p.put("ais.hosts", "127.0.0.1:" + port);
        p.put("topic", "test-ais");
        p.put("task.host", "127.0.0.1:" + port);
        p.put("poll.timeout.ms", "100");
        p.put("batch.max.size", "100");
        p.put("fragment.timeout.ms", "5000");
        p.put("decode.common.only", "true");
        p.put("topic.per.type", "false");
        return p;
    }
}
