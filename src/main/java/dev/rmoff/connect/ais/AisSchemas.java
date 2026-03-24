package dev.rmoff.connect.ais;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;

public class AisSchemas {

    private static final String NAMESPACE = "dev.rmoff.connect.ais";

    public static final Schema KEY_SCHEMA = Schema.INT32_SCHEMA;

    // Flat schema used when topic.per.type=false — all fields present, type-specific ones optional
    public static final Schema FLAT_VALUE_SCHEMA = buildFlatSchema();

    // Per-type schemas used when topic.per.type=true
    public static final Schema POSITION_SCHEMA = buildPositionSchema();
    public static final Schema STATIC_SCHEMA = buildStaticSchema();
    public static final Schema BASE_STATION_SCHEMA = buildBaseStationSchema();
    public static final Schema SAFETY_SCHEMA = buildSafetySchema();
    public static final Schema ATON_SCHEMA = buildAtonSchema();
    public static final Schema BINARY_SCHEMA = buildBinarySchema();
    public static final Schema OTHER_SCHEMA = buildOtherSchema();

    /**
     * Returns the per-type schema for a given AIS message type.
     */
    public static Schema schemaForCategory(int msgType) {
        switch (msgType) {
            case 1: case 2: case 3: case 9: case 18: case 19: case 27:
                return POSITION_SCHEMA;
            case 5: case 24:
                return STATIC_SCHEMA;
            case 4: case 11:
                return BASE_STATION_SCHEMA;
            case 12: case 14:
                return SAFETY_SCHEMA;
            case 21:
                return ATON_SCHEMA;
            case 6: case 8: case 25: case 26:
                return BINARY_SCHEMA;
            default:
                return OTHER_SCHEMA;
        }
    }

    /**
     * Returns the topic suffix for a given AIS message type.
     */
    public static String topicSuffix(int msgType) {
        switch (msgType) {
            case 1: case 2: case 3: case 9: case 18: case 19: case 27:
                return ".position";
            case 5: case 24:
                return ".static";
            case 4: case 11:
                return ".base_station";
            case 12: case 14:
                return ".safety";
            case 21:
                return ".aton";
            case 6: case 8: case 25: case 26:
                return ".binary";
            default:
                return ".other";
        }
    }

    private static SchemaBuilder commonFields(String name) {
        return SchemaBuilder.struct()
                .name(name)
                .field("mmsi", Schema.INT32_SCHEMA)
                .field("msg_type", Schema.INT32_SCHEMA)
                .field("receive_timestamp", Schema.INT64_SCHEMA)
                .field("source_station", Schema.OPTIONAL_STRING_SCHEMA)
                .field("raw_nmea", Schema.STRING_SCHEMA);
    }

    private static void addPositionFields(SchemaBuilder builder) {
        builder.field("latitude", Schema.OPTIONAL_FLOAT64_SCHEMA)
                .field("longitude", Schema.OPTIONAL_FLOAT64_SCHEMA)
                .field("speed_over_ground", Schema.OPTIONAL_FLOAT64_SCHEMA)
                .field("course_over_ground", Schema.OPTIONAL_FLOAT64_SCHEMA)
                .field("true_heading", Schema.OPTIONAL_INT32_SCHEMA)
                .field("nav_status", Schema.OPTIONAL_INT32_SCHEMA)
                .field("nav_status_text", Schema.OPTIONAL_STRING_SCHEMA)
                .field("rate_of_turn", Schema.OPTIONAL_INT32_SCHEMA)
                .field("timestamp_second", Schema.OPTIONAL_INT32_SCHEMA)
                .field("altitude", Schema.OPTIONAL_INT32_SCHEMA)
                // Type 19 adds these to position reports
                .field("ship_name", Schema.OPTIONAL_STRING_SCHEMA)
                .field("ship_type", Schema.OPTIONAL_INT32_SCHEMA)
                .field("ship_type_text", Schema.OPTIONAL_STRING_SCHEMA)
                .field("dimension_to_bow", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dimension_to_stern", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dimension_to_port", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dimension_to_starboard", Schema.OPTIONAL_INT32_SCHEMA);
    }

    private static void addStaticFields(SchemaBuilder builder) {
        builder.field("imo_number", Schema.OPTIONAL_INT64_SCHEMA)
                .field("callsign", Schema.OPTIONAL_STRING_SCHEMA)
                .field("ship_name", Schema.OPTIONAL_STRING_SCHEMA)
                .field("ship_type", Schema.OPTIONAL_INT32_SCHEMA)
                .field("ship_type_text", Schema.OPTIONAL_STRING_SCHEMA)
                .field("dimension_to_bow", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dimension_to_stern", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dimension_to_port", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dimension_to_starboard", Schema.OPTIONAL_INT32_SCHEMA)
                .field("draught", Schema.OPTIONAL_FLOAT64_SCHEMA)
                .field("destination", Schema.OPTIONAL_STRING_SCHEMA)
                .field("eta", Schema.OPTIONAL_STRING_SCHEMA)
                .field("part_number", Schema.OPTIONAL_INT32_SCHEMA);
    }

    private static void addBaseStationFields(SchemaBuilder builder) {
        builder.field("latitude", Schema.OPTIONAL_FLOAT64_SCHEMA)
                .field("longitude", Schema.OPTIONAL_FLOAT64_SCHEMA)
                .field("base_station_timestamp", Schema.OPTIONAL_STRING_SCHEMA);
    }

    private static void addSafetyFields(SchemaBuilder builder) {
        builder.field("safety_text", Schema.OPTIONAL_STRING_SCHEMA)
                .field("dest_mmsi", Schema.OPTIONAL_INT32_SCHEMA);
    }

    private static void addAtonFields(SchemaBuilder builder) {
        builder.field("aid_type", Schema.OPTIONAL_INT32_SCHEMA)
                .field("aid_name", Schema.OPTIONAL_STRING_SCHEMA)
                .field("latitude", Schema.OPTIONAL_FLOAT64_SCHEMA)
                .field("longitude", Schema.OPTIONAL_FLOAT64_SCHEMA)
                .field("dimension_to_bow", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dimension_to_stern", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dimension_to_port", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dimension_to_starboard", Schema.OPTIONAL_INT32_SCHEMA)
                .field("off_position", Schema.OPTIONAL_BOOLEAN_SCHEMA)
                .field("virtual_aid", Schema.OPTIONAL_BOOLEAN_SCHEMA);
    }

    private static void addBinaryFields(SchemaBuilder builder) {
        builder.field("dac", Schema.OPTIONAL_INT32_SCHEMA)
                .field("fid", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dest_mmsi", Schema.OPTIONAL_INT32_SCHEMA)
                .field("binary_data", Schema.OPTIONAL_STRING_SCHEMA);
    }

    private static void addAckFields(SchemaBuilder builder) {
        builder.field("dest_mmsi_1", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dest_mmsi_2", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dest_mmsi_3", Schema.OPTIONAL_INT32_SCHEMA)
                .field("dest_mmsi_4", Schema.OPTIONAL_INT32_SCHEMA);
    }

    private static Schema buildFlatSchema() {
        SchemaBuilder builder = commonFields(NAMESPACE + ".AisValue");
        addPositionFields(builder);
        // Static fields that aren't already added by position
        builder.field("imo_number", Schema.OPTIONAL_INT64_SCHEMA)
                .field("callsign", Schema.OPTIONAL_STRING_SCHEMA)
                .field("draught", Schema.OPTIONAL_FLOAT64_SCHEMA)
                .field("destination", Schema.OPTIONAL_STRING_SCHEMA)
                .field("eta", Schema.OPTIONAL_STRING_SCHEMA)
                .field("part_number", Schema.OPTIONAL_INT32_SCHEMA);
        // Base station
        builder.field("base_station_timestamp", Schema.OPTIONAL_STRING_SCHEMA);
        // Safety
        builder.field("safety_text", Schema.OPTIONAL_STRING_SCHEMA)
                .field("dest_mmsi", Schema.OPTIONAL_INT32_SCHEMA);
        // AtoN
        builder.field("aid_type", Schema.OPTIONAL_INT32_SCHEMA)
                .field("aid_name", Schema.OPTIONAL_STRING_SCHEMA)
                .field("off_position", Schema.OPTIONAL_BOOLEAN_SCHEMA)
                .field("virtual_aid", Schema.OPTIONAL_BOOLEAN_SCHEMA);
        // Binary
        builder.field("dac", Schema.OPTIONAL_INT32_SCHEMA)
                .field("fid", Schema.OPTIONAL_INT32_SCHEMA)
                .field("binary_data", Schema.OPTIONAL_STRING_SCHEMA);
        // Acknowledge
        addAckFields(builder);
        return builder.build();
    }

    private static Schema buildPositionSchema() {
        SchemaBuilder builder = commonFields(NAMESPACE + ".Position");
        addPositionFields(builder);
        return builder.build();
    }

    private static Schema buildStaticSchema() {
        SchemaBuilder builder = commonFields(NAMESPACE + ".Static");
        addStaticFields(builder);
        return builder.build();
    }

    private static Schema buildBaseStationSchema() {
        SchemaBuilder builder = commonFields(NAMESPACE + ".BaseStation");
        addBaseStationFields(builder);
        return builder.build();
    }

    private static Schema buildSafetySchema() {
        SchemaBuilder builder = commonFields(NAMESPACE + ".Safety");
        addSafetyFields(builder);
        return builder.build();
    }

    private static Schema buildAtonSchema() {
        SchemaBuilder builder = commonFields(NAMESPACE + ".AtoN");
        addAtonFields(builder);
        return builder.build();
    }

    private static Schema buildBinarySchema() {
        SchemaBuilder builder = commonFields(NAMESPACE + ".Binary");
        addBinaryFields(builder);
        addAckFields(builder);
        return builder.build();
    }

    private static Schema buildOtherSchema() {
        return commonFields(NAMESPACE + ".Other").build();
    }
}
