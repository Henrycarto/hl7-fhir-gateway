package io.github.henrycarto.gateway.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import java.time.Instant;
import java.time.ZoneId;
import org.hl7.fhir.r4.model.DateTimeType;
import org.junit.jupiter.api.Test;

class Hl7DatesTest {

    private static final ZoneId VIENNA = ZoneId.of("Europe/Vienna");

    @Test
    void explicitOffsetWins() {
        DateTimeType dt = Hl7Dates.toDateTime("20261020143000+0200", VIENNA);
        assertThat(dt.getValue().toInstant()).isEqualTo(Instant.parse("2026-10-20T12:30:00Z"));
        assertThat(dt.getPrecision()).isEqualTo(TemporalPrecisionEnum.SECOND);
    }

    @Test
    void noOffsetUsesDefaultZoneIncludingDaylightSaving() {
        // 20 Oct is CEST (+02:00), 20 Dec is CET (+01:00)
        assertThat(Hl7Dates.toDateTime("202610201430", VIENNA).getValue().toInstant())
                .isEqualTo(Instant.parse("2026-10-20T12:30:00Z"));
        assertThat(Hl7Dates.toDateTime("202612201430", VIENNA).getValue().toInstant())
                .isEqualTo(Instant.parse("2026-12-20T13:30:00Z"));
    }

    @Test
    void dateOnlyKeepsDayPrecision() {
        assertThat(Hl7Dates.toDateTime("20261020", VIENNA).getValueAsString()).isEqualTo("2026-10-20");
        assertThat(Hl7Dates.toDate("19580314").getValueAsString()).isEqualTo("1958-03-14");
        assertThat(Hl7Dates.toDate("195803").getValueAsString()).isEqualTo("1958-03");
    }

    @Test
    void fractionalSecondsGiveMilliPrecision() {
        DateTimeType dt = Hl7Dates.toDateTime("20261020143000.25+0000", VIENNA);
        assertThat(dt.getValue().toInstant()).isEqualTo(Instant.parse("2026-10-20T14:30:00.250Z"));
        assertThat(dt.getPrecision()).isEqualTo(TemporalPrecisionEnum.MILLI);
    }

    @Test
    void blankIsNullAndGarbageIsRejected() {
        assertThat(Hl7Dates.toDateTime("", VIENNA)).isNull();
        assertThat(Hl7Dates.toDate(null)).isNull();
        assertThatThrownBy(() -> Hl7Dates.toDateTime("2026-10-20", VIENNA))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
