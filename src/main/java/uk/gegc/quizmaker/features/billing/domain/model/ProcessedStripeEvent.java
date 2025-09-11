package uk.gegc.quizmaker.features.billing.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_stripe_events")
@Getter
@Setter
public class ProcessedStripeEvent {

    @Id
    @Column(name = "event_id", length = 255, nullable = false, updatable = false)
    private String eventId;

    @Column(name = "created_at", updatable = false, insertable = false)
    private LocalDateTime createdAt;
}

