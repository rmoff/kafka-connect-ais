package net.rmoff.connect.ais;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        NmeaLineParser.ParseOutcome outcome = parser.parseLine(TYPE1_WITH_TAG);
        assertEquals(NmeaLineParser.ParseOutcome.Kind.PARSED, outcome.kind());
        NmeaLineParser.ParseResult parsed = ((NmeaLineParser.Parsed) outcome).result;
        assertEquals("2573305", parsed.sourceStation);
        assertEquals(1774373593000L, parsed.receiveTimestampMs);
        assertEquals(1, parsed.message.getMsgId());
        assertEquals(257230800, parsed.message.getUserId());
    }

    @Test
    void parsesSingleSentenceWithoutTagBlock() {
        NmeaLineParser.ParseOutcome outcome = parser.parseLine(TYPE1_BARE);
        assertEquals(NmeaLineParser.ParseOutcome.Kind.PARSED, outcome.kind());
        NmeaLineParser.ParseResult parsed = ((NmeaLineParser.Parsed) outcome).result;
        assertNull(parsed.sourceStation);
        assertEquals(1, parsed.message.getMsgId());
    }

    @Test
    void handlesMultiSentenceMessages() {
        NmeaLineParser.ParseOutcome r1 = parser.parseLine(TYPE5_SENT1);
        assertEquals(NmeaLineParser.ParseOutcome.Kind.INCOMPLETE_FRAGMENT, r1.kind());
        assertEquals(1, parser.getFragmentCount());

        NmeaLineParser.ParseOutcome r2 = parser.parseLine(TYPE5_SENT2);
        assertEquals(NmeaLineParser.ParseOutcome.Kind.PARSED, r2.kind());
        assertEquals(0, parser.getFragmentCount());
        assertEquals(5, ((NmeaLineParser.Parsed) r2).result.message.getMsgId());
    }

    @Test
    void nullAndEmptyAreIncompleteFragment() {
        assertEquals(NmeaLineParser.ParseOutcome.Kind.INCOMPLETE_FRAGMENT, parser.parseLine(null).kind());
        assertEquals(NmeaLineParser.ParseOutcome.Kind.INCOMPLETE_FRAGMENT, parser.parseLine("").kind());
    }

    @Test
    void malformedLinesAreDecodeError() {
        assertEquals(NmeaLineParser.ParseOutcome.Kind.DECODE_ERROR, parser.parseLine("garbage data").kind());
        assertEquals(NmeaLineParser.ParseOutcome.Kind.DECODE_ERROR, parser.parseLine("!AIVDM,bad").kind());
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
}
