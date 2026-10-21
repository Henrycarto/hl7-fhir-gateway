package io.github.henrycarto.gateway.hl7;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;

/**
 * Converts HL7 v2 TS/DTM values (YYYY[MM[DD[HH[MM[SS[.S[S[S[S]]]]]]]]][+/-ZZZZ])
 * into FHIR date types, keeping the precision the sender actually gave.
 * A timestamp without an offset is read in the configured default zone,
 * because that is what the sending system meant.
 */
public final class Hl7Dates {

    private static final Pattern TS = Pattern.compile(
            "^(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(?:\\.(\\d{1,4}))?([+-]\\d{4})?$");

    private Hl7Dates() {
    }

    public static DateType toDate(String ts) {
        Matcher m = match(ts);
        if (m == null) {
            return null;
        }
        StringBuilder s = new StringBuilder(m.group(1));
        if (m.group(2) != null) {
            s.append('-').append(m.group(2));
            if (m.group(3) != null) {
                s.append('-').append(m.group(3));
            }
        }
        return new DateType(s.toString());
    }

    public static DateTimeType toDateTime(String ts, ZoneId defaultZone) {
        Matcher m = match(ts);
        if (m == null) {
            return null;
        }
        if (m.group(4) == null) {
            // Date-only precision: FHIR dateTime allows YYYY, YYYY-MM, YYYY-MM-DD without a zone.
            return new DateTimeType(toDate(ts).getValueAsString());
        }
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        int hour = Integer.parseInt(m.group(4));
        int minute = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
        int second = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
        int millis = m.group(7) == null ? 0 : Integer.parseInt((m.group(7) + "000").substring(0, 3));

        LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000);
        ZoneId zone = m.group(8) == null ? defaultZone : ZoneOffset.of(m.group(8));
        ZonedDateTime zoned = local.atZone(zone);

        // FHIR dateTime has no minute precision: with a time, seconds are required (zero-filled if not sent).
        TemporalPrecisionEnum precision = m.group(7) != null ? TemporalPrecisionEnum.MILLI
                : TemporalPrecisionEnum.SECOND;
        return new DateTimeType(Date.from(zoned.toInstant()), precision, TimeZone.getTimeZone(zoned.getZone()));
    }

    private static Matcher match(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        Matcher m = TS.matcher(ts.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Not an HL7 timestamp: " + ts);
        }
        if (m.group(4) != null && m.group(3) == null) {
            throw new IllegalArgumentException("HL7 timestamp has a time but no full date: " + ts);
        }
        return m;
    }
}
