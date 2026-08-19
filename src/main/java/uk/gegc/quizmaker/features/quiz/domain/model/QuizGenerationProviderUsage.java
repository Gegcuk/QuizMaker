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
    @Column(name = "record_state", nullable = false, length = 16)
    private ProviderUsageRecordState recordState;

    @Column(name = "provider_llm_tokens")
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
        validateTokens(recordState, providerLlmTokens);
        this.providerLlmTokens = providerLlmTokens;
    }

    public static QuizGenerationProviderUsage started(
            UUID jobId,
            UUID providerAttemptId,
            LocalDateTime recordedAt
    ) {
        return new QuizGenerationProviderUsage(
                jobId,
                providerAttemptId,
                ProviderUsageRecordState.STARTED,
                null,
                recordedAt
        );
    }

    public boolean transitionTo(
            ProviderUsageRecordState terminalState,
            Long tokens
    ) {
        Objects.requireNonNull(terminalState, "terminalState must not be null");
        if (terminalState == ProviderUsageRecordState.STARTED) {
            throw new IllegalArgumentException("terminalState must be terminal");
        }
        validateTokens(terminalState, tokens);
        if (matches(terminalState, tokens)) {
            return false;
        }
        if (recordState != ProviderUsageRecordState.STARTED) {
            throw new IllegalStateException("Provider attempt already has a different terminal fact");
        }
        recordState = terminalState;
        providerLlmTokens = tokens;
        return true;
    }

    private static void validateTokens(ProviderUsageRecordState state, Long tokens) {
        if (state == ProviderUsageRecordState.REPORTED) {
            if (tokens == null || tokens < 0L) {
                throw new IllegalArgumentException("reported provider LLM tokens must not be negative");
            }
        } else if (tokens != null) {
            throw new IllegalArgumentException("non-reported provider usage must not contain a token value");
        }
    }

    public boolean matches(ProviderUsageRecordState expectedState, Long expectedTokens) {
        return recordState == expectedState && Objects.equals(providerLlmTokens, expectedTokens);
    }
}
