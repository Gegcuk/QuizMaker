package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.billing.application.GenerationTariff;
import uk.gegc.quizmaker.features.billing.application.GenerationTariffService;
import uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationRequestFingerprint;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationIdempotencyService;
import uk.gegc.quizmaker.features.quiz.domain.exception.GenerationOperationInProgressException;
import uk.gegc.quizmaker.features.quiz.domain.exception.GenerationOperationInconsistentException;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationOperationState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationOperationType;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationOperation;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationOperationRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuizGenerationIdempotencyServiceImpl implements QuizGenerationIdempotencyService {

    private static final int RETENTION_DAYS = 30;
    private static final int SOURCE_PROCESSING_LEASE_MINUTES = 10;
    private static final String LEGACY_METADATA_CANONICALIZATION_VERSION = "v1";
    private static final String SOURCE_DIGEST_CANONICALIZATION_VERSION = "v2-source-digest";

    private final QuizGenerationOperationRepository operationRepository;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;
    private final GenerationTariffService generationTariffService;

    @Override
    public QuizGenerationOperation claim(
            UUID userId,
            GenerationOperationType operationType,
            String idempotencyKey,
            GenerationRequestFingerprint fingerprint,
            boolean legacyKey
    ) {
        try {
            return requiresNew().execute(status -> findOrCreate(
                    userId, operationType, idempotencyKey, fingerprint, legacyKey));
        } catch (DataIntegrityViolationException exception) {
            return requiresNew().execute(status -> operationRepository
                    .findByUserIdAndOperationTypeAndIdempotencyKey(userId, operationType, idempotencyKey)
                    .map(existing -> verifyCompatible(existing, fingerprint))
                    .orElseThrow(() -> exception));
        }
    }

    @Override
    public QuizGenerationOperation lockForGeneration(UUID operationId, UUID userId) {
        return operationRepository.findByIdAndUserIdForUpdate(operationId, userId)
                .orElseThrow(() -> new GenerationOperationInconsistentException(
                        "The quiz-generation operation is no longer available. Retry with a new Idempotency-Key."));
    }

    @Override
    public QuizGenerationOperation get(UUID operationId, UUID userId) {
        return operationRepository.findByIdAndUserId(operationId, userId)
                .orElseThrow(() -> new GenerationOperationInconsistentException(
                        "The quiz-generation operation is no longer available. Retry with a new Idempotency-Key."));
    }

    @Override
    public SourceOperationState acquireSourceProcessing(UUID operationId, UUID userId) {
        return requiresNew().execute(status -> {
            QuizGenerationOperation operation = lockForGeneration(operationId, userId);
            if (operation.hasStartedJob()) {
                return SourceOperationState.REPLAY;
            }
            if (operation.hasSourceDocument()) {
                return SourceOperationState.READY_TO_START;
            }

            LocalDateTime now = LocalDateTime.now(clock);
            if (operation.getState() == GenerationOperationState.SOURCE_PROCESSING
                    && operation.getSourceProcessingStartedAt() != null
                    && operation.getSourceProcessingStartedAt().plusMinutes(SOURCE_PROCESSING_LEASE_MINUTES).isAfter(now)) {
                throw new GenerationOperationInProgressException();
            }

            operation.beginSourceProcessing(now);
            operationRepository.save(operation);
            return SourceOperationState.PROCESS_SOURCE;
        });
    }

    @Override
    public void attachSourceDocument(UUID operationId, UUID userId, UUID documentId) {
        requiresNew().executeWithoutResult(status -> {
            QuizGenerationOperation operation = lockForGeneration(operationId, userId);
            if (!operation.hasStartedJob()) {
                operation.attachSourceDocument(documentId, LocalDateTime.now(clock));
                operationRepository.save(operation);
            }
        });
    }

    @Override
    public void markSourceRetryable(UUID operationId, UUID userId) {
        requiresNew().executeWithoutResult(status -> {
            QuizGenerationOperation operation = lockForGeneration(operationId, userId);
            operation.makeSourceRetryable(LocalDateTime.now(clock));
            operationRepository.save(operation);
        });
    }

    @Override
    public void linkStartedGeneration(
            QuizGenerationOperation operation,
            UUID jobId,
            UUID reservationId,
            int estimatedTimeSeconds
    ) {
        operation.linkStartedGeneration(jobId, reservationId, estimatedTimeSeconds, LocalDateTime.now(clock));
        operationRepository.save(operation);
    }

    @Override
    public int purgeExpiredOperations() {
        Integer deleted = requiresNew().execute(status ->
                operationRepository.deleteExpiredBefore(LocalDateTime.now(clock)));
        return deleted == null ? 0 : deleted;
    }

    private QuizGenerationOperation findOrCreate(
            UUID userId,
            GenerationOperationType operationType,
            String idempotencyKey,
            GenerationRequestFingerprint fingerprint,
            boolean legacyKey
    ) {
        return operationRepository.findByUserIdAndOperationTypeAndIdempotencyKey(userId, operationType, idempotencyKey)
                .map(existing -> verifyCompatible(existing, fingerprint))
                .orElseGet(() -> {
                    LocalDateTime now = LocalDateTime.now(clock);
                    GenerationOperationState initialState = operationType == GenerationOperationType.DOCUMENT
                            ? GenerationOperationState.READY_TO_START
                            : GenerationOperationState.SOURCE_PENDING;
                    QuizGenerationOperation operation = new QuizGenerationOperation(
                            userId,
                            operationType,
                            idempotencyKey,
                            fingerprint.hash(),
                            fingerprint.canonicalizationVersion(),
                            legacyKey,
                            initialState,
                            now,
                            now.plusDays(RETENTION_DAYS)
                    );
                    GenerationTariff tariff = generationTariffService.currentTariff();
                    operation.captureGenerationTariffSnapshot(
                            tariff.version(),
                            tariff.baseTokens(),
                            tariff.tokensPerThousandCharacters()
                    );
                    return operationRepository.saveAndFlush(operation);
                });
    }

    private QuizGenerationOperation verifyCompatible(
            QuizGenerationOperation existing,
            GenerationRequestFingerprint fingerprint
    ) {
        if (!Objects.equals(existing.getCanonicalizationVersion(), fingerprint.canonicalizationVersion())
                || !Objects.equals(existing.getRequestHash(), fingerprint.hash())) {
            if (isSafeLegacyReplay(existing, fingerprint)) {
                return existing;
            }
            throw new IdempotencyConflictException(
                    "This Idempotency-Key was already used for a different quiz-generation request.");
        }
        return existing;
    }

    private boolean isSafeLegacyReplay(
            QuizGenerationOperation existing,
            GenerationRequestFingerprint fingerprint
    ) {
        return existing.hasStartedJob()
                && LEGACY_METADATA_CANONICALIZATION_VERSION.equals(existing.getCanonicalizationVersion())
                && SOURCE_DIGEST_CANONICALIZATION_VERSION.equals(fingerprint.canonicalizationVersion());
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate;
    }
}
