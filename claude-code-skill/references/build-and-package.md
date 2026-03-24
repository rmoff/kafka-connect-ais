# Build, Package & Test

## Maven POM Template

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>kafka-connect-my-connector</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>11</java.version>
        <kafka.version>3.7.0</kafka.version>
        <maven.compiler.source>${java.version}</maven.compiler.source>
        <maven.compiler.target>${java.version}</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- Kafka Connect API — PROVIDED by the Connect runtime -->
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>connect-api</artifactId>
            <version>${kafka.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Connector-specific dependencies — bundled in the plugin -->
        <!-- Example: HTTP client, JDBC driver, SDK, etc. -->
        <!--
        <dependency>
            <groupId>org.apache.httpcomponents.client5</groupId>
            <artifactId>httpclient5</artifactId>
            <version>5.3</version>
        </dependency>
        -->

        <!-- Logging — provided by Connect runtime -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>1.7.36</version>
            <scope>provided</scope>
        </dependency>

        <!-- Test dependencies -->
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>5.11.0</version>
            <scope>test</scope>
        </dependency>
        <!-- Integration tests with real services -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <version>1.19.7</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Unit tests -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>

            <!-- Integration tests (*IT.java) -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <version>3.2.5</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>

            <!-- Create uber-JAR with all dependencies -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.2</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <artifactSet>
                                <excludes>
                                    <!-- Don't bundle provided dependencies -->
                                    <exclude>org.apache.kafka:*</exclude>
                                    <exclude>org.slf4j:*</exclude>
                                </excludes>
                            </artifactSet>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

**Adapt the POM:**
- Set `kafka.version` to match your target Connect cluster
- Add your connector's specific dependencies (HTTP client, JDBC driver, SDK, etc.)
- Java 11 minimum (Kafka 3.x requirement)

## Gradle Alternative

```kotlin
// build.gradle.kts
plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    compileOnly("org.apache.kafka:connect-api:3.7.0")
    compileOnly("org.slf4j:slf4j-api:1.7.36")

    // Connector-specific dependencies
    // implementation("org.apache.httpcomponents.client5:httpclient5:5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.testcontainers:testcontainers:1.19.7")
}

tasks.shadowJar {
    dependencies {
        exclude(dependency("org.apache.kafka:.*"))
        exclude(dependency("org.slf4j:.*"))
    }
}
```

## Plugin Packaging

Kafka Connect loads plugins from directories listed in `plugin.path`. Two packaging options:

### Option 1: Plugin Directory (recommended)

```
my-connector/
└── lib/
    ├── kafka-connect-my-connector-1.0.0.jar
    ├── httpclient5-5.3.jar
    └── ... (all runtime dependencies)
```

Place this directory inside a `plugin.path` location. Connect discovers plugins via `ServiceLoader` or classpath scanning.

### Option 2: Uber-JAR

A single fat JAR containing your connector code and all dependencies (minus provided ones). Simpler to distribute but harder to debug classpath issues.

The `maven-shade-plugin` or `shadow` Gradle plugin creates this.

### Installation

```bash
# Copy plugin to Connect worker's plugin path
cp -r my-connector/ /usr/share/confluent-hub-components/

# Or for Docker, mount as a volume
# docker-compose.yml:
#   volumes:
#     - ./my-connector:/usr/share/confluent-hub-components/my-connector

# Restart the Connect worker
# Verify plugin is loaded:
curl -s http://localhost:8083/connector-plugins | jq '.[].class'
```

## Testing

### Unit Tests

Test config validation, data conversion, and task logic with mocked contexts.

```java
public class MyConnectorConfigTest {

    @Test
    public void testValidConfig() {
        Map<String, String> props = new HashMap<>();
        props.put("connection.url", "http://example.com");
        props.put("topic", "my-topic");
        // Should not throw
        new MyConnectorConfig(props);
    }

    @Test(expected = ConfigException.class)
    public void testMissingRequiredConfig() {
        new MyConnectorConfig(Collections.emptyMap());
    }

    @Test
    public void testConfigDefValidation() {
        ConfigDef configDef = MyConnectorConfig.CONFIG_DEF;
        // Verify all expected keys exist
        assertTrue(configDef.configKeys().containsKey("connection.url"));
        assertTrue(configDef.configKeys().containsKey("connection.password"));
        // Verify password type
        assertEquals(ConfigDef.Type.PASSWORD,
            configDef.configKeys().get("connection.password").type);
    }
}
```

```java
public class MySourceTaskTest {

    @Test
    public void testPollReturnsRecords() throws Exception {
        MySourceTask task = new MySourceTask();

        // Mock the context
        SourceTaskContext context = mock(SourceTaskContext.class);
        OffsetStorageReader reader = mock(OffsetStorageReader.class);
        when(context.offsetStorageReader()).thenReturn(reader);
        when(reader.offset(any())).thenReturn(null); // No stored offset

        task.initialize(context);
        task.start(validConfigProps());

        List<SourceRecord> records = task.poll();
        assertNotNull(records);
        // Verify record structure, schemas, values...
    }

    @Test
    public void testPollResumesFromOffset() throws Exception {
        MySourceTask task = new MySourceTask();

        SourceTaskContext context = mock(SourceTaskContext.class);
        OffsetStorageReader reader = mock(OffsetStorageReader.class);
        when(context.offsetStorageReader()).thenReturn(reader);

        // Simulate stored offset
        Map<String, Object> storedOffset = Collections.singletonMap("position", 42L);
        when(reader.offset(any())).thenReturn(storedOffset);

        task.initialize(context);
        task.start(validConfigProps());

        // Verify task resumes from position 42, not from the beginning
    }
}
```

### Integration Tests with Testcontainers

Test against real external systems in Docker containers. Name files `*IT.java` so `maven-failsafe-plugin` picks them up.

```java
public class MySourceConnectorIT {

    @ClassRule
    public static GenericContainer<?> externalSystem =
        new GenericContainer<>("my-system:latest")
            .withExposedPorts(8080);

    @Test
    public void testEndToEnd() throws Exception {
        String url = "http://" + externalSystem.getHost()
            + ":" + externalSystem.getMappedPort(8080);

        // Seed test data into the external system
        // ...

        // Start the task
        MySourceTask task = new MySourceTask();
        // ... initialize, start, poll ...

        // Verify records match seeded data
    }
}
```

### Failure Scenario Tests

Test these explicitly — they're where connectors break in production:
- **Connection loss during poll/put**: Mock the client to throw IOException mid-batch
- **Schema change mid-stream**: Feed records with different schemas, verify graceful handling
- **Expired credentials**: Mock auth failure, verify clean error message (no credentials in logs)
- **Offset unavailable**: Return null from `offsetStorageReader()`, verify snapshot/recovery behavior
- **Slow external system**: Verify task doesn't exceed session timeout

### Running Tests

```bash
# Unit tests only
mvn test

# Unit + integration tests
mvn verify

# Skip integration tests (faster local development)
mvn verify -DskipITs
```

## Confluent Hub Packaging

For distributing via Confluent Hub, create a `manifest.json`:

```json
{
  "name": "my-connector",
  "version": "1.0.0",
  "title": "My Custom Connector",
  "description": "A connector for ...",
  "owner": {
    "username": "myorg",
    "name": "My Organization"
  },
  "support": {
    "summary": "Community support"
  },
  "tags": ["my-system"],
  "requirements": ["Kafka >= 2.6"],
  "features": {
    "supported_encodings": ["any"],
    "single_message_transforms": true,
    "confluent_control_center_integration": true,
    "kafka_connect_api": true
  },
  "component_types": ["sink"],
  "release_date": "2024-01-01"
}
```

Place `manifest.json` alongside the `lib/` directory in your plugin package.
