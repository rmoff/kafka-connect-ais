package dev.rmoff.connect.ais;

import dk.dma.ais.message.AisMessage;
import dk.dma.ais.sentence.SentenceException;
import dk.dma.ais.sentence.Vdm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NmeaLineParser {

    private static final Logger log = LoggerFactory.getLogger(NmeaLineParser.class);

    // Tag block: \s:<station>,c:<timestamp>*<checksum>\  (fields can be in any order)
    private static final Pattern TAG_BLOCK_PATTERN =
            Pattern.compile("^\\\\(.+?)\\\\(.+)$");

    // Talker IDs to normalize: !BSVDM, !B2VDM, !BSVDO, etc. → !AIVDM / !AIVDO
    private static final Pattern TALKER_PATTERN =
            Pattern.compile("!B[S2]VD([MO])");

    private final long fragmentTimeoutMs;
    private final Map<String, FragmentEntry> fragments = new HashMap<>();

    public NmeaLineParser(long fragmentTimeoutMs) {
        this.fragmentTimeoutMs = fragmentTimeoutMs;
    }

    public static class ParseResult {
        public final AisMessage message;
        public final String sourceStation;
        public final long receiveTimestampMs;
        public final String rawNmea;

        public ParseResult(AisMessage message, String sourceStation, long receiveTimestampMs, String rawNmea) {
            this.message = message;
            this.sourceStation = sourceStation;
            this.receiveTimestampMs = receiveTimestampMs;
            this.rawNmea = rawNmea;
        }
    }

    private static class FragmentEntry {
        final Vdm vdm;
        final String firstLineRaw;
        final String sourceStation;
        final long receiveTimestampMs;
        final long createdAt;

        FragmentEntry(Vdm vdm, String firstLineRaw, String sourceStation, long receiveTimestampMs) {
            this.vdm = vdm;
            this.firstLineRaw = firstLineRaw;
            this.sourceStation = sourceStation;
            this.receiveTimestampMs = receiveTimestampMs;
            this.createdAt = System.currentTimeMillis();
        }
    }

    /**
     * Parse a raw line from the AIS TCP stream.
     *
     * @param line raw line including optional tag block
     * @return parsed result if a complete message was decoded, empty for fragments or errors
     */
    public Optional<ParseResult> parseLine(String line) {
        if (line == null || line.isEmpty()) {
            return Optional.empty();
        }

        String sourceStation = null;
        long receiveTimestampMs = System.currentTimeMillis();
        String nmeaSentence;

        // Extract tag block if present
        Matcher tagMatcher = TAG_BLOCK_PATTERN.matcher(line);
        if (tagMatcher.matches()) {
            String tagContent = tagMatcher.group(1);
            nmeaSentence = tagMatcher.group(2);
            // Parse tag block key-value pairs
            for (String field : tagContent.split(",")) {
                if (field.startsWith("s:")) {
                    sourceStation = field.substring(2);
                } else if (field.startsWith("c:")) {
                    // Remove checksum suffix if present (e.g., "1774369976*08")
                    String tsStr = field.substring(2);
                    int starIdx = tsStr.indexOf('*');
                    if (starIdx >= 0) {
                        tsStr = tsStr.substring(0, starIdx);
                    }
                    try {
                        receiveTimestampMs = Long.parseLong(tsStr) * 1000L;
                    } catch (NumberFormatException e) {
                        log.warn("Invalid tag block timestamp: {}", tsStr);
                    }
                }
            }
        } else {
            nmeaSentence = line;
        }

        // Extract multi-sentence fields from the raw NMEA before full parse
        String[] fields = nmeaSentence.split(",", 7);
        if (fields.length < 6) {
            log.debug("Malformed NMEA sentence (too few fields): {}", nmeaSentence);
            return Optional.empty();
        }

        int numSentences;
        int sentenceNum;
        try {
            numSentences = Integer.parseInt(fields[1]);
            sentenceNum = Integer.parseInt(fields[2]);
        } catch (NumberFormatException e) {
            log.debug("Invalid sentence numbering: {}", nmeaSentence);
            return Optional.empty();
        }

        try {
            if (numSentences == 1) {
                return parseSingleSentence(nmeaSentence, sourceStation, receiveTimestampMs, line);
            } else {
                return parseMultiSentence(nmeaSentence, sentenceNum, fields, sourceStation, receiveTimestampMs, line);
            }
        } catch (Exception e) {
            log.debug("Failed to parse AIS message: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ParseResult> parseSingleSentence(String nmea, String station, long timestampMs, String rawLine)
            throws Exception {
        Vdm vdm = new Vdm();
        int result = vdm.parse(nmea);
        if (result != 0) {
            log.debug("Unexpected parse result {} for single sentence: {}", result, nmea);
            return Optional.empty();
        }
        AisMessage msg = AisMessage.getInstance(vdm);
        return Optional.of(new ParseResult(msg, station, timestampMs, rawLine));
    }

    private Optional<ParseResult> parseMultiSentence(String nmea, int sentenceNum, String[] fields,
                                                      String station, long timestampMs, String rawLine)
            throws Exception {
        // Fragment key: channel + sequential message ID
        String channel = fields.length > 4 ? fields[4] : "";
        String seqId = fields[3];
        String fragKey = channel + ":" + seqId;

        if (sentenceNum == 1) {
            // First fragment
            Vdm vdm = new Vdm();
            vdm.parse(nmea);
            fragments.put(fragKey, new FragmentEntry(vdm, rawLine, station, timestampMs));
            return Optional.empty();
        } else {
            // Continuation
            FragmentEntry entry = fragments.remove(fragKey);
            if (entry == null) {
                log.debug("Received continuation fragment without first part, key={}", fragKey);
                return Optional.empty();
            }
            int result = entry.vdm.parse(nmea);
            if (result != 0) {
                // Still need more sentences — put it back
                fragments.put(fragKey, entry);
                return Optional.empty();
            }
            AisMessage msg = AisMessage.getInstance(entry.vdm);
            String fullRaw = entry.firstLineRaw + "\n" + rawLine;
            return Optional.of(new ParseResult(msg, entry.sourceStation, entry.receiveTimestampMs, fullRaw));
        }
    }

    static String normalizeTalkerId(String nmea) {
        return TALKER_PATTERN.matcher(nmea).replaceFirst("!AIVD$1");
    }

    /**
     * Remove fragment entries that have been waiting longer than the timeout.
     */
    public void cleanStaleFragments() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, FragmentEntry>> it = fragments.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, FragmentEntry> entry = it.next();
            if (now - entry.getValue().createdAt > fragmentTimeoutMs) {
                log.debug("Removing stale fragment: {}", entry.getKey());
                it.remove();
            }
        }
    }

    public int getFragmentCount() {
        return fragments.size();
    }
}
