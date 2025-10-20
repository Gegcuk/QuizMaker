package uk.gegc.quizmaker.features.quiz.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.api.dto.*;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizCommandService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizPublishingService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizVisibilityService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizRelationService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizAssemblyService;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
import uk.gegc.quizmaker.features.quiz.application.validation.QuizPublishValidator;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCompletedEvent;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationRequestedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidJobStateForCommitException;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AccessPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuizServiceImpl implements QuizService {

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
    private final QuizQueryService quizQueryService;
    private final QuizCommandService quizCommandService;
    private final QuizRelationService quizRelationService;
    private final QuizPublishingService quizPublishingService;
    private final QuizVisibilityService quizVisibilityService;
    private final QuizAssemblyService quizAssemblyService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public UUID createQuiz(String username, CreateQuizRequest request) {
        return quizCommandService.createQuiz(username, request);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuizDto> getQuizzes(Pageable pageable, QuizSearchCriteria criteria, String scope, Authentication authentication) {
        return quizQueryService.getQuizzes(pageable, criteria, scope, authentication);
    }

    @Override
    @Transactional(readOnly = true)
    public QuizDto getQuizById(UUID id, Authentication authentication) {
        return quizQueryService.getQuizById(id, authentication);
    }

    @Override
    @Transactional
    public QuizDto updateQuiz(String username, UUID id, UpdateQuizRequest request) {
        return quizCommandService.updateQuiz(username, id, request);
    }

    @Override
    @Transactional
    public void deleteQuizById(String username, UUID id) {
        quizCommandService.deleteQuizById(username, id);
    }

    @Override
    @Transactional
    public void deleteQuizzesByIds(String username, List<UUID> quizIds) {
        quizCommandService.deleteQuizzesByIds(username, quizIds);
    }

    @Override
    @Transactional
    public BulkQuizUpdateOperationResultDto bulkUpdateQuiz(String username, BulkQuizUpdateRequest request) {
        return quizCommandService.bulkUpdateQuiz(username, request);
    }

    @Override
    @Transactional
    public QuizGenerationResponse generateQuizFromDocument(String username, GenerateQuizFromDocumentRequest request) {
        // This method is now deprecated in favor of async generation
        // For backward compatibility, start an async job and return immediately
        return startQuizGeneration(username, request);
    }

    @Override
    public QuizGenerationResponse generateQuizFromUpload(String username, MultipartFile file, GenerateQuizFromUploadRequest request) {
        try {
            // Step 1: Process document completely first
            DocumentDto document = processDocumentCompletely(username, file, request);
            
            // Step 2: Verify chunks are available and sufficient
            verifyDocumentChunks(document.getId(), request);
            
            // Step 3: Generate quiz from the processed document
            GenerateQuizFromDocumentRequest quizRequest = request.toGenerateQuizFromDocumentRequest(document.getId());
            
            // Step 4: Start generation and return job ID immediately
            return startQuizGeneration(username, quizRequest);
            
        } catch (InsufficientTokensException e) {
            // Let billing exceptions propagate to be handled by GlobalExceptionHandler
            throw e;
        } catch (Exception e) {
            log.error("Failed to start quiz generation from upload for user: {}", username, e);
            throw new RuntimeException("Failed to generate quiz from upload: " + e.getMessage(), e);
        }
    }

    @Override
    public QuizGenerationResponse generateQuizFromText(String username, GenerateQuizFromTextRequest request) {
        try {
            log.info("Starting quiz generation from text for user: {}, text length: {}", username, request.text().length());
            
            // Step 1: Process text as document completely first
            DocumentDto document = processTextAsDocument(username, request);
            
            // Step 2: Verify chunks are available and sufficient
            verifyDocumentChunks(document.getId(), request);
            
            // Step 3: Generate quiz from the processed document
            GenerateQuizFromDocumentRequest quizRequest = request.toGenerateQuizFromDocumentRequest(document.getId());
            
            // Step 4: Start generation and return job ID immediately
            return startQuizGeneration(username, quizRequest);
            
        } catch (InsufficientTokensException e) {
            // Let billing exceptions propagate to be handled by GlobalExceptionHandler
            throw e;
        } catch (Exception e) {
            log.error("Failed to start quiz generation from text for user: {}", username, e);
            throw new RuntimeException("Failed to generate quiz from text: " + e.getMessage(), e);
        }
    }

    @Transactional
    public DocumentDto processDocumentCompletely(String username, MultipartFile file, GenerateQuizFromUploadRequest request) {
        try {
            log.info("Starting document processing for user: {}", username);
            
            // Process document in its own transaction
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

    @Transactional(readOnly = true)
    public void verifyDocumentChunks(UUID documentId, GenerateQuizFromUploadRequest request) {
        log.info("Verifying document chunks for document: {}", documentId);
        
        // Calculate total chunks to verify they are available
        int totalChunks = aiQuizGenerationService.calculateTotalChunks(documentId, request.toGenerateQuizFromDocumentRequest(documentId));
        
        if (totalChunks <= 0) {
            throw new RuntimeException("Document has no chunks available for quiz generation. Please try processing the document again.");
        }
        
        log.info("Document verification successful: {} chunks available", totalChunks);
    }

    @Transactional(readOnly = true)
    public void verifyDocumentChunks(UUID documentId, GenerateQuizFromTextRequest request) {
        log.info("Verifying document chunks for document: {}", documentId);
        
        // Calculate total chunks to verify they are available
        int totalChunks = aiQuizGenerationService.calculateTotalChunks(documentId, request.toGenerateQuizFromDocumentRequest(documentId));
        
        if (totalChunks <= 0) {
            throw new RuntimeException("Document has no chunks available for quiz generation. Please try processing the document again.");
        }
        
        log.info("Document verification successful: {} chunks available", totalChunks);
    }

    @Transactional
    public DocumentDto processTextAsDocument(String username, GenerateQuizFromTextRequest request) {
        try {
            log.info("Starting text processing for user: {}", username);
            
            // Convert text to UTF-8 bytes and use synthetic filename
            byte[] textBytes = request.text().getBytes(StandardCharsets.UTF_8);
            String filename = "text-input.txt";
            
            // Process text as document in its own transaction
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
        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));

        return transactionTemplate.execute(status -> {
            try {
                EstimationDto estimation = estimationService.estimateQuizGeneration(request.documentId(), request);
                long estimatedTokens = estimation.estimatedBillingTokens();

                log.info("Estimated {} billing tokens for quiz generation for user {}", estimatedTokens, username);

                String stableIdempotencyKey = "quiz:" + user.getId() + ":" + request.documentId() + ":" +
                        (request.quizScope() != null ? request.quizScope().name() : "default");

                ReservationDto reservation;
                try {
                    reservation = billingService.reserve(user.getId(), estimatedTokens, "quiz-generation", stableIdempotencyKey);
                    log.info("Reserved {} tokens for user {} (reservationId={}, key={})",
                            estimatedTokens, username, reservation.id(), stableIdempotencyKey);
                } catch (InsufficientTokensException e) {
                    throw new InsufficientTokensException(
                            "Insufficient tokens to start quiz generation. " + e.getMessage(),
                            e.getEstimatedTokens(), e.getAvailableTokens(), e.getShortfall(), e.getReservationTtl());
                }

                int totalChunks = aiQuizGenerationService.calculateTotalChunks(request.documentId(), request);
                int estimatedSeconds = aiQuizGenerationService.calculateEstimatedGenerationTime(
                        totalChunks, request.questionsPerType());

                QuizGenerationJob job;
                try {
                    job = jobService.createJob(user, request.documentId(),
                            objectMapper.writeValueAsString(request), totalChunks, estimatedSeconds);
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    log.warn("Job creation failed due to constraint violation; checking for stale job");
                    
                    // Self-healing: check if there's a stale pending job and auto-cancel it
                    if (e.getMessage() != null && e.getMessage().contains("active_user_id")) {
                        Optional<QuizGenerationJob> staleCancelled = jobService.findAndCancelStaleJobForUser(username);
                        
                        if (staleCancelled.isPresent()) {
                            log.info("Auto-cancelled stale job {}, retrying job creation", staleCancelled.get().getId());
                            // Retry job creation once after cancelling stale job
                            try {
                                job = jobService.createJob(user, request.documentId(),
                                        objectMapper.writeValueAsString(request), totalChunks, estimatedSeconds);
                                log.info("Successfully created job after auto-cancelling stale job");
                            } catch (Exception retryEx) {
                                log.error("Retry of job creation failed after auto-cancel", retryEx);
                                // Release the new reservation since we can't create the job
                                try {
                                    billingService.release(reservation.id(), "job-creation-retry-failed", "quiz-generation", null);
                                } catch (Exception releaseEx) {
                                    log.error("Failed to release reservation {} after retry failure", reservation.id(), releaseEx);
                                }
                                throw new ValidationException("User already has an active generation job. Please try again.");
                            }
                        } else {
                            // No stale job found, active job is legitimately running
                            log.info("No stale job found; user has a legitimately active job");
                            try {
                                billingService.release(reservation.id(), "job-creation-failed", "quiz-generation", null);
                            } catch (Exception releaseEx) {
                                log.error("Failed to release reservation {} after job creation failure", reservation.id(), releaseEx);
                            }
                            throw new ValidationException("User already has an active generation job. Please wait for it to complete.");
                        }
                    } else {
                        // Other constraint violation, release and rethrow
                        try {
                            billingService.release(reservation.id(), "job-creation-failed", "quiz-generation", null);
                        } catch (Exception releaseEx) {
                            log.error("Failed to release reservation {} after job creation failure", reservation.id(), releaseEx);
                        }
                        throw e;
                    }
                }

                job.setBillingReservationId(reservation.id());
                job.setReservationExpiresAt(reservation.expiresAt());
                job.setBillingEstimatedTokens(estimatedTokens);
                job.setBillingState(BillingState.RESERVED);
                job.setInputPromptTokens(estimation.estimatedLlmTokens());
                job.setEstimationVersion("v1.0");
                job.setBillingIdempotencyKeys(objectMapper.writeValueAsString(
                        Map.of("reserve", stableIdempotencyKey)));
                jobRepository.save(job);

                log.info("Updated job {} for user {} with reservation {}, starting async generation",
                        job.getId(), username, reservation.id());

                applicationEventPublisher.publishEvent(new QuizGenerationRequestedEvent(this, job.getId(), request));

                return QuizGenerationResponse.started(job.getId(), (long) estimatedSeconds);
            } catch (JsonProcessingException e) {
                throw new ValidationException("Failed to serialize request data: " + e.getMessage());
            }
        });
    }

    @Override
    @Transactional
    public QuizGenerationStatus getGenerationStatus(UUID jobId, String username) {
        return quizQueryService.getGenerationStatus(jobId, username);
    }

    @Override
    @Transactional
    public QuizDto getGeneratedQuiz(UUID jobId, String username) {

        return quizQueryService.getGeneratedQuiz(jobId, username);
    }

    @Override
    @Transactional
    public QuizGenerationStatus cancelGenerationJob(UUID jobId, String username) {
        QuizGenerationJob job = jobService.getJobByIdAndUsername(jobId, username);

        if (job.getStatus().isTerminal()) {
            throw new ValidationException("Cannot cancel job that is already in terminal state: " + job.getStatus());
        }

        jobService.cancelJob(jobId, username);

        // Refresh job from database
        job = jobRepository.findById(jobId).orElseThrow();
        
        // Set clear error message for user visibility
        job.setErrorMessage("Cancelled by user");
        jobRepository.save(job);
        
        // Handle billing based on whether AI work has started
        if (job.getBillingReservationId() != null && job.getBillingState() == BillingState.RESERVED) {
            boolean hasStartedWork = Boolean.TRUE.equals(job.getHasStartedAiCalls());
            boolean commitOnCancel = quizJobProperties.getCancellation().isCommitOnCancel();
            
            if (hasStartedWork && featureFlags.isBilling() && commitOnCancel) {
                // Work has started - commit tokens used so far
                try {
                    long actualTokens = job.getActualTokens() != null ? job.getActualTokens() : 0L;
                    long estimatedTokens = job.getBillingEstimatedTokens();
                    long minStartFeeTokens = quizJobProperties.getCancellation().getMinStartFeeTokens();
                    
                    // Commit at least minStartFeeTokens if configured, but cap at estimated
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
                        log.info("Committed {} tokens for cancelled job {} (actual tokens used: {})",
                                tokensToCommit, jobId, actualTokens);
                        
                        // Release any remainder
                        if (commitResult.releasedTokens() > 0) {
                            log.info("Released {} tokens back to user after cancel commit", commitResult.releasedTokens());
                        }
                    } else {
                        // No tokens used yet, just release
                        String releaseIdempotencyKey = "quiz:" + jobId + ":release";
                        billingService.release(
                            job.getBillingReservationId(),
                            "Job cancelled by user (no work completed)",
                            jobId.toString(),
                            releaseIdempotencyKey
                        );
                        job.setBillingState(BillingState.RELEASED);
                        job.addBillingIdempotencyKey("release", releaseIdempotencyKey);
                        log.info("Released billing reservation {} for cancelled job {} (no tokens used)",
                                job.getBillingReservationId(), jobId);
                    }
                    jobRepository.save(job);
                } catch (Exception billingError) {
                    log.error("Failed to commit/release billing for cancelled job {}", jobId, billingError);
                    job.setLastBillingError("{\"error\":\"Failed to process billing on cancel: " +
                            billingError.getMessage() + "\"}");
                    jobRepository.save(job);
                }
            } else {
                // No work started - just release the reservation
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
        }
        
        return QuizGenerationStatus.fromEntity(job, featureFlags.isBilling());
    }

    @Override
    @Transactional
    public Page<QuizGenerationStatus> getGenerationJobs(String username, Pageable pageable) {
        return quizQueryService.getGenerationJobs(username, pageable);
    }

    @Override
    @Transactional
    public QuizGenerationJobService.JobStatistics getGenerationJobStatistics(String username) {
        return quizQueryService.getGenerationJobStatistics(username);
    }

    @EventListener
    @Async("generalTaskExecutor")
    @Transactional
    public void handleQuizGenerationCompleted(QuizGenerationCompletedEvent event) {
        try {
            createQuizCollectionFromGeneratedQuestions(
                    event.getJobId(),
                    event.getChunkQuestions(),
                    event.getOriginalRequest()
            );
        } catch (Exception e) {
            // Log error and update job status
            log.error("Failed to create quiz collection for job {}", event.getJobId(), e);
            try {
                QuizGenerationJob job = jobRepository.findById(event.getJobId()).orElse(null);
                if (job != null) {
                    job.markFailed("Quiz creation failed: " + e.getMessage());
                    
                            // Release billing reservation if it exists
                            if (job.getBillingReservationId() != null && job.getBillingState() == BillingState.RESERVED) {
                                try {
                                    String releaseIdempotencyKey = "quiz:" + event.getJobId() + ":release";
                                    internalBillingService.release(
                                        job.getBillingReservationId(),
                                        "Quiz creation failed: " + e.getMessage(),
                                        event.getJobId().toString(),
                                        releaseIdempotencyKey
                                    );
                                    job.setBillingState(BillingState.RELEASED);
                                    // Store release idempotency key for audit trail
                                    job.addBillingIdempotencyKey("release", releaseIdempotencyKey);
                                    log.info("Released billing reservation {} for failed quiz creation job {}", job.getBillingReservationId(), event.getJobId());
                                } catch (Exception billingError) {
                                    log.error("Failed to release billing reservation for quiz creation failure job {}", event.getJobId(), billingError);
                                    // Store billing error but don't fail the job update
                                    job.setLastBillingError("{\"error\":\"Failed to release reservation: " + billingError.getMessage() + "\"}");
                                }
                            }
                    
                    jobRepository.save(job);
                }
            } catch (Exception saveError) {
                log.error("Failed to update job status after quiz creation failure for job {}", event.getJobId(), saveError);
            }
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

            // Guard against event/finish race: if job is already in terminal state (cancelled/failed), skip
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

            // Get category and tags
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

            // Calculate total questions
            int totalQuestions = allQuestions.size();

            // Mark job as completed with consolidated quiz ID and total questions
            // This is now the ONLY place where the job is marked as completed
            job.markCompleted(consolidatedQuiz.getId(), totalQuestions);
            jobRepository.save(job);

            // Commit tokens after successful quiz creation
            commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);

            // TODO: Create document quiz group entity to track relationships
            // This would require additional database schema for quiz relationships

        } catch (Exception e) {
            // If quiz creation fails, mark job as failed
            try {
                QuizGenerationJob job = jobRepository.findById(jobId).orElse(null);
                if (job != null) {
                    job.markFailed("Failed to create quiz collection: " + e.getMessage());
                    jobRepository.save(job);
                }
            } catch (Exception saveError) {
                System.err.println("Failed to update job status after quiz creation failure: " + saveError.getMessage());
            }
            throw new RuntimeException("Failed to create quiz collection from generated questions", e);
        }
    }

    @Override
    @Transactional
    public void addQuestionToQuiz(String username, UUID quizId, UUID questionId) {
        quizRelationService.addQuestionToQuiz(username, quizId, questionId);
    }

    @Override
    @Transactional
    public void removeQuestionFromQuiz(String username, UUID quizId, UUID questionId) {
        quizRelationService.removeQuestionFromQuiz(username, quizId, questionId);
    }

    @Override
    @Transactional
    public void addTagToQuiz(String username, UUID quizId, UUID tagId) {
        quizRelationService.addTagToQuiz(username, quizId, tagId);
    }

    @Override
    @Transactional
    public void removeTagFromQuiz(String username, UUID quizId, UUID tagId) {
        quizRelationService.removeTagFromQuiz(username, quizId, tagId);
    }

    @Override
    @Transactional
    public void changeCategory(String username, UUID quizId, UUID categoryId) {
        quizRelationService.changeCategory(username, quizId, categoryId);
    }

    @Override
    @Transactional
    public QuizDto setVisibility(String name, UUID quizId, Visibility visibility) {
        return quizVisibilityService.setVisibility(name, quizId, visibility);
    }

    @Override
    @Transactional
    public QuizDto setStatus(String username, UUID quizId, QuizStatus status) {
        return quizPublishingService.setStatus(username, quizId, status);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuizDto> getPublicQuizzes(Pageable pageable) {
        return quizQueryService.getPublicQuizzes(pageable);
    }


    void commitTokensForSuccessfulGeneration(QuizGenerationJob job, List<Question> allQuestions, 
                                                   GenerateQuizFromDocumentRequest originalRequest) {
        String jobId = job.getId().toString();
        String correlationId = "commit-" + jobId + "-" + System.currentTimeMillis();
        
        try {
            log.info("Starting token commit for job {} [correlationId={}]", jobId, correlationId);
            
            // Re-load job with pessimistic lock to prevent race conditions
            QuizGenerationJob lockedJob = jobRepository.findByIdForUpdate(job.getId())
                    .orElseThrow(() -> new IllegalStateException("Job " + jobId + " not found during commit"));

            if (lockedJob.getBillingReservationId() == null) {
                log.warn("Job {} has no reservation ID, cannot commit [correlationId={}]", jobId, correlationId);
                return;
            }
            
            // Check if already committed (idempotent)
            if (!lockedJob.getBillingState().isReserved()) {
                if (lockedJob.getBillingState() == BillingState.COMMITTED) {
                    log.info("Job {} already committed, returning success [correlationId={}]", jobId, correlationId);
                    return;
                }
                throw new InvalidJobStateForCommitException(jobId, lockedJob.getBillingState());
            }

            // Check if commit already exists in idempotency keys
            String commitIdempotencyKey = "quiz:" + lockedJob.getId() + ":commit";
            if (hasBillingIdempotencyKey(lockedJob, "commit")) {
                log.info("Job {} already has commit idempotency key, returning success [correlationId={}]", jobId, correlationId);
                return;
            }

            // Enforce ordering: only allow commit when job is COMPLETED and reservation is ACTIVE
            if (!lockedJob.getStatus().isSuccess()) {
                throw new InvalidJobStateForCommitException(jobId, lockedJob.getBillingState(), 
                        "Job must be in COMPLETED status to commit tokens. Current status: " + lockedJob.getStatus());
            }

            // Check if reservation has expired
            if (lockedJob.isReservationExpired()) {
                log.warn("Reservation for job {} has expired, skipping commit [correlationId={}]", jobId, correlationId);
                return;
            }

            // Get input prompt tokens from job (stored during estimation)
            long inputPromptTokens = lockedJob.getInputPromptTokens() != null ? lockedJob.getInputPromptTokens() : 0L;
            
            // Compute commit from persisted output using shared EOT table; BigDecimal scale 0, RoundingMode.CEILING
            long actualBillingTokens = estimationService.computeActualBillingTokens(
                    allQuestions, 
                    originalRequest.difficulty(), 
                    inputPromptTokens
            );

            // Cap actual tokens at reserved amount to avoid overcharging users.
            // Users aren't responsible for our estimation accuracy
            long reservedTokens = lockedJob.getBillingEstimatedTokens();
            long tokensToCommit = Math.min(actualBillingTokens, reservedTokens);
            boolean wasCapped = actualBillingTokens > reservedTokens;
            
            if (wasCapped) {
                long underestimationDelta = actualBillingTokens - reservedTokens;
                log.warn("BILLING_UNDERESTIMATION: Actual tokens ({}) exceed reserved tokens ({}) for job {} (reservationId: {}). " +
                        "Delta: {}, Committing reserved amount to avoid overcharging user.", 
                        actualBillingTokens, reservedTokens, jobId, lockedJob.getBillingReservationId(), underestimationDelta);
                
                // TODO: Emit metrics when metrics system is available
                // meterRegistry.counter("billing.underestimation.count").increment();
                // meterRegistry.gauge("billing.underestimation.delta", underestimationDelta);
            }

            log.info("Committing {} billing tokens for job {} (actual: {}, reserved: {}, inputPromptTokens: {}, questions: {}) [correlationId={}]", 
                    tokensToCommit, jobId, actualBillingTokens, reservedTokens, inputPromptTokens, allQuestions.size(), correlationId);

            // Write both COMMIT and RELEASE ledger rows with reservationId, jobId, idempotencyKey, and reason
            var commitResult = internalBillingService.commit(
                    lockedJob.getBillingReservationId(),
                    tokensToCommit,
                    "quiz-generation",
                    commitIdempotencyKey
            );

            // Guard: If billing service doesn't auto-release remainder when commit < reserved, do it explicitly
            long reserved = lockedJob.getBillingEstimatedTokens();
            long remainder = Math.max(0, reserved - tokensToCommit);
            if (remainder > 0 && (commitResult == null || commitResult.releasedTokens() == 0)) {
                try {
                    log.info("Explicitly releasing remainder {} tokens for job {} [correlationId={}]", 
                            remainder, jobId, correlationId);
                    internalBillingService.release(lockedJob.getBillingReservationId(), "commit-remainder", "quiz-generation", null);
                } catch (Exception ex) {
                    log.warn("Failed to explicitly release remainder {} for reservation {}: {} [correlationId={}]", 
                            remainder, lockedJob.getBillingReservationId(), ex.getMessage(), correlationId);
                }
            }

            // Update job billing fields
            lockedJob.setActualTokens(actualBillingTokens);
            lockedJob.setBillingCommittedTokens(tokensToCommit);
            lockedJob.setWasCappedAtReserved(wasCapped);
            lockedJob.setBillingState(BillingState.COMMITTED);
            
            // Update idempotency keys JSON for audit trail
            updateBillingIdempotencyKeys(lockedJob, "commit", commitIdempotencyKey);
            
            // Clear any previous billing errors
            lockedJob.setLastBillingError(null);
            
            jobRepository.save(lockedJob);

            log.info("Successfully committed {} tokens for job {} (actual: {}, remainder released: {}, wasCappedAtReserved: {}) [correlationId={}]", 
                    tokensToCommit, jobId, actualBillingTokens, 
                    commitResult != null ? commitResult.releasedTokens() : 0L, wasCapped, correlationId);

        } catch (InvalidJobStateForCommitException e) {
            // These are business rule violations that should be logged but not fail the quiz creation
            log.error("Business rule violation during commit for job {} [correlationId={}]: {}", 
                    jobId, correlationId, e.getMessage());
            
            // Store billing error for support with structured format
            storeBillingError(job, e, correlationId);
            
        } catch (Exception e) {
            log.error("Unexpected error during commit for job {} [correlationId={}]", jobId, correlationId, e);
            
            // Store billing error for support
            storeBillingError(job, e, correlationId);
            
            // Don't re-throw - quiz creation was successful, billing failure shouldn't fail the whole operation
            // The reservation will be released by the sweeper when it expires
        }
    }
    
    /**
     * Store billing error in structured JSON format for support purposes.
     */
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

    /**
     * Record for structured billing error information
     */
    private record BillingError(String error, String errorType, java.time.Instant timestamp, String correlationId) {}

    /**
     * Update billing idempotency keys JSON field
     */
    private void updateBillingIdempotencyKeys(QuizGenerationJob job, String operation, String idempotencyKey) {
        try {
            Map<String, String> keys = new HashMap<>();
            if (job.getBillingIdempotencyKeys() != null && !job.getBillingIdempotencyKeys().trim().isEmpty()) {
                // Parse existing keys
                keys = objectMapper.readValue(job.getBillingIdempotencyKeys(), 
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
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
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
            return keys.containsKey(operation);
        } catch (Exception e) {
            log.warn("Failed to check billing idempotency keys for job {}: {}", job.getId(), e.getMessage());
            return false;
        }
    }
}
