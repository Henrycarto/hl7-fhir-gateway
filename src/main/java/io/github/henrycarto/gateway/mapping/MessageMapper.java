package io.github.henrycarto.gateway.mapping;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import org.hl7.fhir.r4.model.Bundle;

public interface MessageMapper {

    boolean supports(String messageCode, String triggerEvent);

    Bundle map(Message message, String triggerEvent) throws HL7Exception;
}
