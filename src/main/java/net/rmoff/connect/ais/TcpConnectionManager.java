package net.rmoff.connect.ais;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class TcpConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(TcpConnectionManager.class);
    // NMEA sentences are ~82 chars; with a tag block and AIS payload still well under this.
    // Bounds memory against a malicious/garbled feed sending an unterminated line.
    private static final int MAX_LINE_LENGTH = 1024;

    private final String host;
    private final int port;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final int connectTimeoutMs;
    private final int soTimeoutMs;

    private volatile Socket socket;
    private volatile BufferedReader reader;
    // Partial line carried across readLine() calls so a mid-line SO_TIMEOUT resumes
    // correctly instead of corrupting the next sentence. Only touched by the task thread.
    private final StringBuilder lineBuffer = new StringBuilder();
    private long currentBackoffMs;
    private long nextReconnectTime;
    private volatile boolean stopping;
    private volatile long lastDataReceivedAtMs;
    // Whether the currently-open connection has delivered any data. The NCA feed
    // routinely accepts a fresh socket then closes it without sending anything; such
    // an unproductive connection must trigger reconnect backoff, while a connection
    // that did deliver data resets the backoff. Task-thread only.
    private boolean dataReceivedThisConnection;

    public TcpConnectionManager(String host, int port, long initialBackoffMs, long maxBackoffMs,
                                int connectTimeoutMs, int soTimeoutMs) {
        this.host = host;
        this.port = port;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.currentBackoffMs = initialBackoffMs;
        this.nextReconnectTime = 0;
        this.connectTimeoutMs = connectTimeoutMs;
        this.soTimeoutMs = soTimeoutMs;
    }

    public void connect() throws IOException {
        log.info("Connecting to AIS endpoint {}:{}", host, port);
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(soTimeoutMs);
        socket.setKeepAlive(true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        lineBuffer.setLength(0);  // drop any partial line left over from a dropped connection
        // NB: do NOT reset the backoff here. A successful TCP connect does not mean the
        // feed will actually deliver data — the backoff is cleared only once data arrives
        // (see connectionEnded()), so a run of accept-then-close connections still backs off.
        dataReceivedThisConnection = false;
        lastDataReceivedAtMs = System.currentTimeMillis();
        log.info("Connected to AIS endpoint {}:{}", host, port);
    }

    /**
     * Read a line from the TCP stream.
     *
     * <p>Reads character-by-character (rather than {@link BufferedReader#readLine()}) so the
     * accumulated line length can be bounded: a feed that never sends a line terminator would
     * otherwise let {@code readLine()} buffer without limit and exhaust the heap. A {@code
     * SocketTimeoutException} mid-line is expected — the partial line is retained in
     * {@code lineBuffer} and the next call resumes where it left off.
     *
     * @return the line, or null if the connection was closed by the remote end
     * @throws SocketTimeoutException if SO_TIMEOUT elapsed with no data
     * @throws IOException on connection error, or if a line exceeds {@link #MAX_LINE_LENGTH}
     */
    public String readLine() throws IOException {
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                String line = lineBuffer.toString();
                lineBuffer.setLength(0);
                markDataReceived();
                return line;
            }
            if (c != '\r') {
                lineBuffer.append((char) c);
                if (lineBuffer.length() > MAX_LINE_LENGTH) {
                    lineBuffer.setLength(0);
                    throw new IOException("Line exceeded maximum length of " + MAX_LINE_LENGTH
                            + " bytes without a terminator; dropping connection");
                }
            }
        }
        // Remote closed the stream. Flush any unterminated trailing line, else signal close.
        if (lineBuffer.length() > 0) {
            String line = lineBuffer.toString();
            lineBuffer.setLength(0);
            markDataReceived();
            return line;
        }
        return null;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Detect a half-open / silently-dead connection that the OS still reports as
     * connected. {@link Socket#isConnected()} never flips back once connected, so
     * we treat "no data for longer than idleTimeoutMs" as dead.
     *
     * @return true if connected but no data has arrived within idleTimeoutMs;
     *         false when idleTimeoutMs &lt;= 0 (disabled) or never connected.
     */
    public boolean isStale(long idleTimeoutMs) {
        if (idleTimeoutMs <= 0 || lastDataReceivedAtMs == 0) {
            return false;
        }
        return System.currentTimeMillis() - lastDataReceivedAtMs > idleTimeoutMs;
    }

    /**
     * Attempt to reconnect if the backoff period has elapsed.
     *
     * @return true if reconnected successfully, false if still in backoff or failed
     */
    public boolean attemptReconnect() {
        if (stopping) return false;
        long now = System.currentTimeMillis();
        if (now < nextReconnectTime) {
            return false;
        }
        try {
            disconnect();  // NOT close() — close() sets `stopping`, which would block all
                           // future reconnects after the first (the multi-reconnect bug).
            connect();
            return true;
        } catch (IOException e) {
            log.warn("Reconnect to {}:{} failed: {}. Next attempt in {}ms",
                    host, port, e.getMessage(), currentBackoffMs);
            nextReconnectTime = System.currentTimeMillis() + currentBackoffMs;
            currentBackoffMs = Math.min(currentBackoffMs * 2, maxBackoffMs);
            return false;
        }
    }

    public void close() {
        stopping = true;
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
            reader = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }
    }

    /**
     * Close the connection without setting the stopping flag.
     * Used when the connection drops but we want to reconnect.
     */
    public void disconnect() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
            reader = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }
    }

    private void markDataReceived() {
        lastDataReceivedAtMs = System.currentTimeMillis();
        dataReceivedThisConnection = true;
    }

    /**
     * Record that the active connection has ended (remote EOF, read error, or the idle
     * watchdog). Closes the socket and sets the reconnect schedule:
     * <ul>
     *   <li>If the connection delivered data, it was a genuine feed that dropped — reset
     *       the backoff so we reconnect promptly.</li>
     *   <li>If it delivered nothing (a starved / immediately-closed connection — the NCA
     *       feed does this to fresh connections), apply the current backoff and grow it,
     *       so a run of starved connections spaces out instead of hammering the feed at
     *       tens of reconnects per minute.</li>
     * </ul>
     */
    public void connectionEnded() {
        if (dataReceivedThisConnection) {
            currentBackoffMs = initialBackoffMs;
            nextReconnectTime = 0;
        } else {
            nextReconnectTime = System.currentTimeMillis() + currentBackoffMs;
            currentBackoffMs = Math.min(currentBackoffMs * 2, maxBackoffMs);
        }
        disconnect();
    }

    long currentBackoffMs() {
        return currentBackoffMs;
    }

    public long getLastDataReceivedAtMs() {
        return lastDataReceivedAtMs;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isStopping() {
        return stopping;
    }
}
