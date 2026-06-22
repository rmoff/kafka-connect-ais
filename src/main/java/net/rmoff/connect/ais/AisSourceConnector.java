package net.rmoff.connect.ais;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.source.ExactlyOnceSupport;
import org.apache.kafka.connect.source.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AisSourceConnector extends SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(AisSourceConnector.class);

    private Map<String, String> configProps;

    @Override
    public String version() {
        return "0.3.0";
    }

    @Override
    public void start(Map<String, String> props) {
        // Validate config
        new AisSourceConnectorConfig(props);
        this.configProps = new HashMap<>(props);
        log.info("Starting AIS source connector");
    }

    @Override
    public Class<? extends Task> taskClass() {
        return AisSourceTask.class;
    }

    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        String hosts = configProps.get(AisSourceConnectorConfig.AIS_HOSTS_CONFIG);
        String[] hostList = hosts.split(",");

        List<Map<String, String>> configs = new ArrayList<>();
        // Distribute hosts across tasks: one host per task
        int numTasks = Math.min(maxTasks, hostList.length);
        for (int i = 0; i < numTasks; i++) {
            Map<String, String> taskConfig = new HashMap<>(configProps);
            taskConfig.put(AisSourceConnectorConfig.TASK_HOST_CONFIG, hostList[i].trim());
            configs.add(taskConfig);
        }
        log.info("Created {} task configs for {} hosts", numTasks, hostList.length);
        for (String warning : connectionWarnings(maxTasks, hostList.length)) {
            log.warn(warning);
        }
        return configs;
    }

    /**
     * Operational warnings about how many connections this connector will open.
     * The connector runs one connection per host (capped at maxTasks), so it opens
     * min(maxTasks, numHosts) connections.
     */
    static List<String> connectionWarnings(int maxTasks, int numHosts) {
        List<String> warnings = new ArrayList<>();
        int numConnections = Math.min(maxTasks, numHosts);
        if (maxTasks > numHosts) {
            warnings.add("tasks.max=" + maxTasks + " exceeds the " + numHosts
                    + " configured host(s); only " + numConnections + " task(s)/connection(s) "
                    + "will run (this connector opens one connection per host).");
        }
        if (numConnections > 1) {
            warnings.add("This connector will open " + numConnections + " simultaneous "
                    + "connections. NOTE: on at least one public AIS feed (Norwegian Coastal "
                    + "Administration) multiple connections sharing one source IP were observed "
                    + "to compete — connections get cycled/starved and throughput collapses. "
                    + "Prefer one connection per source IP: ensure these endpoints are distinct "
                    + "feeds and/or egress from distinct IPs. This may be specific to that feed.");
        }
        return warnings;
    }

    @Override
    public void stop() {
        log.info("Stopping AIS source connector");
    }

    @Override
    public ConfigDef config() {
        return AisSourceConnectorConfig.CONFIG_DEF;
    }

    // Best-effort at-least-once: this connector reads a live TCP AIS feed that has
    // no replay, and the task's source offsets (connection_epoch / message_count)
    // reset on every reconnect. A non-replayable live source cannot guarantee
    // exactly-once delivery, so we honestly report UNSUPPORTED.
    @Override
    public ExactlyOnceSupport exactlyOnceSupport(Map<String, String> connectorConfig) {
        return ExactlyOnceSupport.UNSUPPORTED;
    }
}
