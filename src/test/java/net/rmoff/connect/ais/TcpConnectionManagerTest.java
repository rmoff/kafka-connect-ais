package net.rmoff.connect.ais;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

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
    void isStaleFalseBeforeAnyConnection() {
        TcpConnectionManager conn = new TcpConnectionManager("127.0.0.1", 1, 100, 1000, 10000, 1000);
        assertFalse(conn.isStale(500), "never-connected manager must not be stale");
    }
}
