package net.rmoff.connect.ais;

import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.*;

public class AisSourceTask extends SourceTask {

    private static final Logger log = LoggerFactory.getLogger(AisSourceTask.class);

    private TcpConnectionManager connection;
    private NmeaLineParser parser;
    private AisRecordConverter converter;
    private volatile boolean stopping;

    private Map<String, Object> sourcePartition;
    private long connectionEpoch;
    private long messageCount;
    private long pollTimeoutMs;
    private int batchMaxSize;
    private long idleTimeoutMs;
    private long noDataLogIntervalMs;
    private long lastNoDataLogAtMs;

    // When there is nothing to return, sleep this long before returning null so
    // Kafka Connect's WorkerSourceTask does not hot-loop poll() and pin a CPU core.
    private static final long NO_DATA_SLEEP_MS = 100;

    /**
     * Decide whether to emit a "connected but no data" heartbeat log now: enabled
     * (intervalMs &gt; 0), no data for at least intervalMs, and not logged within intervalMs.
     */
    static boolean dueForNoDataLog(long nowMs, long lastDataMs, long lastLogMs, long intervalMs) {
        if (intervalMs <= 0) return false;
        if (nowMs - lastDataMs < intervalMs) return false;
        return nowMs - lastLogMs >= intervalMs;
    }

    @Override
    public String version() {
        return "0.2.2";
    }

    @Override
    public void start(Map<String, String> props) {
        AisSourceConnectorConfig config = new AisSourceConnectorConfig(props);

        String hostPort = props.get(AisSourceConnectorConfig.TASK_HOST_CONFIG);
        if (hostPort == null) {
            hostPort = config.getString(AisSourceConnectorConfig.AIS_HOSTS_CONFIG).split(",")[0].trim();
        }
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        connection = new TcpConnectionManager(
                host, port,
                config.getLong(AisSourceConnectorConfig.RECONNECT_BACKOFF_INITIAL_MS_CONFIG),
                config.getLong(AisSourceConnectorConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG),
                config.getInt(AisSourceConnectorConfig.CONNECT_TIMEOUT_MS_CONFIG),
                config.getInt(AisSourceConnectorConfig.SO_TIMEOUT_MS_CONFIG)
        );

        parser = new NmeaLineParser(config.getLong(AisSourceConnectorConfig.FRAGMENT_TIMEOUT_MS_CONFIG));

        converter = new AisRecordConverter(
                config.getString(AisSourceConnectorConfig.TOPIC_CONFIG),
                config.getBoolean(AisSourceConnectorConfig.TOPIC_PER_TYPE_CONFIG),
                config.getBoolean(AisSourceConnectorConfig.DECODE_COMMON_ONLY_CONFIG)
        );

        pollTimeoutMs = config.getLong(AisSourceConnectorConfig.POLL_TIMEOUT_MS_CONFIG);
        batchMaxSize = config.getInt(AisSourceConnectorConfig.BATCH_MAX_SIZE_CONFIG);
        idleTimeoutMs = config.getLong(AisSourceConnectorConfig.IDLE_TIMEOUT_MS_CONFIG);
        noDataLogIntervalMs = config.getLong(AisSourceConnectorConfig.NO_DATA_LOG_INTERVAL_MS_CONFIG);
        lastNoDataLogAtMs = 0;

        sourcePartition = Collections.singletonMap("host_port", host + ":" + port);
        connectionEpoch = System.currentTimeMillis();
        messageCount = 0;
        stopping = false;

        log.info("AIS source task starting, connecting to {}:{}", host, port);
        try {
            connection.connect();
        } catch (IOException e) {
            log.warn("Initial connection to {}:{} failed: {}. Will retry in poll().",
                    host, port, e.getMessage());
        }
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        if (stopping) {
            return null;
        }

        // Reconnect if needed
        if (!connection.isConnected()) {
            if (!connection.attemptReconnect()) {
                // Still in backoff. Sleep so Connect doesn't hot-loop poll() at 100% CPU.
                Thread.sleep(NO_DATA_SLEEP_MS);
                return null;
            }
            connectionEpoch = System.currentTimeMillis();
            messageCount = 0;
            log.info("Reconnected to AIS endpoint");
        }

        // Idle watchdog: the OS may still report a half-open socket as connected.
        // If no data has arrived for idleTimeoutMs, force a reconnect on the next poll.
        if (connection.isStale(idleTimeoutMs)) {
            log.warn("No data from AIS feed for >{}ms — forcing reconnect", idleTimeoutMs);
            connection.disconnect();
            parser.cleanStaleFragments();
            Thread.sleep(NO_DATA_SLEEP_MS);
            return null;
        }

        List<SourceRecord> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + pollTimeoutMs;

        while (records.size() < batchMaxSize && System.currentTimeMillis() < deadline && !stopping) {
            try {
                String line = connection.readLine();
                if (line == null) {
                    // Remote end closed connection
                    log.warn("AIS connection closed by remote end");
                    connection.disconnect();
                    break;
                }

                Optional<NmeaLineParser.ParseResult> result = parser.parseLine(line);
                if (result.isPresent()) {
                    messageCount++;
                    Map<String, Object> sourceOffset = new HashMap<>();
                    sourceOffset.put("connection_epoch", connectionEpoch);
                    sourceOffset.put("message_count", messageCount);

                    SourceRecord record = converter.convert(result.get(), sourcePartition, sourceOffset);
                    records.add(record);
                }
            } catch (SocketTimeoutException e) {
                // Normal: no data available within SO_TIMEOUT
                break;
            } catch (IOException e) {
                if (stopping) {
                    break;
                }
                log.warn("Connection error: {}. Will reconnect on next poll.", e.getMessage());
                connection.disconnect();
                break;
            }
        }

        parser.cleanStaleFragments();
        if (records.isEmpty()) {
            // Heartbeat: make a connected-but-starved/silent feed visible in the logs.
            long now = System.currentTimeMillis();
            if (connection.isConnected()
                    && dueForNoDataLog(now, connection.getLastDataReceivedAtMs(), lastNoDataLogAtMs, noDataLogIntervalMs)) {
                log.info("Connected to {}:{} but received no AIS data for {}ms — the feed may be "
                                + "silent or starving this connection",
                        connection.getHost(), connection.getPort(),
                        now - connection.getLastDataReceivedAtMs());
                lastNoDataLogAtMs = now;
            }
            // No data this cycle — pace before returning null to avoid a busy-spin.
            Thread.sleep(NO_DATA_SLEEP_MS);
            return null;
        }
        return records;
    }

    @Override
    public void stop() {
        log.info("Stopping AIS source task");
        stopping = true;
        if (connection != null) {
            connection.close();
        }
    }
}
