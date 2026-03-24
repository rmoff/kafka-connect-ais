package net.rmoff.connect.ais;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
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
        return "0.1.0";
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
        return configs;
    }

    @Override
    public void stop() {
        log.info("Stopping AIS source connector");
    }

    @Override
    public ConfigDef config() {
        return AisSourceConnectorConfig.CONFIG_DEF;
    }
}
