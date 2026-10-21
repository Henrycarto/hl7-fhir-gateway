package io.github.henrycarto.gateway.api;

import io.github.henrycarto.gateway.ingest.IngestResult;
import io.github.henrycarto.gateway.ingest.IngestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP intake for systems that cannot speak MLLP, and for testing with curl.
 * The response body is always an HL7 ACK; the HTTP status mirrors MSA-1.
 */
@RestController
@RequestMapping("/api/hl7")
public class Hl7Controller {

    private final IngestService ingest;

    public Hl7Controller(IngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping(consumes = {MediaType.TEXT_PLAIN_VALUE, "application/hl7-v2", "x-application/hl7-v2+er7"},
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receive(@RequestBody String message) {
        IngestResult result = ingest.ingest(message);
        HttpStatus status = switch (result.code()) {
            case AA, CA -> HttpStatus.OK;
            case AE, CE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case AR, CR -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .header("X-HL7-Ack-Code", result.code().name())
                .header("X-HL7-Duplicate", String.valueOf(result.duplicate()))
                .body(result.ackText().replace('\r', '\n'));
    }
}
