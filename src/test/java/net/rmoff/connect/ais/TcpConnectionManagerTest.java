package net.rmoff.connect.ais;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

class TcpConnectionManagerTest {

    private static final String MSG =
            "\\s:1,c:1774373593*04\\!BSVDM,1,1,,B,13mD7l0Oh10L3`DSh`>1AWWR0l1c,0*48";

    @Test
    void isStaleBecomesTrueAfterIdlePeriodAndResetsOnData() throws Exception {
        List<Socket> keepAlive = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            Thread sender = new Thread(() -> {
                try {
                    Socket client = server.accept();
                    keepAlive.add(client);             // keep open + silent (no close)
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                    out.println(MSG);
                } catch (Exception ignored) { }
            });
            sender.setDaemon(true);
            sender.start();

            TcpConnectionManager conn = new TcpConnectionManager("127.0.0.1", port, 100, 1000, 10000, 1000);
            conn.connect();

            // Fresh connection is never stale.
            assertFalse(conn.isStale(500), "fresh connection must not be stale");

            // Read the line the server sent — refreshes the last-data timestamp.
            assertNotNull(conn.readLine());

            // After idling past the timeout with no new data → stale.
            Thread.sleep(700);
            assertTrue(conn.isStale(500), "connection idle past timeout must be stale");

            // idleTimeoutMs == 0 disables the check.
            assertFalse(conn.isStale(0), "idle timeout 0 must disable staleness");

            conn.close();
        }
    }

    @Test
    void readLineResumesAfterSocketTimeoutWithoutFalseEof() throws Exception {
        int lineCount = 4;
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            Thread sender = new Thread(() -> {
                try {
                    Socket client = server.accept();
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                    for (int i = 0; i < lineCount; i++) {
                        out.println(MSG);
                        Thread.sleep(350);   // lull LONGER than SO_TIMEOUT, but never close
                    }
                    Thread.sleep(3000);      // hold the socket open after the last line
                } catch (Exception ignored) { }
            });
            sender.setDaemon(true);
            sender.start();

            // SO_TIMEOUT = 200ms < the 350ms inter-line lull, so reads time out between lines.
            TcpConnectionManager conn = new TcpConnectionManager("127.0.0.1", port, 100, 1000, 10000, 200);
            conn.connect();

            List<String> got = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 6000;
            while (got.size() < lineCount && System.currentTimeMillis() < deadline) {
                try {
                    String line = conn.readLine();
                    // A lull in the feed must NOT be mistaken for the remote closing the connection.
                    assertNotNull(line,
                            "readLine() returned null (EOF) but the server never closed the socket — "
                            + "a SO_TIMEOUT lull was misreported as a dropped feed");
                    got.add(line);
                } catch (SocketTimeoutException expected) {
                    // Feed quiet for > SO_TIMEOUT. poll() treats this as 'no data this cycle'
                    // and retries; it must not drop the connection.
                }
            }
            conn.close();
            assertEquals(lineCount, got.size(),
                    "should have read every line the live (never-closed) feed sent");
        }
    }

    @Test
    void readLineReassemblesLineSplitAcrossSocketTimeout() throws Exception {
        // Real TCP: a sentence arrives in two packets and the SO_TIMEOUT fires in the gap
        // between them, so readLine() must carry the partial line across the timeout and
        // reassemble it intact — not corrupt it or drop bytes.
        int lineCount = 4;
        int half = MSG.length() / 2;
        String firstHalf = MSG.substring(0, half);
        String secondHalf = MSG.substring(half);

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            Thread sender = new Thread(() -> {
                try {
                    Socket client = server.accept();
                    java.io.OutputStream raw = client.getOutputStream();
                    for (int i = 0; i < lineCount; i++) {
                        raw.write(firstHalf.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        raw.flush();
                        Thread.sleep(350);   // SO_TIMEOUT fires here, MID-LINE
                        raw.write((secondHalf + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        raw.flush();
                        Thread.sleep(350);   // another timeout, between lines
                    }
                    Thread.sleep(3000);      // never close
                } catch (Exception ignored) { }
            });
            sender.setDaemon(true);
            sender.start();

            TcpConnectionManager conn = new TcpConnectionManager("127.0.0.1", port, 100, 1000, 10000, 200);
            conn.connect();

            List<String> got = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 8000;
            while (got.size() < lineCount && System.currentTimeMillis() < deadline) {
                try {
                    String line = conn.readLine();
                    assertNotNull(line,
                            "readLine() returned null (EOF) but the server never closed the socket");
                    got.add(line);
                } catch (SocketTimeoutException expected) {
                    // mid-line lull — retry, must not lose the partial line
                }
            }
            conn.close();
            assertEquals(lineCount, got.size(), "should reassemble every split line");
            for (String line : got) {
                assertEquals(MSG, line, "split line must be reassembled byte-for-byte");
            }
        }
    }

    /**
     * Server that accepts connections and, per 1-based connection number, either sends
     * one line before closing (productive) or closes immediately with no data (the NCA
     * feed's "starve a fresh connection" behaviour). Always closes → client sees EOF.
     */
    private static ServerSocket closingServer(IntPredicate sendLineForConn) throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread t = new Thread(() -> {
            int n = 0;
            try {
                while (!server.isClosed()) {
                    Socket c = server.accept();
                    n++;
                    if (sendLineForConn.test(n)) {
                        PrintWriter out = new PrintWriter(c.getOutputStream(), true);
                        out.println(MSG);
                        Thread.sleep(80);   // let the client read it before we close
                    }
                    c.close();
                }
            } catch (Exception ignored) { }
        });
        t.setDaemon(true);
        t.start();
        return server;
    }

    @Test
    void unproductiveCloseDelaysReconnect() throws Exception {
        try (ServerSocket server = closingServer(n -> false)) {   // every connection: immediate close, no data
            long initial = 500;
            TcpConnectionManager conn =
                    new TcpConnectionManager("127.0.0.1", server.getLocalPort(), initial, 4000, 10000, 200);
            conn.connect();
            assertNull(conn.readLine(), "server closed with no data → EOF");
            conn.connectionEnded();

            // A starved (no-data) connection must NOT be retried immediately — that is the
            // ~35-reconnects/min hammering seen against the live feed.
            assertFalse(conn.attemptReconnect(),
                    "reconnect after an unproductive close must wait for the backoff");
            Thread.sleep(initial + 200);
            assertTrue(conn.attemptReconnect(), "after the backoff elapses, reconnect proceeds");
            conn.close();
        }
    }

    @Test
    void backoffGrowsAndCapsAcrossUnproductiveCloses() throws Exception {
        try (ServerSocket server = closingServer(n -> false)) {
            long initial = 100, max = 400;
            TcpConnectionManager conn =
                    new TcpConnectionManager("127.0.0.1", server.getLocalPort(), initial, max, 10000, 200);

            conn.connect(); assertNull(conn.readLine()); conn.connectionEnded();
            assertEquals(200, conn.currentBackoffMs(), "after 1 unproductive close: initial*2");

            Thread.sleep(120); assertTrue(conn.attemptReconnect());
            assertNull(conn.readLine()); conn.connectionEnded();
            assertEquals(400, conn.currentBackoffMs(), "after 2: initial*4, capped at max");

            Thread.sleep(220); assertTrue(conn.attemptReconnect());
            assertNull(conn.readLine()); conn.connectionEnded();
            assertEquals(400, conn.currentBackoffMs(), "stays capped at max");
            conn.close();
        }
    }

    @Test
    void productiveConnectionResetsBackoff() throws Exception {
        // connections 1 & 2 are starved (grow the backoff); connection 3 delivers a line.
        try (ServerSocket server = closingServer(n -> n >= 3)) {
            long initial = 100, max = 800;
            TcpConnectionManager conn =
                    new TcpConnectionManager("127.0.0.1", server.getLocalPort(), initial, max, 10000, 200);

            conn.connect(); assertNull(conn.readLine()); conn.connectionEnded();   // #1 starved
            Thread.sleep(120); assertTrue(conn.attemptReconnect());
            assertNull(conn.readLine()); conn.connectionEnded();                   // #2 starved
            assertEquals(400, conn.currentBackoffMs(), "backoff grew over starved connections");

            Thread.sleep(220); assertTrue(conn.attemptReconnect());                // #3 live
            assertEquals(MSG, conn.readLine(), "connection #3 delivers data");
            conn.connectionEnded();

            assertEquals(initial, conn.currentBackoffMs(),
                    "a productive connection must reset the backoff to initial");
            assertTrue(conn.attemptReconnect(),
                    "after a productive connection, reconnect is prompt (no backoff wait)");
            conn.close();
        }
    }

    @Test
    void isStaleFalseBeforeAnyConnection() {
        TcpConnectionManager conn = new TcpConnectionManager("127.0.0.1", 1, 100, 1000, 10000, 1000);
        assertFalse(conn.isStale(500), "never-connected manager must not be stale");
    }
}
