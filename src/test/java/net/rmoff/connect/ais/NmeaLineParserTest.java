package net.rmoff.connect.ais;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NmeaLineParserTest {

    // Real captured data from Norwegian Coastal Administration AIS feed
    private static final String TYPE1_WITH_TAG = "\\s:2573305,c:1774373593*04\\!BSVDM,1,1,,B,13mD7l0Oh10L3`DSh`>1AWWR0l1c,0*48";
    private static final String TYPE1_BARE = "!BSVDM,1,1,,B,13mD7l0Oh10L3`DSh`>1AWWR0l1c,0*48";
    private static final String TYPE5_SENT1 = "\\s:2573104,c:1774373593*07\\!BSVDM,2,1,3,B,53o0BP`2GB50hLhr220u<htH`u8B0V222222220t1hE636Uj05SSklk88888,0*0A";
    private static final String TYPE5_SENT2 = "\\s:2573104,c:1774373593*07\\!BSVDM,2,2,3,B,88888888880,2*3D";

    private NmeaLineParser parser;

    @BeforeEach
    void setUp() {
        parser = new NmeaLineParser(30000);
    }

    @Test
    void parsesSingleSentenceWithTagBlock() {
        Optional<NmeaLineParser.ParseResult> result = parser.parseLine(TYPE1_WITH_TAG);
        assertTrue(result.isPresent(), "Should parse a single-sentence message");

        NmeaLineParser.ParseResult parsed = result.get();
        assertEquals("2573305", parsed.sourceStation);
        assertEquals(1774373593000L, parsed.receiveTimestampMs);
        assertNotNull(parsed.message);
        assertEquals(1, parsed.message.getMsgId());
        assertEquals(257230800, parsed.message.getUserId());
    }

    @Test
    void parsesSingleSentenceWithoutTagBlock() {
        Optional<NmeaLineParser.ParseResult> result = parser.parseLine(TYPE1_BARE);
        assertTrue(result.isPresent());

        NmeaLineParser.ParseResult parsed = result.get();
        assertNull(parsed.sourceStation);
        assertNotNull(parsed.message);
        assertEquals(1, parsed.message.getMsgId());
    }

    @Test
    void handlesMultiSentenceMessages() {
        Optional<NmeaLineParser.ParseResult> result1 = parser.parseLine(TYPE5_SENT1);
        assertFalse(result1.isPresent(), "First fragment should not produce a result");
        assertEquals(1, parser.getFragmentCount());

        Optional<NmeaLineParser.ParseResult> result2 = parser.parseLine(TYPE5_SENT2);
        assertTrue(result2.isPresent(), "Second fragment should complete the message");
        assertEquals(0, parser.getFragmentCount());

        NmeaLineParser.ParseResult parsed = result2.get();
        assertEquals(5, parsed.message.getMsgId());
        assertTrue(parsed.rawNmea.contains("\n"), "Raw NMEA should contain both sentences");
    }

    @Test
    void handlesNullAndEmptyLines() {
        assertFalse(parser.parseLine(null).isPresent());
        assertFalse(parser.parseLine("").isPresent());
    }

    @Test
    void handlesMalformedSentences() {
        assertFalse(parser.parseLine("garbage data").isPresent());
        assertFalse(parser.parseLine("!AIVDM,bad").isPresent());
    }

    @Test
    void cleansStaleFragments() throws InterruptedException {
        NmeaLineParser shortTimeoutParser = new NmeaLineParser(50);

        // Use just the NMEA part (without tag block) for simplicity
        String frag1 = "!BSVDM,2,1,3,B,53o0BP`2GB50hLhr220u<htH`u8B0V222222220t1hE636Uj05SSklk88888,0*0A";
        shortTimeoutParser.parseLine(frag1);
        assertEquals(1, shortTimeoutParser.getFragmentCount());

        Thread.sleep(100);
        shortTimeoutParser.cleanStaleFragments();
        assertEquals(0, shortTimeoutParser.getFragmentCount());
    }

    @Test
    void normalizeTalkerIdIsNoOp() {
        // AisLib handles BSVDM natively, normalization would break checksums
        String original = "!BSVDM,1,1,,B,test,0*00";
        assertEquals("!AIVDM,1,1,,B,test,0*00", NmeaLineParser.normalizeTalkerId(original));
    }
}
