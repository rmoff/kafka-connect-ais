# Kafka Connect API Surface

Complete method signatures for connector development. This is the reference — consult it when you need exact signatures, type enums, or interface contracts.

## Connector (Abstract Base Class)

```java
public abstract class Connector {
    // Lifecycle
    void initialize(ConnectorContext ctx)
    abstract void start(Map<String, String> props)
    void reconfigure(Map<String, String> props)  // Default: stop() + start()
    abstract void stop()

    // Task management
    abstract Class<? extends Task> taskClass()
    abstract List<Map<String, String>> taskConfigs(int maxTasks)

    // Configuration
    abstract ConfigDef config()
    Config validate(Map<String, String> connectorConfigs)  // Default: uses config()

    // Context
    protected ConnectorContext context()
}
```

## SourceConnector

```java
public abstract class SourceConnector extends Connector {
    @Override
    protected SourceConnectorContext context()

    // Exactly-once support (Kafka 3.3+)
    ExactlyOnceSupport exactlyOnceSupport(Map<String, String> connectorConfig)
    // Returns: SUPPORTED, UNSUPPORTED, or null

    // Transaction boundaries (Kafka 3.3+)
    ConnectorTransactionBoundaries canDefineTransactionBoundaries(
        Map<String, String> connectorConfig)

    // Offset management (Kafka 3.6+)
    boolean alterOffsets(Map<String, String> connectorConfig,
                        Map<Map<String, ?>, Map<String, ?>> offsets)
}
```

## SinkConnector

```java
public abstract class SinkConnector extends Connector {
    public static final String TOPICS_CONFIG = "topics"

    @Override
    protected SinkConnectorContext context()

    // Offset management
    boolean alterOffsets(Map<String, String> connectorConfig,
                        Map<TopicPartition, Long> offsets)
}
```

## SourceTask

```java
public abstract class SourceTask implements Task {
    protected SourceTaskContext context;

    void initialize(SourceTaskContext context)

    abstract void start(Map<String, String> props)

    // Main data fetching method. Called repeatedly by the framework.
    // Return null or empty list when no data — don't block forever.
    abstract List<SourceRecord> poll() throws InterruptedException

    abstract void stop()  // Called from a DIFFERENT thread than poll()

    // Called after framework commits offsets for a batch
    void commit() throws InterruptedException

    // Called after each record is successfully sent to Kafka
    void commitRecord(SourceRecord record, RecordMetadata metadata)
        throws InterruptedException
}
```

## SinkTask

```java
public abstract class SinkTask implements Task {
    protected SinkTaskContext context;

    public static final String TOPICS_CONFIG = "topics"
    public static final String TOPICS_REGEX_CONFIG = "topics.regex"

    void initialize(SinkTaskContext context)

    abstract void start(Map<String, String> props)

    // Write records to external system. Called with batches of records.
    abstract void put(Collection<SinkRecord> records) throws ConnectException

    // Ensure all buffered writes are committed. Called before offset commit.
    void flush(Map<TopicPartition, OffsetAndMetadata> currentOffsets)

    // Return offsets that are safe to commit. Called before offset commit.
    // Default: returns currentOffsets unchanged.
    Map<TopicPartition, OffsetAndMetadata> preCommit(
        Map<TopicPartition, OffsetAndMetadata> currentOffsets)

    // Partition lifecycle — called during consumer rebalances
    void open(Collection<TopicPartition> partitions)
    void close(Collection<TopicPartition> partitions)

    abstract void stop()
}
```

## SourceRecord

```java
public class SourceRecord extends ConnectRecord<SourceRecord> {
    // Full constructor
    SourceRecord(Map<String, ?> sourcePartition,
                 Map<String, ?> sourceOffset,
                 String topic,
                 Integer partition,        // Kafka partition (null = default)
                 Schema keySchema,
                 Object key,
                 Schema valueSchema,
                 Object value,
                 Long timestamp,
                 Iterable<Header> headers)

    // Common constructor (no explicit partition, timestamp, headers)
    SourceRecord(Map<String, ?> sourcePartition,
                 Map<String, ?> sourceOffset,
                 String topic,
                 Schema keySchema, Object key,
                 Schema valueSchema, Object value)

    // Key/value-only constructor (no key)
    SourceRecord(Map<String, ?> sourcePartition,
                 Map<String, ?> sourceOffset,
                 String topic,
                 Schema valueSchema, Object value)

    Map<String, ?> sourcePartition()
    Map<String, ?> sourceOffset()
}
```

## SinkRecord

```java
public class SinkRecord extends ConnectRecord<SinkRecord> {
    SinkRecord(String topic, int partition,
               Schema keySchema, Object key,
               Schema valueSchema, Object value,
               long kafkaOffset,
               Long timestamp, TimestampType timestampType,
               Iterable<Header> headers,
               String originalTopic,
               Integer originalKafkaPartition,
               long originalKafkaOffset)

    long kafkaOffset()
    TimestampType timestampType()  // CREATE_TIME, LOG_APPEND_TIME, NO_TIMESTAMP_TYPE
    String originalTopic()
    Integer originalKafkaPartition()
    long originalKafkaOffset()
}
```

## Schema

```java
public interface Schema {
    // Type enum
    enum Type { INT8, INT16, INT32, INT64, FLOAT32, FLOAT64,
                BOOLEAN, STRING, BYTES, ARRAY, MAP, STRUCT }

    Type type()
    boolean isOptional()
    Object defaultValue()
    String name()         // Named schemas for logical types
    Integer version()
    String doc()
    Map<String, String> parameters()

    // For ARRAY type
    Schema valueSchema()

    // For MAP type
    Schema keySchema()
    Schema valueSchema()

    // For STRUCT type
    List<Field> fields()
    Field field(String fieldName)

    // Predefined constants
    static Schema INT8_SCHEMA, INT16_SCHEMA, INT32_SCHEMA, INT64_SCHEMA
    static Schema FLOAT32_SCHEMA, FLOAT64_SCHEMA
    static Schema BOOLEAN_SCHEMA, STRING_SCHEMA, BYTES_SCHEMA
    // Optional variants
    static Schema OPTIONAL_INT8_SCHEMA, OPTIONAL_INT16_SCHEMA, ...
    static Schema OPTIONAL_FLOAT32_SCHEMA, OPTIONAL_FLOAT64_SCHEMA
    static Schema OPTIONAL_BOOLEAN_SCHEMA, OPTIONAL_STRING_SCHEMA, OPTIONAL_BYTES_SCHEMA
}
```

## SchemaBuilder

```java
public class SchemaBuilder {
    // Static factory methods
    static SchemaBuilder struct()
    static SchemaBuilder int8(), int16(), int32(), int64()
    static SchemaBuilder float32(), float64()
    static SchemaBuilder bool()
    static SchemaBuilder string()
    static SchemaBuilder bytes()
    static SchemaBuilder array(Schema valueSchema)
    static SchemaBuilder map(Schema keySchema, Schema valueSchema)

    // Builder methods (chainable)
    SchemaBuilder optional()
    SchemaBuilder required()
    SchemaBuilder defaultValue(Object value)
    SchemaBuilder name(String name)
    SchemaBuilder version(Integer version)
    SchemaBuilder doc(String documentation)
    SchemaBuilder parameter(String propertyName, String propertyValue)
    SchemaBuilder parameters(Map<String, String> props)

    // Struct-specific
    SchemaBuilder field(String fieldName, Schema fieldSchema)
    List<Field> fields()
    Field field(String fieldName)

    // Build immutable Schema
    Schema build()
}
```

**Example:**

```java
Schema orderSchema = SchemaBuilder.struct()
    .name("com.example.Order").version(1)
    .field("id", Schema.INT64_SCHEMA)
    .field("customer", Schema.STRING_SCHEMA)
    .field("amount", Decimal.schema(2))
    .field("items", SchemaBuilder.array(Schema.STRING_SCHEMA).build())
    .field("metadata", SchemaBuilder.map(
        Schema.STRING_SCHEMA, Schema.STRING_SCHEMA).optional().build())
    .field("notes", Schema.OPTIONAL_STRING_SCHEMA)
    .build();
```

## Struct

```java
public class Struct {
    Struct(Schema schema)

    Schema schema()

    // Generic access
    Object get(String fieldName)
    Object get(Field field)
    Object getWithoutDefault(String fieldName)

    // Typed getters
    Byte getInt8(String fieldName)
    Short getInt16(String fieldName)
    Integer getInt32(String fieldName)
    Long getInt64(String fieldName)
    Float getFloat32(String fieldName)
    Double getFloat64(String fieldName)
    Boolean getBoolean(String fieldName)
    String getString(String fieldName)
    byte[] getBytes(String fieldName)
    <T> List<T> getArray(String fieldName)
    <K, V> Map<K, V> getMap(String fieldName)
    Struct getStruct(String fieldName)

    // Fluent setters
    Struct put(String fieldName, Object value)
    Struct put(Field field, Object value)

    // Validates all required fields are set
    void validate()
}
```

## Logical Types

Built-in named schemas over primitive types:

```java
// Timestamp — milliseconds since Unix epoch, stored as INT64
org.apache.kafka.connect.data.Timestamp.SCHEMA
Timestamp.builder()  // Returns SchemaBuilder for customization

// Date — days since Unix epoch, stored as INT32
org.apache.kafka.connect.data.Date.SCHEMA

// Time — milliseconds since midnight, stored as INT32
org.apache.kafka.connect.data.Time.SCHEMA

// Decimal — arbitrary-precision decimal, stored as BYTES
org.apache.kafka.connect.data.Decimal.schema(int scale)
// scale = number of digits to the right of the decimal point
// Java type: java.math.BigDecimal
```

## ConfigDef

```java
public class ConfigDef {
    // Type enum
    enum Type { BOOLEAN, STRING, INT, SHORT, LONG, DOUBLE, LIST, CLASS, PASSWORD }

    // Importance enum
    enum Importance { HIGH, MEDIUM, LOW }

    // Width enum (for UI display)
    enum Width { NONE, SHORT, MEDIUM, LONG }

    // Full define method
    ConfigDef define(String name, Type type, Object defaultValue,
                     Validator validator, Importance importance,
                     String documentation,
                     String group, int orderInGroup,
                     Width width, String displayName,
                     List<String> dependents, Recommender recommender)

    // Common shorthand
    ConfigDef define(String name, Type type, Object defaultValue,
                     Importance importance, String documentation)

    // No default (required field)
    ConfigDef define(String name, Type type, Importance importance,
                     String documentation)

    // Built-in validators
    static class Range {
        static Validator atLeast(Number min)
        static Validator between(Number min, Number max)
    }
    static class ValidString {
        static Validator in(String... validStrings)
    }
    static class CaseInsensitiveValidString {
        static Validator in(String... validStrings)
    }
    static class NonEmptyString implements Validator {}
    static class NonEmptyStringWithoutControlChars implements Validator {}
    static class CompositeValidator {
        static Validator of(Validator... validators)
    }

    // Use ConfigDef.NO_DEFAULT_VALUE for required fields
    static final Object NO_DEFAULT_VALUE
}
```

## AbstractConfig

```java
public class AbstractConfig {
    AbstractConfig(ConfigDef definition, Map<String, String> originals)

    // Typed getters
    Object get(String key)
    String getString(String key)
    int getInt(String key)
    short getShort(String key)
    long getLong(String key)
    double getDouble(String key)
    boolean getBoolean(String key)
    List<String> getList(String key)
    Class<?> getClass(String key)
    Password getPassword(String key)  // Returns Password object

    // Raw access (CAUTION: contains resolved passwords)
    Map<String, Object> values()
    Map<String, String> originals()
    Map<String, String> originalsWithPrefix(String prefix)
}
```

## Password

```java
public class Password {
    Password(String value)
    String value()        // Returns the actual password string
    String toString()     // Returns "[hidden]" — safe to log
}
```

## Context Interfaces

### ConnectorContext

```java
public interface ConnectorContext {
    void requestTaskReconfiguration()   // Trigger task reconfig
    void raiseError(Exception e)        // Move connector to FAILED state
}
```

### SourceTaskContext

```java
public interface SourceTaskContext {
    Map<String, String> configs()
    OffsetStorageReader offsetStorageReader()
    TransactionContext transactionContext()  // Kafka 3.2+
}
```

### SinkTaskContext

```java
public interface SinkTaskContext {
    Map<String, String> configs()
    Set<TopicPartition> assignment()

    // Offset control — seek to a specific offset for replay
    void offset(Map<TopicPartition, Long> offsets)
    void offset(TopicPartition tp, long offset)

    // Flow control
    void pause(TopicPartition... partitions)
    void resume(TopicPartition... partitions)
    void timeout(long timeoutMs)
    void requestCommit()

    // Error handling (Connect 2.6+)
    ErrantRecordReporter errantRecordReporter()
}
```

### OffsetStorageReader

```java
public interface OffsetStorageReader {
    // Get offset for a single partition
    <T> Map<String, Object> offset(Map<String, T> partition)

    // Get offsets for multiple partitions
    <T> Map<Map<String, T>, Map<String, Object>> offsets(
        Collection<Map<String, T>> partitions)
}
```

### ErrantRecordReporter

```java
public interface ErrantRecordReporter {
    // Report a problematic record — routes to DLQ if configured
    Future<Void> report(SinkRecord record, Throwable error)
}
```

## ConnectException

```java
public class ConnectException extends KafkaException {
    ConnectException(String s)
    ConnectException(String s, Throwable throwable)
    ConnectException(Throwable throwable)
}
```

Throw `ConnectException` for connector-specific errors. The framework catches it and moves the task to FAILED state. For transient errors you want to retry, implement retry logic in your task code and only throw `ConnectException` when retries are exhausted.
