package io.github.henrycarto.gateway.mllp;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.protocol.ReceivingApplicationException;
import io.github.henrycarto.gateway.ingest.IngestResult;
import io.github.henrycarto.gateway.ingest.IngestService;
import java.util.Map;

/** Bridges the HAPI MLLP server to the same ingest path the HTTP endpoint uses. */
public class Hl7ReceivingApplication implements ReceivingApplication<Message> {

    private final IngestService ingest;

    public Hl7ReceivingApplication(IngestService ingest) {
        this.ingest = ingest;
    }

    @Override
    public Message processMessage(Message message, Map<String, Object> metadata)
            throws ReceivingApplicationException, HL7Exception {
        IngestResult result = ingest.ingest(message, message.encode());
        if (result.ack() == null) {
            throw new ReceivingApplicationException("No ACK produced: " + result.detail());
        }
        return result.ack();
    }

    @Override
    public boolean canProcess(Message message) {
        return true;
    }
}
