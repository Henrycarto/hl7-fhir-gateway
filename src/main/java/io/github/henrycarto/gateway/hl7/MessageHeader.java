package io.github.henrycarto.gateway.hl7;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v25.segment.MSH;

/** The MSH fields the gateway routes and deduplicates on. */
public record MessageHeader(
        String sendingApplication,
        String sendingFacility,
        String controlId,
        String messageCode,
        String triggerEvent) {

    public static MessageHeader of(Message message) throws HL7Exception {
        MSH msh = (MSH) message.get("MSH");
        return new MessageHeader(
                nz(msh.getSendingApplication().getNamespaceID().getValue()),
                nz(msh.getSendingFacility().getNamespaceID().getValue()),
                nz(msh.getMessageControlID().getValue()),
                nz(msh.getMessageType().getMessageCode().getValue()),
                nz(msh.getMessageType().getTriggerEvent().getValue()));
    }

    public String type() {
        return messageCode + "^" + triggerEvent;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
