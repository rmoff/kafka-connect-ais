package net.rmoff.connect.ais;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization test over ~hundreds of real captured AIS sentences
 * (src/test/resources/ais-sample.txt). Exercises the full parse → convert path
 * across whatever message types the live feed contained.
 *
 * Why this matters: AisRecordConverter.populateTypeFields() casts the decoded
 * message to a type-specific class (e.g. (AisMessage4) for types 4 AND 11) and
 * swallows any failure at debug level. A wrong cast or decode regression would
 * therefore silently strip all type-specific fields with no error. This test
 * runs real data and asserts the decode path actually produces populated fields,
 * so such a silent regression fails the build.
 */
class AisConverterRealDataTest {

    @Test
    void decodesRealCapturedSentencesWithoutSilentFieldLoss() throws Exception {
        NmeaLineParser parser = new NmeaLineParser(30000);
        AisRecordConverter converter = new AisRecordConverter("ais", false, false);
        Map<String, Object> partition = Collections.singletonMap("host_port", "test");

        int lines = 0, parsed = 0, withLatLon = 0;
        int incomplete = 0, unsupported = 0, decodeErrors = 0;
        Map<Integer, Integer> byType = new TreeMap<>();

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("ais-sample.txt")) {
            assertNotNull(in, "ais-sample.txt fixture must be on the test classpath");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                lines++;
                NmeaLineParser.ParseOutcome outcome = parser.parseLine(line);
                switch (outcome.kind()) {
                    case PARSED:
                        parsed++;
                        NmeaLineParser.ParseResult result = ((NmeaLineParser.Parsed) outcome).result;

                        Map<String, Object> offset = new HashMap<>();
                        offset.put("connection_epoch", 0L);
                        offset.put("message_count", (long) parsed);

                        // convert() must NEVER throw — one bad message can't kill the stream.
                        SourceRecord rec = converter.convert(result, partition, offset);
                        assertNotNull(rec, "convert produced null for: " + line);

                        Struct value = (Struct) rec.value();
                        int msgType = value.getInt32("msg_type");
                        byType.merge(msgType, 1, Integer::sum);

                        assertNotNull(value.getInt32("mmsi"), "mmsi must be set");
                        // raw_nmea is the single line, or for multi-part messages the joined
                        // fragments ending in the completing line.
                        String raw = value.getString("raw_nmea");
                        assertNotNull(raw, "raw_nmea must be set");
                        assertTrue(raw.endsWith(line), "raw_nmea must end with the completing line");

                        if (value.schema().field("latitude") != null && value.getFloat64("latitude") != null) {
                            withLatLon++;
                        }
                        break;
                    case INCOMPLETE_FRAGMENT:
                        incomplete++;
                        break;
                    case UNSUPPORTED_TYPE:
                        unsupported++;
                        break;
                    case DECODE_ERROR:
                        decodeErrors++;
                        break;
                    default:
                        break;
                }
            }
        }

        System.out.println("Real-data decode: lines=" + lines + " parsed=" + parsed
                + " withLatLon=" + withLatLon + " byType=" + byType);
        System.out.printf("Categorized: parsed=%d incomplete=%d unsupported=%d decodeErrors=%d%n",
                parsed, incomplete, unsupported, decodeErrors);

        assertTrue(parsed > 50, "expected to decode many sentences, got " + parsed);
        assertTrue(byType.size() >= 2, "expected multiple message types, got " + byType.keySet());
        // Position reports (types 1/2/3/18/19) decode lat/lon. The feed is dominated
        // by position reports, so a healthy decode path yields plenty. Zero here means
        // the type-specific decode silently broke.
        assertTrue(withLatLon > 0,
                "no record got a latitude — type-specific decode is silently failing");
        assertTrue(decodeErrors <= 5,
                "Real feed data should yield near-zero decode errors, got " + decodeErrors);
    }
}
