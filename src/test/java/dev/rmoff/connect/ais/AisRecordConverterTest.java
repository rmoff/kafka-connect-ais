package dev.rmoff.connect.ais;

import dk.dma.ais.message.AisMessage;
import dk.dma.ais.sentence.Vdm;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AisRecordConverterTest {

    // Real captured data
    private static final String TYPE1_NMEA = "!BSVDM,1,1,,B,13mD7l0Oh10L3`DSh`>1AWWR0l1c,0*48";

    private final Map<String, Object> partition = Collections.singletonMap("host_port", "test:5631");
    private final Map<String, Object> offset = new HashMap<String, Object>() {{
        put("connection_epoch", 1000L);
        put("message_count", 1L);
    }};

    @Test
    void convertsPositionReport() throws Exception {
        AisMessage msg = parseNmea(TYPE1_NMEA);
        NmeaLineParser.ParseResult parsed = new NmeaLineParser.ParseResult(
                msg, "station1", 1774373593000L, "raw line");

        AisRecordConverter converter = new AisRecordConverter("ais", false, true);
        SourceRecord record = converter.convert(parsed, partition, offset);

        assertNotNull(record);
        assertEquals("ais", record.topic());
        assertEquals(msg.getUserId(), record.key());

        Struct value = (Struct) record.value();
        assertEquals(msg.getUserId(), value.getInt32("mmsi").intValue());
        assertEquals(1, value.getInt32("msg_type").intValue());
        assertEquals("raw line", value.getString("raw_nmea"));
        assertEquals("station1", value.getString("source_station"));
        assertNotNull(value.getFloat64("latitude"));
        assertNotNull(value.getFloat64("longitude"));
    }

    @Test
    void routesToCorrectTopicWhenPerType() throws Exception {
        AisMessage msg = parseNmea(TYPE1_NMEA);
        NmeaLineParser.ParseResult parsed = new NmeaLineParser.ParseResult(
                msg, null, System.currentTimeMillis(), "raw");

        AisRecordConverter converter = new AisRecordConverter("ais", true, true);
        SourceRecord record = converter.convert(parsed, partition, offset);
        assertEquals("ais.position", record.topic());
    }

    @Test
    void setsHeaders() throws Exception {
        AisMessage msg = parseNmea(TYPE1_NMEA);
        NmeaLineParser.ParseResult parsed = new NmeaLineParser.ParseResult(
                msg, "station42", System.currentTimeMillis(), "raw");

        AisRecordConverter converter = new AisRecordConverter("ais", false, true);
        SourceRecord record = converter.convert(parsed, partition, offset);

        assertNotNull(record.headers());
        assertEquals("1", record.headers().lastWithName("ais.msg_type").value());
        assertEquals("station42", record.headers().lastWithName("ais.source_station").value());
    }

    @Test
    void navStatusTextMapping() {
        assertEquals("Under way using engine", AisRecordConverter.navStatusText(0));
        assertEquals("At anchor", AisRecordConverter.navStatusText(1));
        assertEquals("Moored", AisRecordConverter.navStatusText(5));
        assertEquals("Undefined", AisRecordConverter.navStatusText(15));
        assertEquals("Unknown", AisRecordConverter.navStatusText(99));
    }

    @Test
    void shipTypeTextMapping() {
        assertEquals("Not available", AisRecordConverter.shipTypeText(0));
        assertEquals("Fishing", AisRecordConverter.shipTypeText(30));
        assertEquals("Cargo", AisRecordConverter.shipTypeText(70));
        assertEquals("Tanker", AisRecordConverter.shipTypeText(80));
        assertEquals("Tug", AisRecordConverter.shipTypeText(52));
        assertEquals("Passenger", AisRecordConverter.shipTypeText(60));
    }

    private AisMessage parseNmea(String nmea) throws Exception {
        Vdm vdm = new Vdm();
        vdm.parse(nmea);
        return AisMessage.getInstance(vdm);
    }
}
