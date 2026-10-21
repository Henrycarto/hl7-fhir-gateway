package io.github.henrycarto.gateway.api;

import static io.github.henrycarto.gateway.TestSupport.message;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.henrycarto.gateway.TestSupport;
import io.github.henrycarto.gateway.TestSupport.ScriptedFhirSender;
import io.github.henrycarto.gateway.ingest.InboundMessageRepository;
import io.github.henrycarto.gateway.outbox.DeliveryWorker;
import io.github.henrycarto.gateway.outbox.FhirDeliveryException;
import io.github.henrycarto.gateway.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSupport.Config.class)
class ApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    InboundMessageRepository inbound;
    @Autowired
    DeliveryWorker worker;
    @Autowired
    ScriptedFhirSender sender;

    @BeforeEach
    void clean() {
        outbox.deleteAll();
        inbound.deleteAll();
        sender.reset();
    }

    @Test
    void postReturnsAckWithMatchingHttpStatus() throws Exception {
        mvc.perform(post("/api/hl7").contentType(MediaType.TEXT_PLAIN).content(message("adt_a01.hl7")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-HL7-Ack-Code", "AA"))
                .andExpect(content().string(containsString("MSA|AA|MSG00001")));

        mvc.perform(post("/api/hl7").contentType(MediaType.TEXT_PLAIN).content(message("adt_a01_missing_pid3.hl7")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("X-HL7-Ack-Code", "AE"));

        mvc.perform(post("/api/hl7").contentType(MediaType.TEXT_PLAIN).content(message("adt_a40_unsupported.hl7")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-HL7-Ack-Code", "AR"));
    }

    @Test
    void deadLetterCanBeInspectedAndRequeued() throws Exception {
        mvc.perform(post("/api/hl7").contentType(MediaType.TEXT_PLAIN).content(message("adt_a01.hl7")));
        sender.then(new FhirDeliveryException(false, 422, "HTTP 422: invalid"));
        worker.runOnce();

        mvc.perform(get("/api/outbox/summary"))
                .andExpect(jsonPath("$.dead").value(1))
                .andExpect(jsonPath("$.pending").value(0));

        long id = outbox.findAll().get(0).getId();
        mvc.perform(get("/api/outbox/dead"))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].lastError").value(containsString("422")));

        mvc.perform(post("/api/outbox/{id}/retry", id)).andExpect(status().isOk());
        worker.runOnce();

        mvc.perform(get("/api/outbox/summary"))
                .andExpect(jsonPath("$.sent").value(1))
                .andExpect(jsonPath("$.dead").value(0));
    }

    @Test
    void retryOfUnknownEntryIs404() throws Exception {
        mvc.perform(post("/api/outbox/{id}/retry", 999_999)).andExpect(status().isNotFound());
    }
}
