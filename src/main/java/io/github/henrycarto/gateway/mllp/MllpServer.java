package io.github.henrycarto.gateway.mllp;

import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.HL7Service;
import io.github.henrycarto.gateway.config.GatewayProperties;
import io.github.henrycarto.gateway.ingest.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** MLLP listener (the TCP framing hospital interface engines use), started and stopped with the Spring context. */
@Component
@ConditionalOnProperty(prefix = "gateway.mllp", name = "enabled", havingValue = "true")
public class MllpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MllpServer.class);

    private final HapiContext context;
    private final IngestService ingest;
    private final int port;
    private HL7Service server;

    public MllpServer(HapiContext context, IngestService ingest, GatewayProperties properties) {
        this.context = context;
        this.ingest = ingest;
        this.port = properties.mllp().port();
    }

    @Override
    public synchronized void start() {
        server = context.newServer(port, false);
        server.registerApplication("*", "*", new Hl7ReceivingApplication(ingest));
        try {
            server.startAndWait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting MLLP server", e);
        }
        log.info("MLLP listener on port {}", port);
    }

    @Override
    public synchronized void stop() {
        if (server != null) {
            server.stopAndWait();
            server = null;
            log.info("MLLP listener stopped");
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public int getPort() {
        return port;
    }
}
