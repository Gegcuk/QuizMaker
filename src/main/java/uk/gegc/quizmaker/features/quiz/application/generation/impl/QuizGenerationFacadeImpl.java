package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidJobStateForCommitException;
import uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationResponse;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationStatus;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizAssemblyService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationIdempotencyService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationRequestCanonicalizer;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationRequestedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationOperationType;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationOperation;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.exception.GenerationOperationInProgressException;
import uk.gegc.quizmaker.features.quiz.domain.exception.GenerationOperationInconsistentException;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuizGenerationFacadeImpl implements QuizGenerationFacade {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final UserRepository userRepository;
    private final QuizGenerationJobRepository jobRepository;
    private final QuizGenerationJobService jobService;
    private final AiQuizGenerationService aiQuizGenerationService;
    private final DocumentProcessingService documentProcessingService;
    private final BillingService billingService;
    private final InternalBillingService internalBillingService;
    private final EstimationService estimationService;
    private final FeatureFlags featureFlags;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final QuizJobProperties quizJobProperties;
    private final QuizAssemblyService quizAssemblyService;
    private final QuizGenerationIdempotencyService idempotencyService;
    private final QuizGenerationRequestCanonicalizer requestCanonicalizer;

    @Override
    public QuizGenerationResponse generateQuizFromDocument(String username, GenerateQuizFromDocumentRequest request) {
        return generateQuizFromDocument(username, request, null);
    }

    @Override
    public QuizGenerationResponse generateQuizFromDocument(
            String username,
            GenerateQuizFromDocumentRequest request,
            String idempotencyKey
    ) {
        return startQuizGeneration(username, request, idempotencyKey);
    }

    @Override
    public QuizGenerationResponse generateQuizFromUpload(String username, MultipartFile file, GenerateQuizFromUploadRequest request) {
        return generateQuizFromUpload(username, file, request, null);
    }

    @Override
    public QuizGenerationResponse generateQuizFromUpload(
            String username,
            MultipartFile file,
            GenerateQuizFromUploadRequest request,
            String idempotencyKey
    ) {
        User user = findUser(username);
        QuizGenerationOperation operation = claimOperation(
                user,
                GenerationOperationType.UPLOAD,
                idempotencyKey,
                requestCanonicalizer.forUpload(request, file)
        );
        if (operation.hasStartedJob()) {
            return existingGenerationResponse(operation);
        }

        try {
            SourceResolution source = resolveOrProcessUploadSource(username, user, file, request, operation);
            if (source.replayResponse() != null) {
                return source.replayResponse();
            }
            return startQuizGenerationForOperation(
                    username,
                    user,
                    request.toGenerateQuizFromDocumentRequest(source.documentId()),
                    operation.getId()
            );
        } catch (InsufficientTokensException e) {
            throw e;
        } catch (IdempotencyConflictException | GenerationOperationInProgressException | GenerationOperationInconsistentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to start quiz generation from upload for user: {}", username, e);
            throw new RuntimeException("Failed to generate quiz from upload: " + e.getMessage(), e);
        }
    }

    @Override
    public QuizGenerationResponse generateQuizFromText(String username, GenerateQuizFromTextRequest request) {
        return generateQuizFromText(username, request, null);
    }

    @Override
    public QuizGenerationResponse generateQuizFromText(
            String username,
            GenerateQuizFromTextRequest request,
            String idempotencyKey
    ) {
        User user = findUser(username);
        QuizGenerationOperation operation = claimOperation(
                user,
                GenerationOperationType.TEXT,
                idempotencyKey,
                requestCanonicalizer.forText(request)
        );
        if (operation.hasStartedJob()) {
            return existingGenerationResponse(operation);
        }

        try {
            log.info("Starting quiz generation from text for user: {}, text length: {}", username, request.text().length());
            SourceResolution source = resolveOrProcessTextSource(username, user, request, operation);
            if (source.replayResponse() != null) {
                return source.replayResponse();
            }
            return startQuizGenerationForOperation(
                    username,
                    user,
                    request.toGenerateQuizFromDocumentRequest(source.documentId()),
                    operation.getId()
            );
        } catch (InsufficientTokensException e) {
            throw e;
        } catch (IdempotencyConflictException | GenerationOperationInProgressException | GenerationOperationInconsistentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to start quiz generation from text for user: {}", username, e);
            throw new RuntimeException("Failed to generate quiz from text: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public DocumentDto processDocumentCompletely(String username, MultipartFile file, GenerateQuizFromUploadRequest request) {
        try {
            log.info("Starting document processing for user: {}", username);
            DocumentDto document = documentProcessingService.uploadAndProcessDocument(
                    username,
                    file.getBytes(),
                    file.getOriginalFilename(),
                    request.toProcessDocumentRequest()
            );
            log.info("Document processed successfully: {} with {} chunks", document.getId(), document.getTotalChunks());
            return document;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file bytes: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void verifyDocumentChunks(UUID documentId, GenerateQuizFromUploadRequest request) {
        log.info("Verifying document chunks for document: {}", documentId);
        int totalChunks = aiQuizGenerationService.calculateTotalChunks(documentId, request.toGenerateQuizFromDocumentRequest(documentId));
        if (totalChunks <= 0) {
            throw new RuntimeException("Document has no chunks available for quiz generation. Please try processing the document again.");
        }
        log.info("Document verification successful: {} chunks available", totalChunks);
    }

    @Override
    @Transactional(readOnly = true)
    public void verifyDocumentChunks(UUID documentId, GenerateQuizFromTextRequest request) {
        log.info("Verifying document chunks for document: {}", documentId);
        int totalChunks = aiQuizGenerationService.calculateTotalChunks(documentId, request.toGenerateQuizFromDocumentRequest(documentId));
        if (totalChunks <= 0) {
            throw new RuntimeException("Document has no chunks available for quiz generation. Please try processing the document again.");
        }
        log.info("Document verification successful: {} chunks available", totalChunks);
    }

    @Override
    @Transactional
    public DocumentDto processTextAsDocument(String username, GenerateQuizFromTextRequest request) {
        try {
            log.info("Starting text processing for user: {}", username);
            byte[] textBytes = request.text().getBytes(StandardCharsets.UTF_8);
            String filename = "text-input.txt";
            DocumentDto document = documentProcessingService.uploadAndProcessDocument(
                    username,
                    textBytes,
                    filename,
                    request.toProcessDocumentRequest()
            );
            log.info("Text processed successfully: {} with {} chunks", document.getId(), document.getTotalChunks());
            return document;
        } catch (Exception e) {
            throw new RuntimeException("Failed to process text as document: " + e.getMessage(), e);
        }
    }

    @Override
    public QuizGenerationResponse startQuizGeneration(String username, GenerateQuizFromDocumentRequest request) {
        return startQuizGeneration(username, request, null);
    }

    @Override
    public QuizGenerationResponse startQuizGeneration(
            String username,
            GenerateQuizFromDocumentRequest request,
            String idempotencyKey
    ) {
        User user = findUser(username);
        QuizGenerationOperation operation = claimOperation(
                user,
                GenerationOperationType.DOCUMENT,
                idempotencyKey,
                requestCanonicalizer.forDocument(request)
        );
        if (operation.hasStartedJob()) {
            return existingGenerationResponse(operation);
        }
        return startQuizGenerationForOperation(username, user, request, operation.getId());
    }

    private QuizGenerationResponse startQuizGenerationForOperation(
            String username,
            User user,
            GenerateQuizFromDocumentRequest request,
            UUID operationId
    ) {
        return transactionTemplate.execute(status -> {
            QuizGenerationOperation operation = idempotencyService.lockForGeneration(operationId, user.getId());
            if (operation.hasStartedJob()) {
                return existingGenerationResponse(operation);
            }

            try {
                // The operation is owned by this user; the source document must be as well.
                documentProcessingService.getDocumentById(request.documentId(), username);
                EstimationDto estimation = estimationService.estimateQuizGeneration(request.documentId(), request);
                long estimatedTokens = estimation.estimatedBillingTokens();

                log.info("Estimated {} billing tokens for quiz generation for user {}", estimatedTokens, username);

                String reservationIdempotencyKey = "quiz-generation:reserve:" + operation.getId();

                ReservationDto reservation;
                try {
                    reservation = internalBillingService.reserve(
                            user.getId(), estimatedTokens, "quiz-generation", reservationIdempotencyKey);
                    log.info("Reserved {} tokens for user {} (reservationId={})",
                            estimatedTokens, username, reservation.id());
                } catch (InsufficientTokensException e) {
                    throw new InsufficientTokensException(
                            "Insufficient tokens to start quiz generation. " + e.getMessage(),
                            e.getEstimatedTokens(), e.getAvailableTokens(), e.getShortfall(), e.getReservationTtl());
                }

                if (!user.getId().equals(reservation.userId()) || reservation.state() != ReservationState.ACTIVE) {
                    throw new GenerationOperationInconsistentException(
                            "The existing generation reservation is unavailable. Retry with a new Idempotency-Key.");
                }

                int totalChunks = aiQuizGenerationService.calculateTotalChunks(request.documentId(), request);
                int estimatedSeconds = aiQuizGenerationService.calculateEstimatedGenerationTime(
                        totalChunks, request.questionsPerType());

                QuizGenerationJob job;
                try {
                    job = jobService.createJob(user, request.documentId(),
                            objectMapper.writeValueAsString(request), totalChunks, estimatedSeconds);
                } catch (DataIntegrityViolationException e) {
                    log.warn("Job creation failed due to constraint violation; checking for stale job");
                    if (isActiveJobConstraint(e)) {
                        Optional<QuizGenerationJob> staleCancelled = jobService.findAndCancelStaleJobForUser(username);
                        if (staleCancelled.isPresent()) {
                            log.info("Auto-cancelled stale job {}, retrying job creation", staleCancelled.get().getId());
                            try {
                                job = jobService.createJob(user, request.documentId(),
                                        objectMapper.writeValueAsString(request), totalChunks, estimatedSeconds);
                                log.info("Successfully created job after auto-cancelling stale job");
                            } catch (Exception retryEx) {
                                log.error("Retry of job creation failed after auto-cancel", retryEx);
                                throw new ValidationException("User already has an active generation job. Please try again.");
                            }
                        } else {
                            log.info("No stale job found; user has a legitimately active job");
                            throw new ValidationException("User already has an active generation job. Please wait for it to complete.");
                        }
                    } else {
                        throw e;
                    }
                }

                job.setBillingReservationId(reservation.id());
                job.setReservationExpiresAt(reservation.expiresAt());
                job.setBillingEstimatedTokens(estimatedTokens);
                job.setBillingState(BillingState.RESERVED);
                job.setInputPromptTokens(estimation.estimatedLlmTokens());
                job.setEstimationVersion("v1.0");
                job.setBillingIdempotencyKeys(objectMapper.writeValueAsString(Map.of("reserve", reservationIdempotencyKey)));
                jobRepository.save(job);
                internalBillingService.attachReservationToJob(user.getId(), reservation.id(), job.getId());
                idempotencyService.linkStartedGeneration(operation, job.getId(), reservation.id(), estimatedSeconds);

                log.info("Updated job {} for user {} with reservation {}, starting async generation",
                        job.getId(), username, reservation.id());

                applicationEventPublisher.publishEvent(new QuizGenerationRequestedEvent(this, job.getId(), request));

                return QuizGenerationResponse.started(job.getId(), (long) estimatedSeconds);
            } catch (JsonProcessingException e) {
                throw new ValidationException("Failed to serialize request data: " + e.getMessage());
            }
        });
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    private QuizGenerationOperation claimOperation(
            User user,
            GenerationOperationType operationType,
            String suppliedKey,
            uk.gegc.quizmaker.features.quiz.application.generation.GenerationRequestFingerprint fingerprint
    ) {
        IdempotencyKey idempotencyKey = normalizeIdempotencyKey(suppliedKey);
        return idempotencyService.claim(
                user.getId(), operationType, idempotencyKey.value(), fingerprint, idempotencyKey.legacy());
    }

    private SourceResolution resolveOrProcessUploadSource(
            String username,
            User user,
            MultipartFile file,
            GenerateQuizFromUploadRequest request,
            QuizGenerationOperation operation
    ) {
        QuizGenerationIdempotencyService.SourceOperationState sourceState =
                idempotencyService.acquireSourceProcessing(operation.getId(), user.getId());
        if (sourceState == QuizGenerationIdempotencyService.SourceOperationState.REPLAY) {
            return SourceResolution.replay(existingGenerationResponse(idempotencyService.get(operation.getId(), user.getId())));
        }
        if (sourceState == QuizGenerationIdempotencyService.SourceOperationState.READY_TO_START) {
            return SourceResolution.document(idempotencyService.get(operation.getId(), user.getId()).getSourceDocumentId());
        }

        try {
            DocumentDto document = processDocumentCompletely(username, file, request);
            verifyDocumentChunks(document.getId(), request);
            idempotencyService.attachSourceDocument(operation.getId(), user.getId(), document.getId());
            return SourceResolution.document(document.getId());
        } catch (RuntimeException exception) {
            idempotencyService.markSourceRetryable(operation.getId(), user.getId());
            throw exception;
        }
    }

    private SourceResolution resolveOrProcessTextSource(
            String username,
            User user,
            GenerateQuizFromTextRequest request,
            QuizGenerationOperation operation
    ) {
        QuizGenerationIdempotencyService.SourceOperationState sourceState =
                idempotencyService.acquireSourceProcessing(operation.getId(), user.getId());
        if (sourceState == QuizGenerationIdempotencyService.SourceOperationState.REPLAY) {
            return SourceResolution.replay(existingGenerationResponse(idempotencyService.get(operation.getId(), user.getId())));
        }
        if (sourceState == QuizGenerationIdempotencyService.SourceOperationState.READY_TO_START) {
            return SourceResolution.document(idempotencyService.get(operation.getId(), user.getId()).getSourceDocumentId());
        }

        try {
            DocumentDto document = processTextAsDocument(username, request);
            verifyDocumentChunks(document.getId(), request);
            idempotencyService.attachSourceDocument(operation.getId(), user.getId(), document.getId());
            return SourceResolution.document(document.getId());
        } catch (RuntimeException exception) {
            idempotencyService.markSourceRetryable(operation.getId(), user.getId());
            throw exception;
        }
    }

    private QuizGenerationResponse existingGenerationResponse(QuizGenerationOperation operation) {
        UUID jobId = operation.getJobId();
        QuizGenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new GenerationOperationInconsistentException(
                        "The existing quiz-generation job is unavailable. Retry with a new Idempotency-Key."));
        return new QuizGenerationResponse(
                job.getId(),
                job.getStatus(),
                "Existing quiz generation returned for this Idempotency-Key",
                operation.getEstimatedTimeSeconds() == null ? null : operation.getEstimatedTimeSeconds().longValue()
        );
    }

    private boolean isActiveJobConstraint(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && (message.contains("active_user_id")
                || message.contains("one_active_per_user")
                || message.contains("active_username"));
    }

    private IdempotencyKey normalizeIdempotencyKey(String suppliedKey) {
        if (suppliedKey == null) {
            return new IdempotencyKey("legacy-" + UUID.randomUUID(), true);
        }
        String normalized = suppliedKey.trim();
        if (normalized.isEmpty()) {
            throw new ValidationException("Idempotency-Key must contain at least one non-whitespace character");
        }
        if (normalized.length() > 128) {
            throw new ValidationException("Idempotency-Key must not exceed 128 characters");
        }
        return new IdempotencyKey(normalized, false);
    }

    private record IdempotencyKey(String value, boolean legacy) {
    }

    private record SourceResolution(UUID documentId, QuizGenerationResponse replayResponse) {
        static SourceResolution document(UUID documentId) {
            return new SourceResolution(documentId, null);
        }

        static SourceResolution replay(QuizGenerationResponse replayResponse) {
            return new SourceResolution(null, replayResponse);
        }
    }

    @Override
    @Transactional
    public QuizGenerationStatus cancelGenerationJob(UUID jobId, String username) {
        QuizGenerationJob job = jobService.getJobByIdAndUsername(jobId, username);

        if (job.getStatus().isTerminal()) {
            throw new ValidationException("Cannot cancel job that is already in terminal state: " + job.getStatus());
        }

        jobService.cancelJob(jobId, username);
        job = jobRepository.findById(jobId).orElseThrow();
        job.setErrorMessage("Cancelled by user");
        jobRepository.save(job);

        if (job.getBillingReservationId() != null && job.getBillingState() == BillingState.RESERVED) {
            boolean hasStartedWork = Boolean.TRUE.equals(job.getHasStartedAiCalls());
            boolean commitOnCancel = quizJobProperties.getCancellation().isCommitOnCancel();

            if (hasStartedWork && featureFlags.isBilling() && commitOnCancel) {
                handleCancellationCommit(jobId, job);
            } else {
                handleCancellationRelease(jobId, job);
            }
        }

        return QuizGenerationStatus.fromEntity(job, featureFlags.isBilling());
    }

    private void handleCancellationCommit(UUID jobId, QuizGenerationJob job) {
        try {
            long actualTokens = job.getActualTokens() != null ? job.getActualTokens() : 0L;
            long estimatedTokens = job.getBillingEstimatedTokens();
            long minStartFeeTokens = quizJobProperties.getCancellation().getMinStartFeeTokens();

            long tokensToCommit = Math.max(actualTokens, minStartFeeTokens);
            tokensToCommit = Math.min(tokensToCommit, estimatedTokens);

            if (tokensToCommit > 0) {
                String commitIdempotencyKey = "quiz:" + jobId + ":commit-cancel";
                CommitResultDto commitResult = internalBillingService.commit(
                        job.getBillingReservationId(),
                        tokensToCommit,
                        jobId.toString(),
                        commitIdempotencyKey
                );
                job.setBillingCommittedTokens(tokensToCommit);
                job.setBillingState(BillingState.COMMITTED);
                job.addBillingIdempotencyKey("commit-cancel", commitIdempotencyKey);
                log.info("Committed {} tokens for cancelled job {} (actual tokens used: {})", tokensToCommit, jobId, actualTokens);

                if (commitResult.releasedTokens() > 0) {
                    log.info("Released {} tokens back to user after cancel commit", commitResult.releasedTokens());
                }
            } else {
                handleCancellationRelease(jobId, job);
                return;
            }
            jobRepository.save(job);
        } catch (Exception billingError) {
            log.error("Failed to commit/release billing for cancelled job {}", jobId, billingError);
            job.setLastBillingError("{\"error\":\"Failed to process billing on cancel: " + billingError.getMessage() + "\"}");
            jobRepository.save(job);
        }
    }

    private void handleCancellationRelease(UUID jobId, QuizGenerationJob job) {
        try {
            String releaseIdempotencyKey = "quiz:" + jobId + ":release";
            billingService.release(
                    job.getBillingReservationId(),
                    "Job cancelled by user",
                    jobId.toString(),
                    releaseIdempotencyKey
            );
            job.setBillingState(BillingState.RELEASED);
            job.addBillingIdempotencyKey("release", releaseIdempotencyKey);
            jobRepository.save(job);
            log.info("Released billing reservation {} for cancelled job {}", job.getBillingReservationId(), jobId);
        } catch (Exception billingError) {
            log.error("Failed to release billing reservation for cancelled job {}", jobId, billingError);
            job.setLastBillingError("{\"error\":\"Failed to release reservation: " + billingError.getMessage() + "\"}");
            jobRepository.save(job);
        }
    }

    @Override
    @Transactional
    public void createQuizCollectionFromGeneratedQuestions(
            UUID jobId,
            Map<Integer, List<Question>> chunkQuestions,
            GenerateQuizFromDocumentRequest originalRequest
    ) {
        try {
            QuizGenerationJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));

            if (job.isTerminal()) {
                log.info("Job {} already in terminal state {}, skipping quiz creation", jobId, job.getStatus());
                return;
            }

            int chunkCount = (int) chunkQuestions.values().stream()
                    .filter(Objects::nonNull)
                    .filter(list -> !list.isEmpty())
                    .count();

            List<Question> allQuestions = chunkQuestions.values().stream()
                    .filter(Objects::nonNull)
                    .flatMap(List::stream)
                    .collect(Collectors.toList());

            User user = job.getUser();
            UUID documentId = originalRequest.documentId();

            Category category = quizAssemblyService.getOrCreateAICategory();
            Set<Tag> tags = quizAssemblyService.resolveTags(originalRequest);

            if (chunkCount > 1) {
                for (Map.Entry<Integer, List<Question>> entry : chunkQuestions.entrySet()) {
                    int chunkIndex = entry.getKey();
                    List<Question> questions = entry.getValue();

                    if (questions == null || questions.isEmpty()) {
                        continue;
                    }

                    quizAssemblyService.createChunkQuiz(
                            user, questions, chunkIndex, originalRequest, category, tags, documentId
                    );
                }
            }

            Quiz consolidatedQuiz = quizAssemblyService.createConsolidatedQuiz(
                    user, allQuestions, originalRequest, category, tags, documentId, chunkCount
            );

            int totalQuestions = allQuestions.size();
            job.markCompleted(consolidatedQuiz.getId(), totalQuestions);
            jobRepository.save(job);

            commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
        } catch (Exception e) {
            handleQuizCollectionFailure(jobId, e);
            throw new RuntimeException("Failed to create quiz collection from generated questions", e);
        }
    }

    private void handleQuizCollectionFailure(UUID jobId, Exception exception) {
        log.error("Failed to create quiz collection for job {}", jobId, exception);
        try {
            QuizGenerationJob job = jobRepository.findById(jobId).orElse(null);
            if (job != null) {
                job.markFailed("Failed to create quiz collection: " + exception.getMessage());

                if (job.getBillingReservationId() != null && job.getBillingState() == BillingState.RESERVED) {
                    try {
                        String releaseIdempotencyKey = "quiz:" + jobId + ":release";
                        internalBillingService.release(
                                job.getBillingReservationId(),
                                "Quiz creation failed: " + exception.getMessage(),
                                jobId.toString(),
                                releaseIdempotencyKey
                        );
                        job.setBillingState(BillingState.RELEASED);
                        job.addBillingIdempotencyKey("release", releaseIdempotencyKey);
                        log.info("Released billing reservation {} for failed quiz creation job {}", job.getBillingReservationId(), jobId);
                    } catch (Exception billingError) {
                        log.error("Failed to release billing reservation for quiz creation failure job {}", jobId, billingError);
                        job.setLastBillingError("{\"error\":\"Failed to release reservation: " + billingError.getMessage() + "\"}");
                    }
                }

                jobRepository.save(job);
            }
        } catch (Exception saveError) {
            log.error("Failed to update job status after quiz creation failure for job {}", jobId, saveError);
        }
    }

    @Override
    public void commitTokensForSuccessfulGeneration(QuizGenerationJob job, List<Question> allQuestions,
                                                    GenerateQuizFromDocumentRequest originalRequest) {
        String jobId = job.getId().toString();
        String correlationId = "commit-" + jobId + "-" + System.currentTimeMillis();

        try {
            log.info("Starting token commit for job {} [correlationId={}]", jobId, correlationId);

            QuizGenerationJob lockedJob = jobRepository.findByIdForUpdate(job.getId())
                    .orElseThrow(() -> new IllegalStateException("Job " + jobId + " not found during commit"));

            if (lockedJob.getBillingReservationId() == null) {
                log.warn("Job {} has no reservation ID, cannot commit [correlationId={}]", jobId, correlationId);
                return;
            }

            if (!lockedJob.getBillingState().isReserved()) {
                if (lockedJob.getBillingState() == BillingState.COMMITTED) {
                    log.info("Job {} already committed, returning success [correlationId={}]", jobId, correlationId);
                    return;
                }
                throw new InvalidJobStateForCommitException(jobId, lockedJob.getBillingState());
            }

            String commitIdempotencyKey = "quiz:" + lockedJob.getId() + ":commit";
            if (hasBillingIdempotencyKey(lockedJob, "commit")) {
                log.info("Job {} already has commit idempotency key, returning success [correlationId={}]", jobId, correlationId);
                return;
            }

            if (!lockedJob.getStatus().isSuccess()) {
                throw new InvalidJobStateForCommitException(jobId, lockedJob.getBillingState(),
                        "Job must be in COMPLETED status to commit tokens. Current status: " + lockedJob.getStatus());
            }

            if (lockedJob.isReservationExpired()) {
                log.warn("Reservation for job {} has expired, skipping commit [correlationId={}]", jobId, correlationId);
                return;
            }

            long inputPromptTokens = lockedJob.getInputPromptTokens() != null ? lockedJob.getInputPromptTokens() : 0L;

            long actualBillingTokens = estimationService.computeActualBillingTokens(
                    allQuestions,
                    originalRequest.difficulty(),
                    inputPromptTokens
            );

            long reservedTokens = lockedJob.getBillingEstimatedTokens();
            long tokensToCommit = Math.min(actualBillingTokens, reservedTokens);
            boolean wasCapped = actualBillingTokens > reservedTokens;

            if (wasCapped) {
                long underestimationDelta = actualBillingTokens - reservedTokens;
                log.warn("BILLING_UNDERESTIMATION: Actual tokens ({}) exceed reserved tokens ({}) for job {} (reservationId: {}). " +
                                "Delta: {}, Committing reserved amount to avoid overcharging user.",
                        actualBillingTokens, reservedTokens, jobId, lockedJob.getBillingReservationId(), underestimationDelta);
            }

            log.info("Committing {} billing tokens for job {} (actual: {}, reserved: {}, inputPromptTokens: {}, questions: {}) [correlationId={}]",
                    tokensToCommit, jobId, actualBillingTokens, reservedTokens, inputPromptTokens, allQuestions.size(), correlationId);

            var commitResult = internalBillingService.commit(
                    lockedJob.getBillingReservationId(),
                    tokensToCommit,
                    "quiz-generation",
                    commitIdempotencyKey
            );

            long reserved = lockedJob.getBillingEstimatedTokens();
            long remainder = Math.max(0, reserved - tokensToCommit);
            if (remainder > 0 && (commitResult == null || commitResult.releasedTokens() == 0)) {
                try {
                    log.info("Explicitly releasing remainder {} tokens for job {} [correlationId={}]", remainder, jobId, correlationId);
                    internalBillingService.release(lockedJob.getBillingReservationId(), "commit-remainder", "quiz-generation", null);
                } catch (Exception ex) {
                    log.warn("Failed to explicitly release remainder {} for reservation {}: {} [correlationId={}]",
                            remainder, lockedJob.getBillingReservationId(), ex.getMessage(), correlationId);
                }
            }

            lockedJob.setActualTokens(actualBillingTokens);
            lockedJob.setBillingCommittedTokens(tokensToCommit);
            lockedJob.setWasCappedAtReserved(wasCapped);
            lockedJob.setBillingState(BillingState.COMMITTED);

            updateBillingIdempotencyKeys(lockedJob, "commit", commitIdempotencyKey);

            lockedJob.setLastBillingError(null);

            jobRepository.save(lockedJob);

            log.info("Successfully committed {} tokens for job {} (actual: {}, remainder released: {}, wasCappedAtReserved: {}) [correlationId={}]",
                    tokensToCommit, jobId, actualBillingTokens,
                    commitResult != null ? commitResult.releasedTokens() : 0L, wasCapped, correlationId);

        } catch (InvalidJobStateForCommitException e) {
            log.error("Business rule violation during commit for job {} [correlationId={}]: {}", jobId, correlationId, e.getMessage());
            storeBillingError(job, e, correlationId);
        } catch (Exception e) {
            log.error("Unexpected error during commit for job {} [correlationId={}]", jobId, correlationId, e);
            storeBillingError(job, e, correlationId);
        }
    }

    private void storeBillingError(QuizGenerationJob job, Exception e, String correlationId) {
        try {
            BillingError billingError = new BillingError(
                    e.getMessage(),
                    e.getClass().getSimpleName(),
                    java.time.Instant.now(),
                    correlationId
            );
            job.setLastBillingError(objectMapper.writeValueAsString(billingError));
            jobRepository.save(job);
        } catch (Exception saveError) {
            log.error("Failed to save billing error for job {} [correlationId={}]", job.getId(), correlationId, saveError);
        }
    }

    private record BillingError(String error, String errorType, java.time.Instant timestamp, String correlationId) {}

    private void updateBillingIdempotencyKeys(QuizGenerationJob job, String operation, String idempotencyKey) {
        try {
            Map<String, String> keys = new HashMap<>();
            if (job.getBillingIdempotencyKeys() != null && !job.getBillingIdempotencyKeys().trim().isEmpty()) {
                keys = objectMapper.readValue(
                        job.getBillingIdempotencyKeys(),
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class)
                );
            }

            keys.put(operation, idempotencyKey);
            job.setBillingIdempotencyKeys(objectMapper.writeValueAsString(keys));
        } catch (Exception e) {
            log.warn("Failed to update billing idempotency keys for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private boolean hasBillingIdempotencyKey(QuizGenerationJob job, String operation) {
        try {
            if (job.getBillingIdempotencyKeys() == null || job.getBillingIdempotencyKeys().trim().isEmpty()) {
                return false;
            }
            Map<String, String> keys = objectMapper.readValue(
                    job.getBillingIdempotencyKeys(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class)
            );
            return keys.containsKey(operation);
        } catch (Exception e) {
            log.warn("Failed to check billing idempotency keys for job {}: {}", job.getId(), e.getMessage());
            return false;
        }
    }
}
