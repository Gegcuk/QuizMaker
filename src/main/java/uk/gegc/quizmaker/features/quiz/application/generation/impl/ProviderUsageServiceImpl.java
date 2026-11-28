package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.quiz.application.generation.ProviderUsageRecordResult;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkerContext;
import uk.gegc.quizmaker.features.quiz.application.generation.ProviderUsageService;
import uk.gegc.quizmaker.features.quiz.domain.model.ProviderUsageRecordState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationProviderUsage;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationProviderUsageRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class ProviderUsageServiceImpl implements ProviderUsageService {

    private static final int MAX_TRANSIENT_ATTEMPTS = 3;

    private final QuizGenerationJobRepository jobRepository;
    private final QuizGenerationProviderUsageRepository usageRepository;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;
    private final MeterRegistry metrics;

    private record WriteResult(ProviderUsageRecordResult result, boolean reconciled) {
        private static final WriteResult RECORDED = new WriteResult(ProviderUsageRecordResult.RECORDED, false);
        private static final WriteResult DUPLICATE = new WriteResult(ProviderUsageRecordResult.DUPLICATE, false);
    }

    public ProviderUsageServiceImpl(
            QuizGenerationJobRepository jobRepository,
            QuizGenerationProviderUsageRepository usageRepository,
            PlatformTransactionManager transactionManager,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.jobRepository = jobRepository;
        this.usageRepository = usageRepository;
        this.transactionManager = transactionManager;
        this.clock = clock;
        this.metrics = meterRegistry;
        for (String outcome : List.of("started", "reported", "missing", "failed", "duplicate",
                "conflict", "retry", "failure", "reconciled")) {
            count(outcome, 0);
        }
        countTokens(0);
    }

    @Override
    public ProviderUsageRecordResult recordStarted(UUID jobId, UUID providerAttemptId) {
        return record(jobId, providerAttemptId, ProviderUsageRecordState.STARTED, null);
    }

    @Override
    public ProviderUsageRecordResult recordReported(
            UUID jobId,
            UUID providerAttemptId,
            long providerLlmTokens
    ) {
        if (providerLlmTokens < 0L) {
            throw new IllegalArgumentException("providerLlmTokens must not be negative");
        }
        return record(jobId, providerAttemptId, ProviderUsageRecordState.REPORTED, providerLlmTokens);
    }

    @Override
    public ProviderUsageRecordResult recordMissing(UUID jobId, UUID providerAttemptId) {
        return record(jobId, providerAttemptId, ProviderUsageRecordState.MISSING, null);
    }

    @Override
    public ProviderUsageRecordResult recordFailed(UUID jobId, UUID providerAttemptId) {
        return record(jobId, providerAttemptId, ProviderUsageRecordState.FAILED, null);
    }

    private ProviderUsageRecordResult record(
            UUID jobId,
            UUID providerAttemptId,
            ProviderUsageRecordState recordState,
            Long providerLlmTokens
    ) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(providerAttemptId, "providerAttemptId must not be null");

        for (int attempt = 1; attempt <= MAX_TRANSIENT_ATTEMPTS; attempt++) {
            try {
                WriteResult written = Objects.requireNonNull(
                        requiresNew().execute(status -> recordOnce(
                                jobId,
                                providerAttemptId,
                                recordState,
                                providerLlmTokens
                        )),
                        "Provider usage transaction returned no result"
                );
                var result = written.result();
                count(result == ProviderUsageRecordResult.DUPLICATE
                        ? "duplicate" : recordState.name().toLowerCase(Locale.ROOT), 1);
                if (written.reconciled()) count("reconciled", 1);
                if (result == ProviderUsageRecordResult.RECORDED
                        && recordState == ProviderUsageRecordState.REPORTED) {
                    countTokens(providerLlmTokens);
                }
                return result;
            } catch (TransientDataAccessException | CannotCreateTransactionException exception) {
                if (attempt == MAX_TRANSIENT_ATTEMPTS) {
                    count("failure", 1);
                    throw exception;
                }
                count("retry", 1);
                log.warn("Retrying provider usage persistence for job {} after transient database failure ({}/{})",
                        jobId, attempt, MAX_TRANSIENT_ATTEMPTS);
            } catch (RuntimeException exception) {
                count(exception instanceof IllegalStateException ? "conflict" : "failure", 1);
                throw exception;
            }
        }
        throw new IllegalStateException("Provider usage retry loop completed unexpectedly");
    }

    private WriteResult recordOnce(
            UUID jobId,
            UUID providerAttemptId,
            ProviderUsageRecordState recordState,
            Long providerLlmTokens
    ) {
        var job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));

        if (recordState == ProviderUsageRecordState.STARTED) {
            // Capture the same fence proven under the job lock. Later reports remain
            // valid even after that worker loses the right to start more calls.
            Long executionFence = GenerationWorkerContext.protectedExecutionFence(jobId);
            var existing = usageRepository.findByJobIdAndProviderAttemptId(jobId, providerAttemptId);
            if (existing.isPresent()) {
                return WriteResult.DUPLICATE;
            }
            usageRepository.saveAndFlush(QuizGenerationProviderUsage.started(
                    jobId, providerAttemptId, LocalDateTime.now(clock), executionFence));
            job.markProviderAttemptStarted();
            jobRepository.saveAndFlush(job);
            return WriteResult.RECORDED;
        }

        QuizGenerationProviderUsage usage = usageRepository.findByJobIdAndProviderAttemptId(jobId, providerAttemptId)
                .orElseThrow(() -> new IllegalStateException(
                        "Provider attempt " + providerAttemptId + " has no durable start"));
        boolean reconciled = usage.getRecordState() == ProviderUsageRecordState.UNKNOWN;
        if (!usage.transitionTo(recordState, providerLlmTokens, LocalDateTime.now(clock))) {
            return WriteResult.DUPLICATE;
        }
        usageRepository.saveAndFlush(usage);
        if (recordState == ProviderUsageRecordState.REPORTED) {
            job.recordProviderUsage(providerLlmTokens);
        }
        job.reconcileProviderUsageCompleteness(!usageRepository.existsByJobIdAndRecordStateNot(
                jobId, ProviderUsageRecordState.REPORTED));
        jobRepository.saveAndFlush(job);
        return new WriteResult(ProviderUsageRecordResult.RECORDED, reconciled);
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate;
    }

    private void count(String outcome, long amount) {
        try {
            Counter.builder("quiz.generation.provider.usage")
                    .description("Durable provider usage recording outcomes")
                    .tag("outcome", outcome)
                    .register(metrics)
                    .increment(amount);
        } catch (RuntimeException ignored) {
            // A metrics failure cannot turn a committed start or report into a dispatch failure.
        }
    }

    private void countTokens(long amount) {
        try {
            Counter.builder("quiz.generation.provider.tokens")
                    .description("Total provider-reported LLM tokens recorded durably")
                    .register(metrics)
                    .increment(amount);
        } catch (RuntimeException ignored) {
            // The provider usage transaction is already committed.
        }
    }
}
