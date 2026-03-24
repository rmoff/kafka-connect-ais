package dev.rmoff.connect.ais;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

import java.util.Map;

public class AisSourceConnectorConfig extends AbstractConfig {

    public static final String AIS_HOSTS_CONFIG = "ais.hosts";
    private static final String AIS_HOSTS_DOC = "Comma-separated list of AIS TCP endpoints in host:port format";

    public static final String TOPIC_CONFIG = "topic";
    private static final String TOPIC_DOC = "Kafka topic name (or topic prefix when topic.per.type=true)";

    public static final String TOPIC_PER_TYPE_CONFIG = "topic.per.type";
    private static final String TOPIC_PER_TYPE_DOC = "When true, route messages to category-specific topics using the topic value as prefix";

    public static final String POLL_TIMEOUT_MS_CONFIG = "poll.timeout.ms";
    private static final String POLL_TIMEOUT_MS_DOC = "Maximum milliseconds to spend reading per poll() call";

    public static final String BATCH_MAX_SIZE_CONFIG = "batch.max.size";
    private static final String BATCH_MAX_SIZE_DOC = "Maximum number of records to return per poll() call";

    public static final String RECONNECT_BACKOFF_INITIAL_MS_CONFIG = "reconnect.backoff.initial.ms";
    private static final String RECONNECT_BACKOFF_INITIAL_MS_DOC = "Initial reconnect backoff delay in milliseconds";

    public static final String RECONNECT_BACKOFF_MAX_MS_CONFIG = "reconnect.backoff.max.ms";
    private static final String RECONNECT_BACKOFF_MAX_MS_DOC = "Maximum reconnect backoff delay in milliseconds";

    public static final String FRAGMENT_TIMEOUT_MS_CONFIG = "fragment.timeout.ms";
    private static final String FRAGMENT_TIMEOUT_MS_DOC = "Timeout in milliseconds for incomplete multi-sentence message fragments";

    public static final String DECODE_COMMON_ONLY_CONFIG = "decode.common.only";
    private static final String DECODE_COMMON_ONLY_DOC = "When true, only common message types (position, static, base station, safety, AtoN) get full field decoding. Other types get common fields + raw_nmea only.";

    // Internal config set by connector for task distribution
    public static final String TASK_HOST_CONFIG = "task.host";

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(AIS_HOSTS_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                    new HostPortListValidator(), ConfigDef.Importance.HIGH, AIS_HOSTS_DOC)
            .define(TOPIC_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                    ConfigDef.Importance.HIGH, TOPIC_DOC)
            .define(TOPIC_PER_TYPE_CONFIG, ConfigDef.Type.BOOLEAN, false,
                    ConfigDef.Importance.MEDIUM, TOPIC_PER_TYPE_DOC)
            .define(POLL_TIMEOUT_MS_CONFIG, ConfigDef.Type.LONG, 100L,
                    ConfigDef.Importance.LOW, POLL_TIMEOUT_MS_DOC)
            .define(BATCH_MAX_SIZE_CONFIG, ConfigDef.Type.INT, 500,
                    ConfigDef.Importance.LOW, BATCH_MAX_SIZE_DOC)
            .define(RECONNECT_BACKOFF_INITIAL_MS_CONFIG, ConfigDef.Type.LONG, 1000L,
                    ConfigDef.Importance.LOW, RECONNECT_BACKOFF_INITIAL_MS_DOC)
            .define(RECONNECT_BACKOFF_MAX_MS_CONFIG, ConfigDef.Type.LONG, 60000L,
                    ConfigDef.Importance.LOW, RECONNECT_BACKOFF_MAX_MS_DOC)
            .define(FRAGMENT_TIMEOUT_MS_CONFIG, ConfigDef.Type.LONG, 30000L,
                    ConfigDef.Importance.LOW, FRAGMENT_TIMEOUT_MS_DOC)
            .define(DECODE_COMMON_ONLY_CONFIG, ConfigDef.Type.BOOLEAN, true,
                    ConfigDef.Importance.MEDIUM, DECODE_COMMON_ONLY_DOC);

    public AisSourceConnectorConfig(Map<String, String> props) {
        super(CONFIG_DEF, props);
    }

    static class HostPortListValidator implements ConfigDef.Validator {
        @Override
        public void ensureValid(String name, Object value) {
            if (value == null) {
                throw new ConfigException(name, value, "Must not be null");
            }
            String str = (String) value;
            String[] hosts = str.split(",");
            for (String host : hosts) {
                String trimmed = host.trim();
                if (trimmed.isEmpty()) {
                    throw new ConfigException(name, value, "Empty host entry");
                }
                String[] parts = trimmed.split(":");
                if (parts.length != 2) {
                    throw new ConfigException(name, value,
                            "Each entry must be in host:port format, got: " + trimmed);
                }
                try {
                    int port = Integer.parseInt(parts[1]);
                    if (port < 1 || port > 65535) {
                        throw new ConfigException(name, value,
                                "Port must be between 1 and 65535, got: " + port);
                    }
                } catch (NumberFormatException e) {
                    throw new ConfigException(name, value,
                            "Invalid port number: " + parts[1]);
                }
            }
        }
    }
}
