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
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.GenerationTariff;
import uk.gegc.quizmaker.features.billing.application.GenerationTariffService;
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
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFinalizationClaim;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationIdempotencyService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationRequestCanonicalizer;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationRequestedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationOperationType;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationFinalizationState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationOperation;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
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
import java.time.LocalDateTime;
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
    private final GenerationTariffService generationTariffService;
    private final FeatureFlags featureFlags;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final QuizAssemblyService quizAssemblyService;
    private final QuizGenerationIdempotencyService idempotencyService;
    private final QuizGenerationRequestCanonicalizer requestCanonicalizer;
    private final QuizJobProperties quizJobProperties;

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
            SourceResolution source = resolveOrProcessUploadSource(user, file, request, operation);
            if (source.replayResponse() != null) {
                return source.replayResponse();
            }
            return startQuizGenerationForOperation(
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
            SourceResolution source = resolveOrProcessTextSource(user, request, operation);
            if (source.replayResponse() != null) {
                return source.replayResponse();
            }
            return startQuizGenerationForOperation(
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
        return startQuizGenerationForOperation(user, request, operation.getId());
    }

    private QuizGenerationResponse startQuizGenerationForOperation(
            User user,
            GenerateQuizFromDocumentRequest request,
            UUID operationId
    ) {
        String ownerUsername = user.getUsername();
        return transactionTemplate.execute(status -> {
            QuizGenerationOperation operation = idempotencyService.lockForGeneration(operationId, user.getId());
            if (operation.hasStartedJob()) {
                return existingGenerationResponse(operation);
            }

            try {
                // The operation is owned by this user; the source document must be as well.
                documentProcessingService.getDocumentById(request.documentId(), ownerUsername);
                EstimationDto estimation = estimationService.estimateQuizGeneration(request.documentId(), request);
                long estimatedTokens = estimation.estimatedBillingTokens();

                log.info("Estimated {} billing tokens for quiz generation for user {}", estimatedTokens, ownerUsername);

                String reservationIdempotencyKey = "quiz-generation:reserve:" + operation.getId();

                ReservationDto reservation;
                try {
                    reservation = internalBillingService.reserve(
                            user.getId(), estimatedTokens, "quiz-generation", reservationIdempotencyKey);
                    log.info("Reserved {} tokens for user {} (reservationId={})",
                            estimatedTokens, ownerUsername, reservation.id());
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
                        Optional<QuizGenerationJob> staleCancelled = jobService.findAndCancelStaleJobForUser(ownerUsername);
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
                captureGenerationTariff(job, estimation);
                job.setBillingIdempotencyKeys(objectMapper.writeValueAsString(Map.of("reserve", reservationIdempotencyKey)));
                jobRepository.save(job);
                internalBillingService.attachReservationToJob(user.getId(), reservation.id(), job.getId());
                idempotencyService.linkStartedGeneration(operation, job.getId(), reservation.id(), estimatedSeconds);

                log.info("Updated job {} for user {} with reservation {}, starting async generation",
                        job.getId(), ownerUsername, reservation.id());

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

    private void captureGenerationTariff(QuizGenerationJob job, EstimationDto estimation) {
        if (estimation.tariffVersion() == null
                || estimation.tariffVersion().isBlank()
                || estimation.billingBaseTokens() == null
                || estimation.billingTokensPerThousandCharacters() == null
                || estimation.quotedContentCharacters() == null
                || estimation.quotedQuestionTypeCount() == null) {
            log.warn("Generation quote for job {} has no tariff snapshot; preserving legacy settlement compatibility",
                    job.getId());
            return;
        }

        job.captureGenerationTariff(
                estimation.tariffVersion(),
                estimation.billingBaseTokens(),
                estimation.billingTokensPerThousandCharacters(),
                estimation.quotedContentCharacters(),
                estimation.quotedQuestionTypeCount()
        );
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
            DocumentDto document = processDocumentCompletely(user.getUsername(), file, request);
            verifyDocumentChunks(document.getId(), request);
            idempotencyService.attachSourceDocument(operation.getId(), user.getId(), document.getId());
            return SourceResolution.document(document.getId());
        } catch (RuntimeException exception) {
            idempotencyService.markSourceRetryable(operation.getId(), user.getId());
            throw exception;
        }
    }

    private SourceResolution resolveOrProcessTextSource(
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
            DocumentDto document = processTextAsDocument(user.getUsername(), request);
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
        QuizGenerationJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz generation job not found with ID: " + jobId));

        if (!job.getUser().getUsername().equals(username)) {
            throw new ValidationException("Access denied: job does not belong to user");
        }

        if (job.getStatus().isTerminal()) {
            throw new ValidationException("Cannot cancel job that is already in terminal state: " + job.getStatus());
        }

        job.setStatus(uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus.CANCELLED);
        job.setCompletedAt(LocalDateTime.now());
        job.markFinalizationCancelled(LocalDateTime.now());
        job.setErrorMessage("Cancelled by user");
        jobRepository.save(job);

        if (job.getBillingReservationId() != null && job.getBillingState() == BillingState.RESERVED) {
            // A cancelled job does not deliver a successful quiz. V1 charges
            // only valid questions accepted into a successful quiz, so the full
            // reservation is released even if an AI call already started.
            handleCancellationRelease(jobId, job);
        }

        return QuizGenerationStatus.fromEntity(job, featureFlags.isBilling());
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
            job.setLastBillingError("{\"reason\":\"Cancellation release pending\"}");
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
        QuizGenerationJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));

        if (job.isTerminal()) {
            log.info("Job {} already in terminal state {}, skipping quiz creation", jobId, job.getStatus());
            return;
        }

        if (job.getFinalizationState() == QuizGenerationFinalizationState.NOT_STARTED) {
            // Compatibility for legacy in-process callers. Production event handling
            // claims finalization in its own transaction before reaching this method.
            job.beginFinalization(LocalDateTime.now());
        }
        if (job.getFinalizationState() != QuizGenerationFinalizationState.FINALIZING) {
            throw new IllegalStateException("Job " + jobId + " is not eligible for quiz finalization");
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

        // The job completion, quizzes, and ledger commit share this transaction.
        // Any settlement exception rolls all of them back before content is visible.
        job.markCompleted(consolidatedQuiz.getId(), allQuestions.size());
        commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
        job.markFinalizationSucceeded(LocalDateTime.now());
        jobRepository.save(job);
    }

    @Override
    public void commitTokensForSuccessfulGeneration(QuizGenerationJob job, List<Question> allQuestions,
                                                    GenerateQuizFromDocumentRequest originalRequest) {
        String jobId = job.getId().toString();
        String correlationId = "commit-" + jobId + "-" + System.currentTimeMillis();

        log.info("Starting token commit for job {} [correlationId={}]", jobId, correlationId);

        QuizGenerationJob lockedJob = jobRepository.findByIdForUpdate(job.getId())
                .orElseThrow(() -> new IllegalStateException("Job " + jobId + " not found during commit"));

        if (lockedJob.getBillingReservationId() == null) {
            if (lockedJob.getBillingState() == BillingState.NONE) {
                log.info("Job {} predates billing reservation enforcement; no settlement is required [correlationId={}]",
                        jobId, correlationId);
                return;
            }
            throw new InvalidJobStateForCommitException(jobId, lockedJob.getBillingState(),
                    "A reserved billing state requires a reservation ID");
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
            throw new InvalidJobStateForCommitException(jobId, lockedJob.getBillingState(),
                    "Reservation expired before the generation could be finalized");
        }

        long reservedTokens = lockedJob.getBillingEstimatedTokens();
        int acceptedQuestionTypeCount = allQuestions == null
                    ? 0
                    : (int) allQuestions.stream()
                            .map(Question::getType)
                            .filter(Objects::nonNull)
                            .distinct()
                            .count();
        boolean usesTariffSnapshot = lockedJob.hasGenerationTariffSnapshot();
        long actualBillingTokens;
        long tokensToCommit;
        boolean wasCapped;

        if (usesTariffSnapshot) {
                GenerationTariff tariff = generationTariffService.fromSnapshot(
                        lockedJob.getBillingTariffVersion(),
                        lockedJob.getBillingBaseTokens(),
                        lockedJob.getBillingTokensPerThousandCharacters()
                );
                actualBillingTokens = tariff.quoteForContent(
                        lockedJob.getBillingQuotedContentCharacters(),
                        acceptedQuestionTypeCount
                );
                tokensToCommit = tariff.settlementForAcceptedQuestionTypes(
                        lockedJob.getBillingQuotedContentCharacters(),
                        acceptedQuestionTypeCount,
                        reservedTokens
                );
                wasCapped = actualBillingTokens > reservedTokens;
                lockedJob.setBillingAcceptedQuestionTypeCount(acceptedQuestionTypeCount);
        } else {
                long inputPromptTokens = lockedJob.getInputPromptTokens() != null ? lockedJob.getInputPromptTokens() : 0L;
                actualBillingTokens = estimationService.computeActualBillingTokens(
                        allQuestions,
                        originalRequest.difficulty(),
                        inputPromptTokens
                );
                tokensToCommit = Math.min(actualBillingTokens, reservedTokens);
                wasCapped = actualBillingTokens > reservedTokens;
        }

        if (wasCapped) {
                log.warn("Generation settlement for job {} reached its stored maximum quote: actual {}, quote {}",
                        jobId, actualBillingTokens, reservedTokens);
        }

        if (tokensToCommit == 0L && usesTariffSnapshot) {
            releaseZeroAcceptedQuestionTypeQuote(lockedJob, acceptedQuestionTypeCount, correlationId);
            return;
        }

        log.info("Committing {} billing tokens for job {} (actual: {}, quote: {}, acceptedQuestionTypes: {}, tariffVersion: {}) [correlationId={}]",
                    tokensToCommit, jobId, actualBillingTokens, reservedTokens, acceptedQuestionTypeCount,
                    lockedJob.getBillingTariffVersion(), correlationId);

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
                log.warn("Failed to explicitly release remainder {} for reservation {} [correlationId={}]",
                        remainder, lockedJob.getBillingReservationId(), correlationId, ex);
            }
        }

        if (!usesTariffSnapshot) {
                // Historical rows retain the original heuristic settlement model.
                lockedJob.setActualTokens(actualBillingTokens);
        }
        lockedJob.setBillingCommittedTokens(tokensToCommit);
        lockedJob.setWasCappedAtReserved(wasCapped);
        lockedJob.setBillingState(BillingState.COMMITTED);

        updateBillingIdempotencyKeys(lockedJob, "commit", commitIdempotencyKey);

        lockedJob.setLastBillingError(null);

        jobRepository.save(lockedJob);

        log.info("Successfully committed {} tokens for job {} (actual: {}, remainder released: {}, cappedAtQuote: {}) [correlationId={}]",
                    tokensToCommit, jobId, actualBillingTokens,
                commitResult != null ? commitResult.releasedTokens() : 0L, wasCapped, correlationId);
    }

    private void releaseZeroAcceptedQuestionTypeQuote(
            QuizGenerationJob lockedJob,
            int acceptedQuestionTypeCount,
            String correlationId
    ) {
        String releaseIdempotencyKey = "quiz:" + lockedJob.getId() + ":release-zero-accepted-types";
        internalBillingService.release(
                lockedJob.getBillingReservationId(),
                "zero-accepted-question-types",
                "quiz-generation",
                releaseIdempotencyKey
        );
        lockedJob.setBillingAcceptedQuestionTypeCount(acceptedQuestionTypeCount);
        lockedJob.setBillingCommittedTokens(0L);
        lockedJob.setBillingState(BillingState.RELEASED);
        lockedJob.setWasCappedAtReserved(false);
        updateBillingIdempotencyKeys(lockedJob, "release-zero-accepted-types", releaseIdempotencyKey);
        lockedJob.setLastBillingError(null);
        jobRepository.save(lockedJob);
        log.info("Released generation quote for job {} because no accepted question type was generated [correlationId={}]",
                lockedJob.getId(), correlationId);
    }

    @Override
    public QuizGenerationFinalizationClaim claimQuizGenerationFinalization(UUID jobId) {
        QuizGenerationFinalizationClaim claim = transactionTemplate.execute(status -> {
            QuizGenerationJob job = jobRepository.findByIdForUpdate(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));

            if (job.getStatus().isTerminal()) {
                if (job.getStatus() == uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus.CANCELLED
                        && job.getFinalizationState() != QuizGenerationFinalizationState.CANCELLED) {
                    job.markFinalizationCancelled(LocalDateTime.now());
                    jobRepository.save(job);
                }
                return job.getFinalizationState() == QuizGenerationFinalizationState.SUCCEEDED
                        ? QuizGenerationFinalizationClaim.ALREADY_FINALIZED
                        : QuizGenerationFinalizationClaim.TERMINAL;
            }

            if (job.getFinalizationState() == QuizGenerationFinalizationState.FINALIZING) {
                return QuizGenerationFinalizationClaim.IN_PROGRESS;
            }
            if (job.getFinalizationState() != QuizGenerationFinalizationState.NOT_STARTED) {
                return QuizGenerationFinalizationClaim.TERMINAL;
            }

            job.beginFinalization(LocalDateTime.now());
            jobRepository.save(job);
            return QuizGenerationFinalizationClaim.CLAIMED;
        });
        return Objects.requireNonNull(claim, "Finalization claim transaction returned no result");
    }

    @Override
    public void handleQuizGenerationFinalizationFailure(UUID jobId) {
        FailureReleaseCandidate candidate = transactionTemplate.execute(status -> markFinalizationFailed(jobId));
        if (candidate == null || candidate.reservationId() == null) {
            return;
        }

        String releaseIdempotencyKey = "quiz:" + jobId + ":finalization-release";
        try {
            internalBillingService.release(
                    candidate.reservationId(),
                    "generation-finalization-failed",
                    "quiz-generation",
                    releaseIdempotencyKey
            );
            transactionTemplate.executeWithoutResult(status -> markFinalizationReservationReleased(jobId, releaseIdempotencyKey));
            log.info("Released reservation for failed quiz-generation finalization {}", jobId);
        } catch (Exception exception) {
            log.error("Reservation release is pending for failed quiz-generation finalization {}", jobId, exception);
            transactionTemplate.executeWithoutResult(status -> markFinalizationReleasePending(jobId));
        }
    }

    @Override
    public int recoverStalledQuizGenerationFinalizations() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(
                quizJobProperties.getFinalization().getRecoveryGraceSeconds());
        List<UUID> stalledJobIds = jobRepository
                .findByFinalizationStateAndFinalizationStartedAtBefore(QuizGenerationFinalizationState.FINALIZING, cutoff)
                .stream()
                .map(QuizGenerationJob::getId)
                .toList();
        List<UUID> releasePendingJobIds = jobRepository
                .findByFinalizationStateAndBillingState(QuizGenerationFinalizationState.FAILED, BillingState.RESERVED)
                .stream()
                .map(QuizGenerationJob::getId)
                .toList();

        stalledJobIds.forEach(this::handleQuizGenerationFinalizationFailure);
        releasePendingJobIds.stream()
                .filter(jobId -> !stalledJobIds.contains(jobId))
                .forEach(this::handleQuizGenerationFinalizationFailure);
        return stalledJobIds.size() + (int) releasePendingJobIds.stream()
                .filter(jobId -> !stalledJobIds.contains(jobId))
                .count();
    }

    private FailureReleaseCandidate markFinalizationFailed(UUID jobId) {
        QuizGenerationJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
        if (job.getFinalizationState() == QuizGenerationFinalizationState.SUCCEEDED
                || job.getStatus() == uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus.COMPLETED) {
            return FailureReleaseCandidate.none();
        }
        if (job.getStatus() == uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus.CANCELLED) {
            job.markFinalizationCancelled(LocalDateTime.now());
            jobRepository.save(job);
            return FailureReleaseCandidate.none();
        }

        job.markFailed("Generation could not be finalized. No quiz was created; any reserved balance will be released automatically.");
        job.markFinalizationFailed("Finalization failed; reservation release is pending or complete.", LocalDateTime.now());
        jobRepository.save(job);

        return job.getBillingReservationId() != null && job.getBillingState() == BillingState.RESERVED
                ? new FailureReleaseCandidate(job.getBillingReservationId())
                : FailureReleaseCandidate.none();
    }

    private void markFinalizationReservationReleased(UUID jobId, String releaseIdempotencyKey) {
        QuizGenerationJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
        if (job.getBillingState() == BillingState.RESERVED) {
            job.setBillingState(BillingState.RELEASED);
            job.addBillingIdempotencyKey("finalization-release", releaseIdempotencyKey);
            job.setLastBillingError(null);
            jobRepository.save(job);
        }
    }

    private void markFinalizationReleasePending(UUID jobId) {
        QuizGenerationJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
        if (job.getBillingState() == BillingState.RESERVED) {
            job.setLastBillingError("{\"reason\":\"Finalization release pending\"}");
            jobRepository.save(job);
        }
    }

    private record FailureReleaseCandidate(UUID reservationId) {
        static FailureReleaseCandidate none() {
            return new FailureReleaseCandidate(null);
        }
    }

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
