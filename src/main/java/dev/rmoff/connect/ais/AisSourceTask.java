package dev.rmoff.connect.ais;

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

    @Override
    public String version() {
        return "0.1.0";
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
                config.getLong(AisSourceConnectorConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG)
        );

        parser = new NmeaLineParser(config.getLong(AisSourceConnectorConfig.FRAGMENT_TIMEOUT_MS_CONFIG));

        converter = new AisRecordConverter(
                config.getString(AisSourceConnectorConfig.TOPIC_CONFIG),
                config.getBoolean(AisSourceConnectorConfig.TOPIC_PER_TYPE_CONFIG),
                config.getBoolean(AisSourceConnectorConfig.DECODE_COMMON_ONLY_CONFIG)
        );

        pollTimeoutMs = config.getLong(AisSourceConnectorConfig.POLL_TIMEOUT_MS_CONFIG);
        batchMaxSize = config.getInt(AisSourceConnectorConfig.BATCH_MAX_SIZE_CONFIG);

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
                return null; // Still in backoff, return immediately
            }
            connectionEpoch = System.currentTimeMillis();
            messageCount = 0;
            log.info("Reconnected to AIS endpoint");
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
        return records.isEmpty() ? null : records;
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
