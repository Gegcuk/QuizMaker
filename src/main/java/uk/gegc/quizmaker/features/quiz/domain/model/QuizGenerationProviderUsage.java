package uk.gegc.quizmaker.features.quiz.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "quiz_generation_provider_usage",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_qgpu_job_attempt",
                columnNames = {"job_id", "provider_attempt_id"}
        )
)
@Getter
@NoArgsConstructor
public class QuizGenerationProviderUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "provider_attempt_id", nullable = false, updatable = false)
    private UUID providerAttemptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_state", nullable = false, updatable = false, length = 16)
    private ProviderUsageRecordState recordState;

    @Column(name = "provider_llm_tokens", updatable = false)
    private Long providerLlmTokens;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    private QuizGenerationProviderUsage(
            UUID jobId,
            UUID providerAttemptId,
            ProviderUsageRecordState recordState,
            Long providerLlmTokens,
            LocalDateTime recordedAt
    ) {
        this.jobId = Objects.requireNonNull(jobId, "jobId must not be null");
        this.providerAttemptId = Objects.requireNonNull(providerAttemptId, "providerAttemptId must not be null");
        this.recordState = Objects.requireNonNull(recordState, "recordState must not be null");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        if (recordState == ProviderUsageRecordState.REPORTED) {
            if (providerLlmTokens == null || providerLlmTokens < 0L) {
                throw new IllegalArgumentException("reported provider LLM tokens must not be negative");
            }
            this.providerLlmTokens = providerLlmTokens;
        } else if (providerLlmTokens != null) {
            throw new IllegalArgumentException("missing provider usage must not contain a token value");
        }
    }

    public static QuizGenerationProviderUsage reported(
            UUID jobId,
            UUID providerAttemptId,
            long providerLlmTokens,
            LocalDateTime recordedAt
    ) {
        return new QuizGenerationProviderUsage(
                jobId,
                providerAttemptId,
                ProviderUsageRecordState.REPORTED,
                providerLlmTokens,
                recordedAt
        );
    }

    public static QuizGenerationProviderUsage missing(
            UUID jobId,
            UUID providerAttemptId,
            LocalDateTime recordedAt
    ) {
        return new QuizGenerationProviderUsage(
                jobId,
                providerAttemptId,
                ProviderUsageRecordState.MISSING,
                null,
                recordedAt
        );
    }

    public boolean matches(ProviderUsageRecordState expectedState, Long expectedTokens) {
        return recordState == expectedState && Objects.equals(providerLlmTokens, expectedTokens);
    }
}
