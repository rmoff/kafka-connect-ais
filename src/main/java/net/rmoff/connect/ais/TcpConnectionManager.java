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
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int SO_TIMEOUT_MS = 1000;

    private final String host;
    private final int port;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    private Socket socket;
    private BufferedReader reader;
    private long currentBackoffMs;
    private long nextReconnectTime;
    private volatile boolean stopping;

    public TcpConnectionManager(String host, int port, long initialBackoffMs, long maxBackoffMs) {
        this.host = host;
        this.port = port;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.currentBackoffMs = initialBackoffMs;
        this.nextReconnectTime = 0;
    }

    public void connect() throws IOException {
        log.info("Connecting to AIS endpoint {}:{}", host, port);
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(SO_TIMEOUT_MS);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        currentBackoffMs = initialBackoffMs;
        nextReconnectTime = 0;
        log.info("Connected to AIS endpoint {}:{}", host, port);
    }

    /**
     * Read a line from the TCP stream.
     *
     * @return the line, or null if the connection was closed by the remote end
     * @throws SocketTimeoutException if SO_TIMEOUT elapsed with no data
     * @throws IOException on connection error
     */
    public String readLine() throws IOException {
        return reader.readLine();
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
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
            close();
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
