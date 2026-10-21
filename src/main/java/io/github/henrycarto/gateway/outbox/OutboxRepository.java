package io.github.henrycarto.gateway.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEntry, Long> {

    /** Oldest first, regardless of when they are due: the worker preserves arrival order. */
    List<OutboxEntry> findTop50ByStatusOrderByIdAsc(OutboxStatus status);

    List<OutboxEntry> findTop100ByStatusOrderByIdDesc(OutboxStatus status);

    long countByStatus(OutboxStatus status);
}
