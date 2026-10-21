package io.github.henrycarto.gateway.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeansConfig {

    /**
     * One HL7 context for parsing and for the MLLP server.
     * Messages from v2.3 to v2.5.1 are read into the v2.5 model, so the mappers
     * deal with one structure. Field validation is off: senders in the wild are
     * not strict, and the mappers check the fields they actually need.
     */
    @Bean(destroyMethod = "close")
    public HapiContext hapiContext() {
        HapiContext context = new DefaultHapiContext();
        context.setModelClassFactory(new CanonicalModelClassFactory("2.5"));
        context.setValidationContext(ValidationContextFactory.noValidation());
        return context;
    }

    /** FhirContext is expensive to build and thread-safe: one per application. */
    @Bean
    public FhirContext fhirContext(GatewayProperties properties) {
        FhirContext ctx = FhirContext.forR4();
        ctx.getRestfulClientFactory().setSocketTimeout(properties.delivery().requestTimeoutMs());
        ctx.getRestfulClientFactory().setConnectTimeout(properties.delivery().requestTimeoutMs());
        return ctx;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
