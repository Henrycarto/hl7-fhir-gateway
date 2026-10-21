package io.github.henrycarto.gateway.mapping;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import io.github.henrycarto.gateway.hl7.MessageHeader;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.stereotype.Component;

/** Picks the mapper for a message type. Adding a message type means adding one bean. */
@Component
public class MessageRouter {

    private final List<MessageMapper> mappers;

    public MessageRouter(List<MessageMapper> mappers) {
        this.mappers = mappers;
    }

    public Bundle toBundle(Message message, MessageHeader header) throws HL7Exception {
        for (MessageMapper mapper : mappers) {
            if (mapper.supports(header.messageCode(), header.triggerEvent())) {
                return mapper.map(message, header.triggerEvent());
            }
        }
        throw new UnsupportedMessageException("Unsupported message type " + header.type());
    }
}
