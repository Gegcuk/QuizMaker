package uk.gegc.quizmaker.features.quiz.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor
@Table(
        name = "quiz_generation_operations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_qgo_user_type_idempotency_key",
                        columnNames = {"user_id", "operation_type", "idempotency_key"}
                ),
                @UniqueConstraint(name = "uq_qgo_job_id", columnNames = "job_id"),
                @UniqueConstraint(name = "uq_qgo_reservation_id", columnNames = "reservation_id")
        },
        indexes = {
                @Index(name = "idx_qgo_expires_at", columnList = "expires_at"),
                @Index(name = "idx_qgo_user_created_at", columnList = "user_id, created_at")
        }
)
public class QuizGenerationOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, updatable = false, length = 24)
    private GenerationOperationType operationType;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64, columnDefinition = "CHAR(64)")
    private String requestHash;

    @Column(name = "canonicalization_version", nullable = false, updatable = false, length = 16)
    private String canonicalizationVersion;

    @Column(name = "legacy_key", nullable = false)
    private boolean legacyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private GenerationOperationState state;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "estimated_time_seconds")
    private Integer estimatedTimeSeconds;

    @Column(name = "source_processing_started_at")
    private LocalDateTime sourceProcessingStartedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public QuizGenerationOperation(
            UUID userId,
            GenerationOperationType operationType,
            String idempotencyKey,
            String requestHash,
            String canonicalizationVersion,
            boolean legacyKey,
            GenerationOperationState state,
            LocalDateTime now,
            LocalDateTime expiresAt
    ) {
        this.userId = userId;
        this.operationType = operationType;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.canonicalizationVersion = canonicalizationVersion;
        this.legacyKey = legacyKey;
        this.state = state;
        this.createdAt = now;
        this.updatedAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean hasStartedJob() {
        return jobId != null;
    }

    public boolean hasSourceDocument() {
        return sourceDocumentId != null;
    }

    public void beginSourceProcessing(LocalDateTime now) {
        state = GenerationOperationState.SOURCE_PROCESSING;
        sourceProcessingStartedAt = now;
        updatedAt = now;
    }

    public void makeSourceRetryable(LocalDateTime now) {
        if (!hasStartedJob() && !hasSourceDocument()) {
            state = GenerationOperationState.SOURCE_PENDING;
            sourceProcessingStartedAt = null;
            updatedAt = now;
        }
    }

    public void attachSourceDocument(UUID documentId, LocalDateTime now) {
        sourceDocumentId = documentId;
        state = GenerationOperationState.READY_TO_START;
        sourceProcessingStartedAt = null;
        updatedAt = now;
    }

    public void linkStartedGeneration(UUID linkedJobId, UUID linkedReservationId, int linkedEstimatedTimeSeconds, LocalDateTime now) {
        if (jobId != null && !jobId.equals(linkedJobId)) {
            throw new IllegalStateException("A generation operation cannot be linked to more than one job");
        }
        if (reservationId != null && !reservationId.equals(linkedReservationId)) {
            throw new IllegalStateException("A generation operation cannot be linked to more than one reservation");
        }
        jobId = linkedJobId;
        reservationId = linkedReservationId;
        estimatedTimeSeconds = linkedEstimatedTimeSeconds;
        state = GenerationOperationState.STARTED;
        updatedAt = now;
    }
}
