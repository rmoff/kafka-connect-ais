package dev.rmoff.connect.ais;

import dk.dma.ais.message.*;
import dk.dma.enav.model.geometry.Position;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

public class AisRecordConverter {

    private static final Logger log = LoggerFactory.getLogger(AisRecordConverter.class);

    // Message types that are always fully decoded
    private static final Set<Integer> COMMON_TYPES = Set.of(
            1, 2, 3, 4, 5, 9, 11, 12, 14, 18, 19, 21, 24, 27
    );

    private static final String[] NAV_STATUS_TEXT = {
            "Under way using engine",
            "At anchor",
            "Not under command",
            "Restricted manoeuvrability",
            "Constrained by draught",
            "Moored",
            "Aground",
            "Engaged in fishing",
            "Under way sailing",
            "Reserved (HSC)",
            "Reserved (WIG)",
            "Power-driven vessel towing astern",
            "Power-driven vessel pushing ahead/alongside",
            "Reserved",
            "AIS-SART/MOB/EPIRB",
            "Undefined"
    };

    private final String topicBase;
    private final boolean topicPerType;
    private final boolean decodeCommonOnly;

    public AisRecordConverter(String topicBase, boolean topicPerType, boolean decodeCommonOnly) {
        this.topicBase = topicBase;
        this.topicPerType = topicPerType;
        this.decodeCommonOnly = decodeCommonOnly;
    }

    public SourceRecord convert(NmeaLineParser.ParseResult parsed,
                                Map<String, Object> sourcePartition,
                                Map<String, Object> sourceOffset) {
        AisMessage msg = parsed.message;
        int msgType = msg.getMsgId();

        String topic = topicPerType ? topicBase + AisSchemas.topicSuffix(msgType) : topicBase;
        Schema valueSchema = topicPerType ? AisSchemas.schemaForCategory(msgType) : AisSchemas.FLAT_VALUE_SCHEMA;

        Struct value = new Struct(valueSchema);

        // Common fields
        value.put("mmsi", msg.getUserId());
        value.put("msg_type", msgType);
        value.put("receive_timestamp", parsed.receiveTimestampMs);
        putIfExists(value, "source_station", parsed.sourceStation);
        value.put("raw_nmea", parsed.rawNmea);

        // Type-specific fields
        boolean shouldDecode = COMMON_TYPES.contains(msgType) || !decodeCommonOnly;
        if (shouldDecode) {
            try {
                populateTypeFields(value, msg);
            } catch (Exception e) {
                log.debug("Error populating type-specific fields for type {}: {}", msgType, e.getMessage());
            }
        }

        // Headers
        ConnectHeaders headers = new ConnectHeaders();
        headers.addString("ais.msg_type", String.valueOf(msgType));
        if (parsed.sourceStation != null) {
            headers.addString("ais.source_station", parsed.sourceStation);
        }

        return new SourceRecord(
                sourcePartition, sourceOffset,
                topic,
                null,
                AisSchemas.KEY_SCHEMA, msg.getUserId(),
                valueSchema, value,
                parsed.receiveTimestampMs,
                headers
        );
    }

    private void populateTypeFields(Struct value, AisMessage msg) {
        switch (msg.getMsgId()) {
            case 1: case 2: case 3:
                populatePositionReport(value, (AisPositionMessage) msg);
                break;
            case 4: case 11:
                populateBaseStation(value, (AisMessage4) msg);
                break;
            case 5:
                populateStaticVoyage(value, (AisMessage5) msg);
                break;
            case 9:
                populateSarAircraft(value, (AisMessage9) msg);
                break;
            case 12:
                populateSafetyAddressed(value, (AisMessage12) msg);
                break;
            case 14:
                populateSafetyBroadcast(value, (AisMessage14) msg);
                break;
            case 18:
                populateClassBPosition(value, (AisMessage18) msg);
                break;
            case 19:
                populateClassBExtended(value, (AisMessage19) msg);
                break;
            case 21:
                populateAtoN(value, (AisMessage21) msg);
                break;
            case 24:
                populateClassBStatic(value, (AisMessage24) msg);
                break;
            case 27:
                populateLongRange(value, (AisMessage27) msg);
                break;
            case 6:
                populateBinaryAddressed(value, (AisMessage6) msg);
                break;
            case 7: case 13:
                populateAcknowledge(value, (AisMessage7) msg);
                break;
            case 8:
                populateBinaryBroadcast(value, (AisMessage8) msg);
                break;
            default:
                break;
        }
    }

    private void populatePositionReport(Struct value, AisPositionMessage msg) {
        Position pos = msg.getPos().getGeoLocation();
        putValidLatLon(value, pos);
        putValidSog(value, msg.getSog());
        putValidCog(value, msg.getCog());
        putValidHeading(value, msg.getTrueHeading());
        putIfExists(value, "nav_status", msg.getNavStatus());
        putIfExists(value, "nav_status_text", navStatusText(msg.getNavStatus()));
        putValidRot(value, msg.getRot());
        putValidTimestampSecond(value, msg.getUtcSec());
    }

    private void populateBaseStation(Struct value, AisMessage4 msg) {
        Position pos = msg.getPos().getGeoLocation();
        putValidLatLon(value, pos);

        String ts = String.format("%04d-%02d-%02dT%02d:%02d:%02dZ",
                msg.getUtcYear(), msg.getUtcMonth(), msg.getUtcDay(),
                msg.getUtcHour(), msg.getUtcMinute(), msg.getUtcSecond());
        putIfExists(value, "base_station_timestamp", ts);
    }

    private void populateStaticVoyage(Struct value, AisMessage5 msg) {
        putIfExists(value, "imo_number", (long) msg.getImo());
        putIfExists(value, "callsign", trimAis(msg.getCallsign()));
        putIfExists(value, "ship_name", trimAis(msg.getName()));
        putIfExists(value, "ship_type", msg.getShipType());
        putIfExists(value, "ship_type_text", shipTypeText(msg.getShipType()));
        putIfExists(value, "dimension_to_bow", msg.getDimBow());
        putIfExists(value, "dimension_to_stern", msg.getDimStern());
        putIfExists(value, "dimension_to_port", msg.getDimPort());
        putIfExists(value, "dimension_to_starboard", msg.getDimStarboard());
        putIfExists(value, "draught", msg.getDraught() / 10.0);
        putIfExists(value, "destination", trimAis(msg.getDest()));

        java.util.Date etaDate = msg.getEtaDate();
        if (etaDate != null) {
            putIfExists(value, "eta", etaDate.toInstant().toString());
        }
    }

    private void populateSarAircraft(Struct value, AisMessage9 msg) {
        Position pos = msg.getPos().getGeoLocation();
        putValidLatLon(value, pos);
        putValidSog(value, msg.getSog());
        putValidCog(value, msg.getCog());
        putIfExists(value, "altitude", msg.getAltitude());
    }

    private void populateSafetyAddressed(Struct value, AisMessage12 msg) {
        putIfExists(value, "safety_text", msg.getMessage());
        putIfExists(value, "dest_mmsi", msg.getDestination());
    }

    private void populateSafetyBroadcast(Struct value, AisMessage14 msg) {
        putIfExists(value, "safety_text", msg.getMessage());
    }

    private void populateClassBPosition(Struct value, AisMessage18 msg) {
        Position pos = msg.getPos().getGeoLocation();
        putValidLatLon(value, pos);
        putValidSog(value, msg.getSog());
        putValidCog(value, msg.getCog());
        putValidHeading(value, msg.getTrueHeading());
    }

    private void populateClassBExtended(Struct value, AisMessage19 msg) {
        Position pos = msg.getPos().getGeoLocation();
        putValidLatLon(value, pos);
        putValidSog(value, msg.getSog());
        putValidCog(value, msg.getCog());
        putValidHeading(value, msg.getTrueHeading());
        putIfExists(value, "ship_name", trimAis(msg.getName()));
        putIfExists(value, "ship_type", msg.getShipType());
        putIfExists(value, "ship_type_text", shipTypeText(msg.getShipType()));
        putIfExists(value, "dimension_to_bow", msg.getDimBow());
        putIfExists(value, "dimension_to_stern", msg.getDimStern());
        putIfExists(value, "dimension_to_port", msg.getDimPort());
        putIfExists(value, "dimension_to_starboard", msg.getDimStarboard());
    }

    private void populateAtoN(Struct value, AisMessage21 msg) {
        putIfExists(value, "aid_type", msg.getAtonType());
        putIfExists(value, "aid_name", trimAis(msg.getName()));
        Position pos = msg.getPos().getGeoLocation();
        putValidLatLon(value, pos);
        putIfExists(value, "dimension_to_bow", msg.getDimBow());
        putIfExists(value, "dimension_to_stern", msg.getDimStern());
        putIfExists(value, "dimension_to_port", msg.getDimPort());
        putIfExists(value, "dimension_to_starboard", msg.getDimStarboard());
        putIfExists(value, "off_position", msg.getOffPosition() == 1);
        putIfExists(value, "virtual_aid", msg.getVirtual() == 1);
    }

    private void populateClassBStatic(Struct value, AisMessage24 msg) {
        putIfExists(value, "part_number", msg.getPartNumber());
        if (msg.getPartNumber() == 0) {
            putIfExists(value, "ship_name", trimAis(msg.getName()));
        } else {
            putIfExists(value, "ship_type", msg.getShipType());
            putIfExists(value, "ship_type_text", shipTypeText(msg.getShipType()));
            putIfExists(value, "callsign", trimAis(msg.getCallsign()));
            putIfExists(value, "dimension_to_bow", msg.getDimBow());
            putIfExists(value, "dimension_to_stern", msg.getDimStern());
            putIfExists(value, "dimension_to_port", msg.getDimPort());
            putIfExists(value, "dimension_to_starboard", msg.getDimStarboard());
        }
    }

    private void populateLongRange(Struct value, AisMessage27 msg) {
        Position pos = msg.getPos().getGeoLocation();
        putValidLatLon(value, pos);
        putValidSog(value, msg.getSog());
        putValidCog(value, msg.getCog());
        putIfExists(value, "nav_status", msg.getNavStatus());
        putIfExists(value, "nav_status_text", navStatusText(msg.getNavStatus()));
    }

    private void populateBinaryAddressed(Struct value, AisMessage6 msg) {
        putIfExists(value, "dac", msg.getDac());
        putIfExists(value, "fid", msg.getFi());
        putIfExists(value, "dest_mmsi", msg.getDestination());
    }

    private void populateAcknowledge(Struct value, AisMessage7 msg) {
        putIfExists(value, "dest_mmsi_1", (int) msg.getDest1());
        putIfExists(value, "dest_mmsi_2", (int) msg.getDest2());
        putIfExists(value, "dest_mmsi_3", (int) msg.getDest3());
        putIfExists(value, "dest_mmsi_4", (int) msg.getDest4());
    }

    private void populateBinaryBroadcast(Struct value, AisMessage8 msg) {
        putIfExists(value, "dac", msg.getDac());
        putIfExists(value, "fid", msg.getFi());
    }

    // --- Helpers ---

    private void putValidLatLon(Struct value, Position pos) {
        if (pos != null) {
            double lat = pos.getLatitude();
            double lon = pos.getLongitude();
            putIfExists(value, "latitude", Math.abs(lat) <= 90.0 ? lat : null);
            putIfExists(value, "longitude", Math.abs(lon) <= 180.0 ? lon : null);
        }
    }

    private void putValidSog(Struct value, int sog) {
        // 1023 = not available (types 1,2,3), but AisLib stores raw * 10
        if (sog < 1023) {
            putIfExists(value, "speed_over_ground", sog / 10.0);
        }
    }

    private void putValidCog(Struct value, int cog) {
        // 3600 = not available
        if (cog < 3600) {
            putIfExists(value, "course_over_ground", cog / 10.0);
        }
    }

    private void putValidHeading(Struct value, int heading) {
        // 511 = not available
        if (heading < 511) {
            putIfExists(value, "true_heading", heading);
        }
    }

    private void putValidRot(Struct value, int rot) {
        // 128 / -128 = not available
        if (rot != 128 && rot != -128) {
            putIfExists(value, "rate_of_turn", rot);
        }
    }

    private void putValidTimestampSecond(Struct value, int sec) {
        // 60-63 = not available / special
        if (sec < 60) {
            putIfExists(value, "timestamp_second", sec);
        }
    }

    private static void putIfExists(Struct struct, String field, Object value) {
        if (struct.schema().field(field) != null && value != null) {
            struct.put(field, value);
        }
    }

    static String navStatusText(int status) {
        if (status >= 0 && status < NAV_STATUS_TEXT.length) {
            return NAV_STATUS_TEXT[status];
        }
        return "Unknown";
    }

    static String shipTypeText(int type) {
        if (type == 0) return "Not available";
        int category = type / 10;
        switch (category) {
            case 2: return "WIG";
            case 3:
                switch (type) {
                    case 30: return "Fishing";
                    case 31: return "Towing";
                    case 32: return "Towing (large)";
                    case 33: return "Dredging/underwater ops";
                    case 34: return "Diving ops";
                    case 35: return "Military ops";
                    case 36: return "Sailing";
                    case 37: return "Pleasure craft";
                    default: return "Vessel";
                }
            case 4: return "HSC";
            case 5:
                switch (type) {
                    case 50: return "Pilot vessel";
                    case 51: return "SAR vessel";
                    case 52: return "Tug";
                    case 53: return "Port tender";
                    case 54: return "Anti-pollution";
                    case 55: return "Law enforcement";
                    case 58: return "Medical transport";
                    case 59: return "Non-combat ship";
                    default: return "Special craft";
                }
            case 6: return "Passenger";
            case 7: return "Cargo";
            case 8: return "Tanker";
            case 9: return "Other";
            default: return "Reserved";
        }
    }

    private static String trimAis(String s) {
        if (s == null) return null;
        // AIS pads strings with @ symbols
        return s.replace("@", "").trim();
    }
}
