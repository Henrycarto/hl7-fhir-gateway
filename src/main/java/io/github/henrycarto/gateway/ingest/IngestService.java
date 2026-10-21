package io.github.henrycarto.gateway.ingest;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.hl7v2.AcknowledgmentCode;
import ca.uhn.hl7v2.ErrorCode;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import io.github.henrycarto.gateway.hl7.Hl7Parser;
import io.github.henrycarto.gateway.hl7.MessageHeader;
import io.github.henrycarto.gateway.mapping.MappingException;
import io.github.henrycarto.gateway.mapping.MessageRouter;
import io.github.henrycarto.gateway.mapping.UnsupportedMessageException;
import io.github.henrycarto.gateway.outbox.OutboxEntry;
import io.github.henrycarto.gateway.outbox.OutboxRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only path into the system, shared by HTTP and MLLP.
 *
 * AA means "stored durably and queued for delivery", not "written to the FHIR server".
 * The sender gets a fast answer that does not depend on the FHIR server being up;
 * delivery is the outbox worker's job.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final DateTimeFormatter HL7_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final Hl7Parser parser;
    private final MessageRouter router;
    private final InboundMessageRepository inbound;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final FhirContext fhir;
    private final Clock clock;

    public IngestService(Hl7Parser parser, MessageRouter router, InboundMessageRepository inbound,
                         OutboxRepository outbox, TransactionTemplate tx, FhirContext fhir, Clock clock) {
        this.parser = parser;
        this.router = router;
        this.inbound = inbound;
        this.outbox = outbox;
        this.tx = tx;
        this.fhir = fhir;
        this.clock = clock;
    }

    /** HTTP entry point: raw ER7 text in, ACK out. */
    public IngestResult ingest(String raw) {
        Message message;
        try {
            message = parser.parse(raw);
        } catch (HL7Exception e) {
            log.warn("Rejected unparseable message: {}", e.getMessage());
            return unparseable(raw, e.getMessage());
        }
        return ingest(message, Hl7Parser.normalise(raw));
    }

    /** MLLP entry point: HAPI has already parsed the message. */
    public IngestResult ingest(Message message, String raw) {
        MessageHeader header;
        try {
            header = MessageHeader.of(message);
        } catch (HL7Exception e) {
            return nak(message, AcknowledgmentCode.AR, ErrorCode.SEGMENT_SEQUENCE_ERROR, "MSH unreadable: " + e.getMessage());
        }
        if (header.controlId().isEmpty()) {
            return nak(message, AcknowledgmentCode.AE, ErrorCode.REQUIRED_FIELD_MISSING, "MSH-10 message control id is missing");
        }

        if (inbound.existsBySendingApplicationAndSendingFacilityAndControlId(
                header.sendingApplication(), header.sendingFacility(), header.controlId())) {
            log.info("Duplicate {} {} from {}/{}, acknowledged without reprocessing",
                    header.type(), header.controlId(), header.sendingApplication(), header.sendingFacility());
            return accepted(message, true);
        }

        Bundle bundle;
        try {
            bundle = router.toBundle(message, header);
        } catch (UnsupportedMessageException e) {
            return nak(message, AcknowledgmentCode.AR, ErrorCode.UNSUPPORTED_MESSAGE_TYPE, e.getMessage());
        } catch (MappingException | IllegalArgumentException e) {
            return nak(message, AcknowledgmentCode.AE, ErrorCode.REQUIRED_FIELD_MISSING, e.getMessage());
        } catch (HL7Exception e) {
            return nak(message, AcknowledgmentCode.AE, ErrorCode.DATA_TYPE_ERROR, e.getMessage());
        }

        String bundleJson = fhir.newJsonParser().encodeResourceToString(bundle);
        Instant now = clock.instant();
        try {
            tx.executeWithoutResult(status -> {
                inbound.save(new InboundMessage(header.sendingApplication(), header.sendingFacility(),
                        header.controlId(), header.type(), now, raw));
                outbox.save(new OutboxEntry(header.controlId(), header.type(),
                        bundle.getEntry().size(), bundleJson, now));
            });
        } catch (DataIntegrityViolationException e) {
            // Two copies of the same message raced past the exists check. The other one won.
            log.info("Concurrent duplicate {} {}, acknowledged without reprocessing", header.type(), header.controlId());
            return accepted(message, true);
        }
        log.info("Accepted {} {} from {}/{} with {} FHIR entries", header.type(), header.controlId(),
                header.sendingApplication(), header.sendingFacility(), bundle.getEntry().size());
        return accepted(message, false);
    }

    private IngestResult accepted(Message message, boolean duplicate) {
        try {
            Message ack = message.generateACK();
            return new IngestResult(AcknowledgmentCode.AA, ack, parser.encode(ack), duplicate, null);
        } catch (HL7Exception | IOException e) {
            throw new IllegalStateException("Could not build ACK", e);
        }
    }

    private IngestResult nak(Message message, AcknowledgmentCode code, ErrorCode error, String detail) {
        log.warn("{} for message: {}", code, detail);
        try {
            Message ack = message.generateACK(code, new HL7Exception(detail, error));
            return new IngestResult(code, ack, parser.encode(ack), false, detail);
        } catch (HL7Exception | IOException e) {
            throw new IllegalStateException("Could not build NAK", e);
        }
    }

    /**
     * HAPI cannot build an ACK for a message it cannot parse, so this one is written by hand.
     * It echoes MSH-10 when it can be found, so the sender can still correlate the rejection.
     */
    private IngestResult unparseable(String raw, String reason) {
        String controlId = "";
        String normalised = Hl7Parser.normalise(raw);
        if (normalised.startsWith("MSH") && normalised.length() > 4) {
            char fieldSep = normalised.charAt(3);
            String msh = normalised.split("\r", 2)[0];
            String[] fields = msh.split(java.util.regex.Pattern.quote(String.valueOf(fieldSep)), -1);
            // fields[0] = "MSH", fields[1] = encoding characters (MSH-2), so MSH-n is fields[n-1]
            if (fields.length > 9) {
                controlId = fields[9];
            }
        }
        String safeReason = reason == null ? "parse error" : reason.replaceAll("[|^~\\\\&\r\n]", " ");
        String ts = HL7_TS.format(clock.instant());
        String ack = "MSH|^~\\&|HL7FHIRGW||||" + ts + "||ACK|" + ts + "|P|2.5\r"
                + "MSA|AR|" + controlId + "|" + truncate(safeReason, 80) + "\r"
                + "ERR|||" + ErrorCode.UNSUPPORTED_MESSAGE_TYPE.getCode() + "|E||||" + truncate(safeReason, 200) + "\r";
        return new IngestResult(AcknowledgmentCode.AR, null, ack, false, reason);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
