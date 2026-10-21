package io.github.henrycarto.gateway.hl7;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around the HAPI pipe parser that cleans up what real senders send:
 * LF or CRLF segment separators, stray MLLP framing bytes, trailing whitespace.
 */
@Component
public class Hl7Parser {

    private static final char START_BLOCK = 0x0b;
    private static final char END_BLOCK = 0x1c;

    private final PipeParser parser;

    public Hl7Parser(HapiContext context) {
        this.parser = context.getPipeParser();
    }

    public Message parse(String raw) throws HL7Exception {
        return parser.parse(normalise(raw));
    }

    public String encode(Message message) throws HL7Exception {
        return parser.encode(message);
    }

    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace(String.valueOf(START_BLOCK), "")
                .replace(String.valueOf(END_BLOCK), "")
                .replace("\r\n", "\r")
                .replace('\n', '\r');
        // Drop empty segments (blank lines) and surrounding whitespace.
        StringBuilder out = new StringBuilder();
        for (String segment : s.split("\r")) {
            String trimmed = segment.strip();
            if (!trimmed.isEmpty()) {
                out.append(trimmed).append('\r');
            }
        }
        return out.toString();
    }
}
