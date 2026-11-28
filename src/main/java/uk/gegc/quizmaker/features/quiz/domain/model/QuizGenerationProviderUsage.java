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
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "quiz_generation_provider_usage",
        indexes = @Index(name = "idx_qgpu_state_unknown_id", columnList = "record_state, unknown_at, id"),
        uniqueConstraints = @UniqueConstraint(
                name = "uq_qgpu_job_attempt",
                columnNames = {"job_id", "provider_attempt_id"}
        )
)
@Getter
@NoArgsConstructor
public class QuizGenerationProviderUsage {

    public enum TerminalOutcome { RECORDED, DUPLICATE, FIRST_CONFLICT, KNOWN_CONFLICT }

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

    @Column(name = "execution_fence", updatable = false)
    private Long executionFence;

    @Column(name = "unknown_at", updatable = false)
    private LocalDateTime unknownAt;

    @Column(name = "terminal_at")
    private LocalDateTime terminalAt;

    @Column(name = "first_conflict_at")
    private LocalDateTime firstConflictAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflicting_record_state", length = 16)
    private ProviderUsageRecordState conflictingRecordState;

    @Column(name = "conflicting_provider_llm_tokens")
    private Long conflictingProviderLlmTokens;

    private QuizGenerationProviderUsage(
            UUID jobId,
            UUID providerAttemptId,
            ProviderUsageRecordState recordState,
            Long providerLlmTokens,
            LocalDateTime recordedAt,
            Long executionFence
    ) {
        this.jobId = Objects.requireNonNull(jobId, "jobId must not be null");
        this.providerAttemptId = Objects.requireNonNull(providerAttemptId, "providerAttemptId must not be null");
        this.recordState = Objects.requireNonNull(recordState, "recordState must not be null");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        this.executionFence = executionFence;
        validateTokens(recordState, providerLlmTokens);
        this.providerLlmTokens = providerLlmTokens;
    }

    public static QuizGenerationProviderUsage started(
            UUID jobId,
            UUID providerAttemptId,
            LocalDateTime recordedAt,
            Long executionFence
    ) {
        return new QuizGenerationProviderUsage(
                jobId,
                providerAttemptId,
                ProviderUsageRecordState.STARTED,
                null,
                recordedAt,
                executionFence
        );
    }

    public TerminalOutcome recordTerminalFact(
            ProviderUsageRecordState terminalState,
            Long tokens,
            LocalDateTime observedAt
    ) {
        Objects.requireNonNull(terminalState, "terminalState must not be null");
        if (terminalState == ProviderUsageRecordState.STARTED || terminalState == ProviderUsageRecordState.UNKNOWN) {
            throw new IllegalArgumentException("terminalState must be terminal");
        }
        validateTokens(terminalState, tokens);
        if (matches(terminalState, tokens)) {
            return TerminalOutcome.DUPLICATE;
        }
        if (recordState != ProviderUsageRecordState.STARTED) {
            if (firstConflictAt != null) {
                return TerminalOutcome.KNOWN_CONFLICT;
            }
            firstConflictAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            conflictingRecordState = terminalState;
            conflictingProviderLlmTokens = tokens;
            return TerminalOutcome.FIRST_CONFLICT;
        }
        terminalAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
        recordState = terminalState;
        providerLlmTokens = tokens;
        return TerminalOutcome.RECORDED;
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
        return getRecordState() == expectedState && Objects.equals(providerLlmTokens, expectedTokens);
    }

    public ProviderUsageRecordState getRecordState() {
        // The marker lets the previous writer finish a STARTED row during rollout.
        return recordState == ProviderUsageRecordState.STARTED && unknownAt != null
                ? ProviderUsageRecordState.UNKNOWN : recordState;
    }
}
