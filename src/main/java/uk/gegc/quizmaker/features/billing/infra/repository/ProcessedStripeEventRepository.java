package uk.gegc.quizmaker.features.billing.infra.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.features.billing.domain.model.ProcessedStripeEvent;

public interface ProcessedStripeEventRepository extends JpaRepository<ProcessedStripeEvent, String> {
    boolean existsByEventId(String eventId);
}

