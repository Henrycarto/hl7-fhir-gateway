package io.github.henrycarto.gateway.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundMessageRepository extends JpaRepository<InboundMessage, Long> {

    boolean existsBySendingApplicationAndSendingFacilityAndControlId(
            String sendingApplication, String sendingFacility, String controlId);
}
