---
name: kafka-connect
description: >-
  Build custom Kafka Connect connectors. Use whenever the user wants to develop,
  implement, or scaffold a new Kafka Connect connector plugin — source connectors
  that pull data into Kafka, or sink connectors that push data from Kafka to external
  systems. Also use when working with the Connect API classes: SourceConnector,
  SinkConnector, SourceTask, SinkTask, ConfigDef, Schema, SchemaBuilder, Struct,
  SourceRecord, SinkRecord. Triggers on mentions of connector development, custom
  connector, building a connector, connector plugin, connect-api, or implementing
  poll()/put() methods.
user-invocable: true
argument-hint: [source|sink] [description of what the connector does]
allowed-tools:
  - Bash
  - Read
  - Write
  - Glob
  - Grep
  - AskUserQuestion
---

# Kafka Connect — Connector Development

Build production-grade Kafka Connect source and sink connector plugins in Java.

## Workflow

1. **Gather requirements** — source or sink? external system? schema? auth? offset model?
2. **Scaffold** — Maven project, directory layout, POM dependencies
3. **Implement Connector** — config validation, task partitioning
4. **Implement Task** — data movement (poll or put), offset tracking
5. **Define configuration** — ConfigDef with types, validators, groups
6. **Handle schemas** — SchemaBuilder, Struct, logical types
7. **Security review** — credentials, logging, SSL, error sanitization
8. **Test** — unit, integration (Testcontainers), failure scenarios
9. **Package & deploy** — plugin directory or uber-JAR, install to plugin.path

## Requirements Interview

Before writing code, establish these. Skip questions where the answer is obvious from context.

**Core:**
- Source or Sink?
- What external system? (REST API, database, file system, message queue, cloud service?)
- Existing client library for the external system?

**Source-specific:**
- What's the offset/position model? (timestamp, sequence ID, cursor, API pagination token?)
- Is an initial snapshot needed, or stream-only? (See "Snapshot Modes" below)
- Is the source sometimes quiet for long periods? (heartbeat topic needed?)
- How should work be partitioned across tasks? (per table, per API endpoint, per partition?)

**Sink-specific:**
- Idempotent writes needed? What's the natural dedup key?
- How should records be batched? (by count, by size, by time?)
- Rebalance sensitivity? (does the external system have per-partition state?)

**Both:**
- Schema strategy: fixed schema, dynamic from source, or schemaless?
- Authentication: password, API key, OAuth token, mTLS?
- Error tolerance: fail-fast or skip bad records (ErrantRecordReporter / DLQ)?

## Project Scaffolding

```
my-connector/
├── pom.xml
└── src/
    ├── main/java/com/example/connect/
    │   ├── MySourceConnector.java     (or MySinkConnector.java)
    │   ├── MySourceTask.java          (or MySinkTask.java)
    │   └── MyConnectorConfig.java
    └── test/java/com/example/connect/
        ├── MyConnectorConfigTest.java
        ├── MySourceTaskTest.java
        └── MySourceConnectorIT.java   (integration test)
```

For the full Maven POM template, read [references/build-and-package.md](references/build-and-package.md).

## Source Connector

### SourceConnector

```java
public class MySourceConnector extends SourceConnector {

    private Map<String, String> configProps;

    @Override
    public void start(Map<String, String> props) {
        this.configProps = props;
        // Validate config eagerly — fail fast on bad config
        new MyConnectorConfig(props);
    }

    @Override
    public Class<? extends Task> taskClass() {
        return MySourceTask.class;
    }

    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        // Partition work across tasks. Each map configures one task.
        // Example: assign tables/endpoints/partitions to tasks.
        // Return at most maxTasks configs, but can return fewer.
        List<Map<String, String>> configs = new ArrayList<>();
        // ... partition logic ...
        return configs;
    }

    @Override
    public void stop() {
        // Release any resources opened in start()
    }

    @Override
    public ConfigDef config() {
        return MyConnectorConfig.CONFIG_DEF;
    }

    @Override
    public String version() {
        return "1.0.0";
    }
}
```

### SourceTask

```java
public class MySourceTask extends SourceTask {

    private volatile boolean stopping = false;
    private MyConnectorConfig config;
    // ... client/connection fields ...

    @Override
    public void start(Map<String, String> props) {
        this.config = new MyConnectorConfig(props);

        // Restore offset from last committed position
        Map<String, Object> sourcePartition = Collections.singletonMap(
            "source", config.getString("my.source.id")
        );
        Map<String, Object> lastOffset = context.offsetStorageReader()
            .offset(sourcePartition);

        if (lastOffset != null) {
            // Resume from stored position
            // e.g., lastOffset.get("position")
        }

        // Open connection to external system
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        if (stopping) return null;

        // Fetch data from external system
        List<SourceRecord> records = new ArrayList<>();

        // If no data available, backoff — don't spin
        if (noNewData) {
            Thread.sleep(config.getLong("poll.interval.ms"));
            return Collections.emptyList();
        }

        for (/* each datum */) {
            Map<String, Object> sourcePartition = Collections.singletonMap(
                "source", config.getString("my.source.id")
            );
            Map<String, Object> sourceOffset = Collections.singletonMap(
                "position", currentPosition  // Use source-native coordinate
            );

            Schema valueSchema = SchemaBuilder.struct()
                .name("com.example.MyRecord")
                .field("id", Schema.INT64_SCHEMA)
                .field("name", Schema.STRING_SCHEMA)
                .field("updated_at", Timestamp.SCHEMA)
                .build();

            Struct value = new Struct(valueSchema)
                .put("id", datum.getId())
                .put("name", datum.getName())
                .put("updated_at", datum.getUpdatedAt());

            records.add(new SourceRecord(
                sourcePartition, sourceOffset,
                config.getString("topic"),
                null,           // partition (null = default partitioner)
                Schema.INT64_SCHEMA, datum.getId(),  // key
                valueSchema, value                    // value
            ));
        }
        return records;
    }

    @Override
    public void stop() {
        // Called from a DIFFERENT THREAD than poll()
        stopping = true;
        // Close connections, release resources
    }
}
```

**Key source patterns:**
- **Offset resume**: Always check `offsetStorageReader()` in `start()` — resume from where you left off
- **Backoff**: When no data, sleep or return empty list. Never busy-loop.
- **Graceful shutdown**: `stop()` runs on a different thread. Use a `volatile boolean` flag that `poll()` checks.
- **Source-native positions**: Use the external system's natural coordinate (database LSN, API cursor, file offset, timestamp) as your offset value. Keep it human-readable for debugging.

## Sink Connector

### SinkConnector

Same lifecycle pattern as SourceConnector — `start()`, `taskClass()`, `taskConfigs()`, `stop()`, `config()`, `version()`. The only structural difference is it extends `SinkConnector`.

### SinkTask

```java
public class MySinkTask extends SinkTask {

    private MyConnectorConfig config;
    // ... client/connection fields ...
    private ErrantRecordReporter reporter;

    @Override
    public void start(Map<String, String> props) {
        this.config = new MyConnectorConfig(props);
        // Open connection to external system

        // ErrantRecordReporter for DLQ support (Connect 2.6+)
        try {
            reporter = context.errantRecordReporter();
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            reporter = null; // Older Connect runtime
        }
    }

    @Override
    public void put(Collection<SinkRecord> records) {
        if (records.isEmpty()) return;

        for (SinkRecord record : records) {
            try {
                // Convert record to external system format
                // Write to external system (batch for efficiency)
            } catch (Exception e) {
                if (reporter != null) {
                    reporter.report(record, e);  // Route to DLQ
                } else {
                    throw new ConnectException(
                        sanitizeMessage("Failed to write record", e), e
                    );
                }
            }
        }

        // Flush batch to external system
    }

    @Override
    public void flush(Map<TopicPartition, OffsetAndMetadata> offsets) {
        // Ensure all buffered writes are committed to the external system.
        // Called before Connect commits consumer offsets.
    }

    @Override
    public void open(Collection<TopicPartition> partitions) {
        // Called after rebalance — set up per-partition resources
    }

    @Override
    public void close(Collection<TopicPartition> partitions) {
        // Called before rebalance — tear down per-partition resources
    }

    @Override
    public void stop() {
        // Close connections, release resources
    }
}
```

**Key sink patterns:**
- **Batching**: Buffer records in `put()`, flush on threshold (count or size). Don't write one record at a time.
- **Idempotent writes**: Use upsert/merge semantics where possible. At-least-once delivery means you'll see duplicates after restarts.
- **put() + flush() timing**: Both must complete within the consumer's `session.timeout.ms` or the task gets evicted from the consumer group.
- **Rebalance lifecycle**: `open()`/`close()` bracket partition assignments. Use them if your external system has per-partition state (e.g., per-partition files, connections, or transactions).
- **ErrantRecordReporter**: Route bad records to a dead letter queue instead of failing the entire task. Always wrap in try-catch for older Connect runtimes.

## ConfigDef & Configuration

```java
public class MyConnectorConfig extends AbstractConfig {

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
        // Connection
        .define("connection.url", Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                Importance.HIGH, "URL of the external system")
        .define("connection.user", Type.STRING, "",
                Importance.HIGH, "Username for authentication")
        .define("connection.password", Type.PASSWORD, "",
                Importance.HIGH, "Password for authentication")

        // Behavior
        .define("topic", Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                Importance.HIGH, "Kafka topic to write to")
        .define("tasks.max", Type.INT, 1,
                Range.atLeast(1), Importance.MEDIUM,
                "Maximum number of tasks")
        .define("poll.interval.ms", Type.LONG, 5000L,
                Range.atLeast(100), Importance.MEDIUM,
                "Interval between polls when no data is available")

        // SSL (if applicable)
        .define("ssl.enabled", Type.BOOLEAN, false,
                Importance.MEDIUM, "Enable SSL/TLS")
        .define("ssl.truststore.location", Type.STRING, "",
                Importance.MEDIUM, "Path to SSL truststore")
        .define("ssl.truststore.password", Type.PASSWORD, "",
                Importance.MEDIUM, "Truststore password");

    public MyConnectorConfig(Map<String, String> props) {
        super(CONFIG_DEF, props);
    }
}
```

**ConfigDef rules:**
- Use `Type.PASSWORD` for passwords, API keys, tokens, secrets — anything sensitive
- Use validators: `Range.atLeast()`, `Range.between()`, `ValidString.in()`, `NonEmptyString`
- `Importance.HIGH` = required for basic operation. `MEDIUM` = common tuning. `LOW` = advanced.
- Group related configs with the `group` and `orderInGroup` parameters for UI display
- Always create a dedicated Config class wrapping `AbstractConfig` — don't parse raw props manually
- Use typed getters: `getString()`, `getInt()`, `getPassword().value()` — never `props.get()`

## Security

Connectors handle credentials and connect to external systems. Getting security wrong causes production incidents.

### Credential Handling

- Define ALL sensitive properties as `Type.PASSWORD` in ConfigDef
  - The Connect REST API masks PASSWORD fields in GET responses (shows `[hidden]`)
  - `getPassword()` returns a `Password` object — call `.value()` only at point of use (authentication)
- Support Config Providers — don't assume credentials are literal strings
  - Users may configure `${vault:/secret/path}`, `${file:/path/to/creds}`, `${env:API_KEY}`
  - The framework resolves these before passing to `start()`, but your code should not cache or log the resolved values

### Logging

- **NEVER** log the raw config map (`props`, `originals()`, `values()`) — it contains resolved passwords
- Log only specific non-sensitive config values you need for debugging
- In catch blocks, sanitize exception messages before logging — JDBC URLs, HTTP URLs, and client library errors often contain embedded credentials
- Use SLF4J parameterized logging (`log.error("Failed to connect to {}", host, e)`) — never string concatenation with sensitive values

```java
// BAD: logs password in clear text
log.info("Starting connector with config: {}", props);

// BAD: JDBC URL may contain password
log.error("Connection failed: " + e.getMessage());

// GOOD: log only what's needed
log.info("Starting connector for host={}, database={}", config.getString("host"),
    config.getString("database"));

// GOOD: sanitize error messages
log.error("Connection failed to host {}", config.getString("host"), e);
```

### Error Message Sanitization

When wrapping exceptions in `ConnectException`, strip credentials:

```java
private String sanitizeMessage(String context, Exception e) {
    String msg = e.getMessage();
    if (msg != null) {
        // Strip common credential patterns from URLs
        msg = msg.replaceAll("://[^@]+@", "://***@");
        // Strip password= parameters
        msg = msg.replaceAll("password=[^&\\s]+", "password=***");
    }
    return context + ": " + msg;
}
```

### SSL/TLS

For connectors that communicate over the network, define SSL configs following Kafka's naming convention:
- `ssl.enabled` (BOOLEAN), `ssl.truststore.location` (STRING), `ssl.truststore.password` (PASSWORD)
- `ssl.keystore.location` (STRING), `ssl.keystore.password` (PASSWORD), `ssl.key.password` (PASSWORD)
- `ssl.enabled.protocols` (STRING, default `"TLSv1.2,TLSv1.3"`)

### Sensitive Data in Records

- `sourcePartition` and `sourceOffset` maps are stored in Kafka's internal `connect-offsets` topic — **never** include credentials, tokens, or PII in these maps
- If the external system's position identifier contains sensitive data, hash or encode it

## Schema & Data Conversion

Build schemas with `SchemaBuilder`, populate with `Struct`:

```java
// Define a schema
Schema schema = SchemaBuilder.struct()
    .name("com.example.Order")
    .version(1)
    .field("id", Schema.INT64_SCHEMA)
    .field("customer", Schema.STRING_SCHEMA)
    .field("amount", Decimal.schema(2))            // Logical type
    .field("created_at", Timestamp.SCHEMA)          // Logical type
    .field("notes", Schema.OPTIONAL_STRING_SCHEMA)  // Nullable field
    .build();

// Populate a struct
Struct value = new Struct(schema)
    .put("id", 42L)
    .put("customer", "Acme Corp")
    .put("amount", new BigDecimal("99.95"))
    .put("created_at", new java.util.Date())
    .put("notes", null);
```

**Schema types**: `INT8`, `INT16`, `INT32`, `INT64`, `FLOAT32`, `FLOAT64`, `BOOLEAN`, `STRING`, `BYTES`, `ARRAY`, `MAP`, `STRUCT`

**Logical types** (named schemas over primitive types):
- `org.apache.kafka.connect.data.Timestamp` — milliseconds since epoch as INT64
- `org.apache.kafka.connect.data.Date` — days since epoch as INT32
- `org.apache.kafka.connect.data.Time` — milliseconds since midnight as INT32
- `org.apache.kafka.connect.data.Decimal` — `BYTES` with `scale` parameter

**Schemaless mode**: When you don't control the source schema, use `Schema.STRING_SCHEMA` with a JSON string value, or use a `Map<String, Object>` with `null` schema.

For the full Schema, SchemaBuilder, Struct, and ConfigDef API surface, read [references/connect-api.md](references/connect-api.md).

## Production Patterns

Patterns proven at scale in Debezium and Confluent connectors. Apply the ones relevant to your connector.

### Snapshot Modes (Source)

Most source connectors need to handle existing data, not just new changes. Expose snapshot mode as config:
- **initial** — snapshot existing data on first start, then switch to incremental/streaming
- **never** — skip snapshot, stream only (for connectors that don't need history)
- **when_needed** — auto-snapshot if stored offset position is no longer available (e.g., log compaction, API cursor expired)

Design snapshots as a pluggable strategy so modes can be added without changing core logic.

### Heartbeat Topics (Source)

When the source system is quiet, no records flow and Connect's offsets don't advance. This causes:
- Stale offsets that may expire (database log positions get purged)
- Consumer lag monitors false-alarming on zero throughput

Fix: emit periodic heartbeat records on a dedicated topic (e.g., `__heartbeat.my-connector`). Configurable interval (default 30s). The heartbeat record carries the current source position, keeping offsets fresh.

### Offset Model Design

- Use **source-native coordinates**: database LSN, API pagination cursor, file byte offset, event timestamp — not synthetic IDs
- Make offset maps **human-readable** for debugging: `{"server": "prod-db-1", "lsn": "0/16B3748"}` not `{"pos": 23847293}`
- Include enough context to **resume without data loss** — when in doubt, include more rather than less
- Test the restart path: stop connector, verify it resumes from the correct position

### Recovery & Duplicate Tolerance

- Design for **at-least-once delivery** — this is Connect's standard guarantee
- Document that consumers should handle duplicates after restarts
- Implement configurable recovery: auto-snapshot when stored offset is unavailable
- For sink connectors: use idempotent writes (upsert) so replayed records don't cause duplicates in the target system

### Metrics (JMX)

Register MBeans for operational visibility:
- Records processed (counter), errors (counter), offset lag
- Snapshot progress (percentage), poll latency (histogram)
- Follow Kafka Connect's naming conventions: `kafka.connect:type=connector-task-metrics,connector=...,task=...`

### Signal Mechanism (Advanced)

For complex source connectors, consider a signal channel (database table, API endpoint, or Kafka topic) that operators can use to trigger actions without restarting:
- Re-snapshot a specific table
- Pause/resume processing for a subset of partitions
- Rotate credentials

## Common Gotchas

- **stop() threading**: `SourceTask.stop()` is called from a different thread than `poll()`. Use a `volatile boolean` flag — don't rely on closing a connection that `poll()` is using without synchronization.
- **Session timeout**: `SinkTask.put()` and `flush()` must complete within the consumer's `session.timeout.ms` (default 10s). If your writes are slow, increase this timeout or reduce batch size.
- **Don't busy-loop in poll()**: Return `null` or an empty list when no data. Sleep or use the external system's blocking read. The framework calls `poll()` again immediately — if you don't throttle, you'll saturate the CPU.
- **Schema evolution**: Add new fields as `OPTIONAL` with defaults for backward compatibility. Removing or renaming fields breaks existing consumers.
- **Offset maps are Maps**: Source offsets are `Map<String, ?>`, not scalars. Design your partition/offset key structure before writing code.
- **Task count**: `taskConfigs(maxTasks)` can return fewer configs than maxTasks, but not more. Return one config per logical partition of work.
- **Provided scope**: Kafka Connect API JARs (`connect-api`, `connect-runtime`, `kafka-clients`) must be `<scope>provided</scope>` in your POM — the Connect worker already has them on the classpath. Bundling them causes version conflicts.
- **context.raiseError()**: For unrecoverable errors, call `context.raiseError(e)` to move the connector to FAILED state. Don't swallow exceptions silently — it makes debugging impossible.
- **Password round-tripping**: The Connect REST API masks PASSWORD fields. You cannot GET a connector's config and PUT it back without re-supplying the actual password values.

## Reference Guides

| Guide | Read When |
|-------|-----------|
| [references/connect-api.md](references/connect-api.md) | Need exact method signatures for Connector, Task, Schema, SchemaBuilder, Struct, ConfigDef, Context interfaces |
| [references/build-and-package.md](references/build-and-package.md) | Setting up Maven POM, packaging as plugin directory or uber-JAR, testing with Testcontainers, deployment |
