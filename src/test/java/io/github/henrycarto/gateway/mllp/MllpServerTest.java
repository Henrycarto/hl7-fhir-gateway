package io.github.henrycarto.gateway.mllp;

import static io.github.henrycarto.gateway.TestSupport.message;
import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.Connection;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;
import io.github.henrycarto.gateway.TestSupport;
import io.github.henrycarto.gateway.hl7.Hl7Parser;
import io.github.henrycarto.gateway.outbox.OutboxRepository;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** End to end over a real TCP socket with MLLP framing, using HAPI's own client. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestSupport.Config.class)
class MllpServerTest {

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void mllp(DynamicPropertyRegistry registry) {
        registry.add("gateway.mllp.enabled", () -> "true");
        registry.add("gateway.mllp.port", () -> PORT);
    }

    @Autowired
    HapiContext context;
    @Autowired
    Hl7Parser parser;
    @Autowired
    OutboxRepository outbox;

    @Test
    void mllpClientGetsAnAckAndMessageIsQueued() throws Exception {
        long before = outbox.count();
        Message request = parser.parse(message("oru_r01.hl7").replace("MSG00003", "MLLP-0001"));

        Connection connection = context.newClient("localhost", PORT, false);
        try {
            Message response = connection.getInitiator().sendAndReceive(request);
            Terser terser = new Terser(response);
            assertThat(terser.get("/MSA-1")).isEqualTo("AA");
            assertThat(terser.get("/MSA-2")).isEqualTo("MLLP-0001");
        } finally {
            connection.close();
        }
        assertThat(outbox.count()).isEqualTo(before + 1);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
