package io.github.henrycarto.gateway.mapping;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * Builds a FHIR transaction bundle where every write is keyed by a business identifier,
 * never by a server id. Replaying the same HL7 message produces the same bundle and
 * the same end state on the server.
 */
public class TransactionBuilder {

    private final Bundle bundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);

    /** Create or replace: the resource on the server becomes exactly this one. */
    public Reference upsert(Resource resource, String system, String value) {
        String type = resource.fhirType();
        String fullUrl = fullUrl(type, system, value);
        bundle.addEntry()
                .setFullUrl(fullUrl)
                .setResource(resource)
                .getRequest()
                .setMethod(Bundle.HTTPVerb.PUT)
                .setUrl(type + "?identifier=" + token(system, value));
        return new Reference(fullUrl);
    }

    /**
     * Create only if missing; an existing resource is left untouched.
     * Used for the Patient in lab results, so a thin PID in an ORU never
     * overwrites the full demographics that came in through ADT.
     */
    public Reference ensureExists(Resource resource, String system, String value) {
        String type = resource.fhirType();
        String fullUrl = fullUrl(type, system, value);
        bundle.addEntry()
                .setFullUrl(fullUrl)
                .setResource(resource)
                .getRequest()
                .setMethod(Bundle.HTTPVerb.POST)
                .setUrl(type)
                .setIfNoneExist("identifier=" + token(system, value));
        return new Reference(fullUrl);
    }

    public Bundle build() {
        return bundle;
    }

    static String fullUrl(String type, String system, String value) {
        String key = type + "|" + system + "|" + value;
        return "urn:uuid:" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String token(String system, String value) {
        return URLEncoder.encode(system + "|" + value, StandardCharsets.UTF_8);
    }
}
