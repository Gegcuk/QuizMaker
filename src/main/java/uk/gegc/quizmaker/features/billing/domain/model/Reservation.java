package uk.gegc.quizmaker.features.billing.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "reservations")
@Getter
@Setter
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private ReservationState state;

    @Column(name = "estimated_tokens", nullable = false)
    private long estimatedTokens;

    @Column(name = "committed_tokens", nullable = false)
    private long committedTokens;

    @Column(name = "meta_json", columnDefinition = "JSON")
    private String metaJson;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "job_id")
    private UUID jobId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", updatable = false, insertable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}

