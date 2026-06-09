package net.rmoff.connect.ais;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * A controllable local stand-in for the AIS TCP feed, for tests.
 *
 * Each accepted connection is handled per {@link Mode}, chosen by a function of the
 * connection number (1-based) so tests can model the real feed's quirks:
 * <ul>
 *   <li>{@link Mode#LIVE} — stream dummy AIS sentences continuously.</li>
 *   <li>{@link Mode#SILENT} — accept the socket but send nothing and keep it open
 *       (the real feed's "starved/dead connection" behaviour that looked like a
 *       healthy idle connector and hid the 2026-06 incident for hours).</li>
 * </ul>
 */
class FakeAisFeed implements AutoCloseable {

    enum Mode { LIVE, SILENT }

    /** A real, decodable single-sentence AIS line. */
    static final String SAMPLE_LINE =
            "\\s:1,c:1774373593*04\\!BSVDM,1,1,,B,13mD7l0Oh10L3`DSh`>1AWWR0l1c,0*48";

    private final ServerSocket server;
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger();
    private volatile IntFunction<Mode> modeForConnection = n -> Mode.LIVE;

    FakeAisFeed() throws IOException {
        server = new ServerSocket(0);
        Thread accept = new Thread(this::acceptLoop, "fake-ais-accept");
        accept.setDaemon(true);
        accept.start();
    }

    int port() { return server.getLocalPort(); }
    int connectionCount() { return connections.get(); }

    /** Decide each connection's behaviour by its 1-based number. */
    void modeForConnection(IntFunction<Mode> f) { this.modeForConnection = f; }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                open.add(s);
                int n = connections.incrementAndGet();
                if (modeForConnection.apply(n) == Mode.LIVE) {
                    Thread t = new Thread(() -> stream(s), "fake-ais-stream-" + n);
                    t.setDaemon(true);
                    t.start();
                }
                // SILENT: leave the socket open and send nothing.
            } catch (IOException e) {
                return; // server closed
            }
        }
    }

    private void stream(Socket s) {
        try {
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            while (!s.isClosed() && !server.isClosed()) {
                out.println(SAMPLE_LINE);
                if (out.checkError()) break;
                Thread.sleep(20); // ~50 sentences/sec
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() throws IOException {
        for (Socket s : open) {
            try { s.close(); } catch (IOException ignored) { }
        }
        server.close();
    }
}
