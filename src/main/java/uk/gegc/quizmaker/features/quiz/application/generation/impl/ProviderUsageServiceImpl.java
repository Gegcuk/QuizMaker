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
import uk.gegc.quizmaker.features.quiz.application.generation.ProviderUsageService;
import uk.gegc.quizmaker.features.quiz.domain.model.ProviderUsageRecordState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationProviderUsage;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationProviderUsageRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Clock;
import java.time.LocalDateTime;
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
    private final Counter reportedCounter;
    private final Counter missingCounter;
    private final Counter duplicateCounter;
    private final Counter retryCounter;
    private final Counter failureCounter;
    private final Counter providerTokensCounter;

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
        this.reportedCounter = counter(meterRegistry, "reported");
        this.missingCounter = counter(meterRegistry, "missing");
        this.duplicateCounter = counter(meterRegistry, "duplicate");
        this.retryCounter = counter(meterRegistry, "retry");
        this.failureCounter = counter(meterRegistry, "failure");
        this.providerTokensCounter = Counter.builder("quiz.generation.provider.tokens")
                .description("Total provider-reported LLM tokens recorded durably")
                .register(meterRegistry);
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
                ProviderUsageRecordResult result = Objects.requireNonNull(
                        requiresNew().execute(status -> recordOnce(
                                jobId,
                                providerAttemptId,
                                recordState,
                                providerLlmTokens
                        )),
                        "Provider usage transaction returned no result"
                );
                counterFor(result, recordState).increment();
                if (result == ProviderUsageRecordResult.RECORDED
                        && recordState == ProviderUsageRecordState.REPORTED) {
                    providerTokensCounter.increment(providerLlmTokens);
                }
                return result;
            } catch (TransientDataAccessException | CannotCreateTransactionException exception) {
                if (attempt == MAX_TRANSIENT_ATTEMPTS) {
                    failureCounter.increment();
                    throw exception;
                }
                retryCounter.increment();
                log.warn("Retrying provider usage persistence for job {} after transient database failure ({}/{})",
                        jobId, attempt, MAX_TRANSIENT_ATTEMPTS);
            } catch (RuntimeException exception) {
                failureCounter.increment();
                throw exception;
            }
        }
        throw new IllegalStateException("Provider usage retry loop completed unexpectedly");
    }

    private ProviderUsageRecordResult recordOnce(
            UUID jobId,
            UUID providerAttemptId,
            ProviderUsageRecordState recordState,
            Long providerLlmTokens
    ) {
        var job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));

        var existing = usageRepository.findByJobIdAndProviderAttemptId(jobId, providerAttemptId);
        if (existing.isPresent()) {
            if (!existing.get().matches(recordState, providerLlmTokens)) {
                throw new IllegalStateException(
                        "Provider attempt " + providerAttemptId + " was already recorded with different usage");
            }
            return ProviderUsageRecordResult.DUPLICATE;
        }

        QuizGenerationProviderUsage usage = recordState == ProviderUsageRecordState.REPORTED
                ? QuizGenerationProviderUsage.reported(
                        jobId, providerAttemptId, providerLlmTokens, LocalDateTime.now(clock))
                : QuizGenerationProviderUsage.missing(
                        jobId, providerAttemptId, LocalDateTime.now(clock));
        usageRepository.saveAndFlush(usage);
        job.recordProviderUsage(providerLlmTokens);
        jobRepository.saveAndFlush(job);
        return ProviderUsageRecordResult.RECORDED;
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate;
    }

    private Counter counterFor(ProviderUsageRecordResult result, ProviderUsageRecordState state) {
        if (result == ProviderUsageRecordResult.DUPLICATE) {
            return duplicateCounter;
        }
        return state == ProviderUsageRecordState.REPORTED ? reportedCounter : missingCounter;
    }

    private Counter counter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("quiz.generation.provider.usage")
                .description("Durable provider usage recording outcomes")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
