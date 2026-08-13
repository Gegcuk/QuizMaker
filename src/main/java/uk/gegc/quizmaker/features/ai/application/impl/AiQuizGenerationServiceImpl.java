package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.ai.application.GenerationCoveragePolicy;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.ProviderUsageObservation;
import uk.gegc.quizmaker.features.ai.application.ProviderUsagePersistenceException;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionResponseParser;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.application.QuestionContentShuffler;
import uk.gegc.quizmaker.features.question.application.QuestionContentValidationService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCompletedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.application.generation.ProviderUsageService;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiQuizGenerationServiceImpl implements AiQuizGenerationService {

    private final ChatClient chatClient;
    private final DocumentRepository documentRepository;
    private final PromptTemplateService promptTemplateService;
    private final QuestionResponseParser questionResponseParser;
    private final QuizGenerationJobRepository jobRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AiRateLimitConfig rateLimitConfig;
    private final InternalBillingService internalBillingService;
    private final TransactionTemplate transactionTemplate;
    private final StructuredAiClient structuredAiClient;
    private final QuestionContentShuffler questionContentShuffler;
    private final QuestionContentValidationService questionContentValidationService;
    private final ProviderUsageService providerUsageService;

    // In-memory tracking for generation progress (will be replaced with database in Phase 2)
    private final Map<UUID, GenerationProgress> generationProgress = new ConcurrentHashMap<>();

    @Override
    public void generateQuizFromDocumentAsync(UUID jobId, GenerateQuizFromDocumentRequest request) {
        QuizGenerationJob job = transactionTemplate.execute(status -> {
            QuizGenerationJob managedJob = jobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
            managedJob.setStatus(GenerationStatus.PROCESSING);
            jobRepository.save(managedJob);
            // initialize lazy relationships we will need outside the transaction
            managedJob.getUser().getId();
            managedJob.getUser().getUsername();
            return managedJob;
        });

        if (job == null) {
            return;
        }

        generateQuizFromDocumentAsync(job, request);
    }

    @Override
    public void generateQuizFromDocumentAsync(QuizGenerationJob job, GenerateQuizFromDocumentRequest request) {
        UUID jobId = job.getId();
        Instant startTime = Instant.now();
        log.info("Starting quiz generation for job {} with document {}", jobId, request.documentId());
        log.info("Thread: {}, Transaction: {}", Thread.currentThread().getName(), 
                org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive() ? "ACTIVE" : "NONE");

        try {
            // Get the job from database and update status in a short transaction
            log.info("Attempting to find job {} in database from thread {}", jobId, Thread.currentThread().getName());
            
            QuizGenerationJob freshJob = updateJobStatusToProcessing(jobId);

            // Initialize progress tracking
            GenerationProgress progress = new GenerationProgress();
            generationProgress.put(jobId, progress);

            // Validate document
            validateDocumentForGeneration(request.documentId(), freshJob.getUser().getUsername());

            // Get document and chunks
            Document document = documentRepository.findByIdWithChunksAndUser(request.documentId())
                    .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + request.documentId()));

            List<DocumentChunk> chunks = getChunksForScope(document, request);
            progress.setTotalChunks(chunks.size());

            Map<QuestionType, Integer> requestedByType = GenerationCoveragePolicy.expectedCounts(
                    chunks.size(), request.questionsPerType());

            // Compute total tasks: each chunk × number of requested question types
            int totalTasks = computeTotalTasks(chunks.size(), request.questionsPerType());
            progress.setTotalTasks(totalTasks);

            // Update job with total chunks and total tasks in a short transaction
            updateJobTotalChunksAndTasks(jobId, chunks.size(), totalTasks);

            // Count actual requested types (count > 0)
            long requestedTypeCount = request.questionsPerType().values().stream()
                    .filter(count -> count != null && count > 0)
                    .count();
            
            log.info("Processing {} chunks for document {} with {} total tasks ({} question types requested)", 
                    chunks.size(), request.documentId(), totalTasks, requestedTypeCount);

            // Process chunks asynchronously
            List<CompletableFuture<List<Question>>> chunkFutures = chunks.stream()
                    .map(chunk -> {
                        // Check for cancellation before scheduling chunk processing
                        if (isJobCancelled(jobId)) {
                            log.info("Job {} cancelled before chunk processing", jobId);
                            return CompletableFuture.completedFuture(List.<Question>of());
                        }
                        return generateQuestionsFromChunkWithJob(
                                chunk,
                                request.questionsPerType(),
                                request.difficulty(),
                                jobId,
                                request.language()
                        );
                    })
                    .toList();

            // Collect all generated questions with enhanced tracking
            List<Question> allQuestions = new ArrayList<>();
            Map<Integer, List<Question>> chunkQuestions = new HashMap<>();

            int processedChunks = 0;

            // Collect results from all chunks
            for (int chunkIndex = 0; chunkIndex < chunkFutures.size(); chunkIndex++) {
                try {
                    CompletableFuture<List<Question>> future = chunkFutures.get(chunkIndex);
                    List<Question> chunkQuestionsList = future.get();
                    
                    if (!chunkQuestionsList.isEmpty()) {
                        allQuestions.addAll(chunkQuestionsList);
                        chunkQuestions.put(chunkIndex, chunkQuestionsList);
                    }
                    
                    processedChunks++;

                    // Update chunk progress atomically. Task counters remain authoritative for the percentage.
                    updateJobChunkProgressSafely(jobId, processedChunks, 
                        String.format("Processing chunk %d/%d", processedChunks, chunks.size()));

                } catch (Exception e) {
                    propagateProviderUsagePersistenceFailure(e);
                    log.error("Error processing chunk {} for job {}", chunkIndex, jobId, e);
                    progress.addError("Chunk " + chunkIndex + " processing failed: " + e.getMessage());

                    // Update chunk progress with error atomically
                    updateJobChunkProgressSafely(jobId, processedChunks, 
                        String.format("Error in chunk %d", chunkIndex));
                }
            }

            if (allQuestions.isEmpty()) {
                throw new AiServiceException("Failed to generate any questions for job " + jobId + ". All generation attempts failed.");
            }

            GenerationCoveragePolicy.Decision initialCoverage = GenerationCoveragePolicy.evaluate(
                    requestedByType,
                    request.difficulty(),
                    chunkQuestions
            );
            Map<QuestionType, Integer> generatedByType = new EnumMap<>(QuestionType.class);
            generatedByType.putAll(initialCoverage.acceptedByType());
            Map<QuestionType, Integer> missingTypes = new EnumMap<>(QuestionType.class);
            missingTypes.putAll(initialCoverage.missingByType());
            
            if (!missingTypes.isEmpty()) {
                log.info("Missing question types detected for job {}: {}. Attempting redistribution...", 
                        jobId, missingTypes);
                
                // Update job status to show redistribution phase (atomic, doesn't touch task-based progress)
                updateJobChunkProgressSafely(jobId, chunks.size(), 
                    String.format("Analyzing coverage: %d question types need redistribution", missingTypes.size()));
                
                // Attempt to generate missing types from successful chunks
                redistributeMissingQuestions(
                        chunks,
                        missingTypes,
                        request.difficulty(),
                        chunkQuestions,
                        allQuestions,
                        generatedByType,
                        jobId,
                        request.language()
                );
                        
            }

            GenerationCoveragePolicy.Decision coverage = GenerationCoveragePolicy.evaluate(
                    requestedByType,
                    request.difficulty(),
                    chunkQuestions
            );
            String coverageSummary = formatCoverageSummary(
                    coverage.acceptedByType(), coverage.expectedByType());

            log.info(
                    "Generation coverage reconciled for job {}: requested={}, accepted={}, missing={}, discarded={}, outcome={}",
                    jobId,
                    coverage.requestedTotal(),
                    coverage.acceptedTotal(),
                    coverage.requestedTotal() - coverage.acceptedTotal(),
                    coverage.discardedTotal(),
                    coverage.successful() ? (coverage.partial() ? "partial" : "complete") : "failed"
            );

            if (!coverage.successful()) {
                updateJobChunkProgressSafely(
                        jobId,
                        chunks.size(),
                        String.format("Generation failed coverage: %d/%d accepted",
                                coverage.acceptedTotal(), coverage.requestedTotal())
                );
                throw new AiServiceException(String.format(
                        "Generated question coverage %d/%d does not exceed the required %d%% threshold",
                        coverage.acceptedTotal(),
                        coverage.requestedTotal(),
                        GenerationCoveragePolicy.SUCCESS_THRESHOLD_PERCENT
                ));
            }

            String outcome = coverage.partial()
                    ? "Generation completed with partial coverage: "
                    : "Generation completed successfully: ";
            updateJobChunkProgressSafely(jobId, chunks.size(), outcome + coverageSummary);

            Map<Integer, List<Question>> acceptedChunkQuestions = coverage.acceptedByChunk();
            List<Question> acceptedQuestions = coverage.acceptedQuestions();

            log.info("Quiz generation completed for job {} in {} seconds. Generated {} questions across {} chunks. Coverage: {}",
                    jobId, Duration.between(startTime, Instant.now()).getSeconds(), 
                    acceptedQuestions.size(), acceptedChunkQuestions.size(), coverageSummary);

            // Publish event to trigger quiz creation
            // The event handler will mark the job as completed atomically with quiz creation
            eventPublisher.publishEvent(new QuizGenerationCompletedEvent(
                    this, jobId, acceptedChunkQuestions, request, acceptedQuestions));

            progress.setCompleted(true);
            progress.setGeneratedQuestions(acceptedQuestions);
            
            // Clean up progress map to prevent memory leaks
            generationProgress.remove(jobId);

        } catch (Exception e) {
            log.error("Quiz generation failed for job {}", jobId, e);

            transactionTemplate.executeWithoutResult(status -> {
                QuizGenerationJob failedJob = jobRepository.findById(jobId).orElse(null);
                if (failedJob != null) {
                    if (failedJob.isTerminal()) {
                        log.info("Generation worker for job {} stopped after terminal state {} won",
                                jobId, failedJob.getStatus());
                        return;
                    }
                    failedJob.markFailed("Generation failed: " + e.getMessage());

                    if (failedJob.getBillingReservationId() != null && failedJob.getBillingState() == BillingState.RESERVED) {
                        try {
                            String releaseIdempotencyKey = "quiz:" + jobId + ":release";
                            internalBillingService.release(
                                    failedJob.getBillingReservationId(),
                                    "Generation failed: " + e.getMessage(),
                                    jobId.toString(),
                                    releaseIdempotencyKey
                            );
                            failedJob.setBillingState(BillingState.RELEASED);
                            failedJob.addBillingIdempotencyKey("release", releaseIdempotencyKey);
                            log.info("Released billing reservation {} for failed job {}", failedJob.getBillingReservationId(), jobId);
                        } catch (Exception billingError) {
                            log.error("Failed to release billing reservation for job {}", jobId, billingError);
                            failedJob.setLastBillingError("{\"error\":\"Failed to release reservation: " + billingError.getMessage() + "\"}");
                        }
                    }

                    jobRepository.save(failedJob);
                }
            });

            GenerationProgress progress = generationProgress.get(jobId);
            if (progress != null) {
                progress.setCompleted(true);
                progress.addError("Generation failed: " + e.getMessage());
            }
            
            // Clean up progress map to prevent memory leaks
            generationProgress.remove(jobId);

            throw new AiServiceException("Failed to generate quiz: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<Question>> generateQuestionsFromChunk(
            DocumentChunk chunk,
            Map<QuestionType, Integer> questionsPerType,
            Difficulty difficulty
    ) {
        return generateQuestionsFromChunkWithJob(chunk, questionsPerType, difficulty, null, "en");
    }

    /**
     * Enhanced version with job status updates
     */
    public CompletableFuture<List<Question>> generateQuestionsFromChunkWithJob(
            DocumentChunk chunk,
            Map<QuestionType, Integer> questionsPerType,
            Difficulty difficulty,
            UUID jobId,
            String targetLanguage
    ) {
        return CompletableFuture.supplyAsync(() -> {
            List<Question> allQuestions = new ArrayList<>();
            List<String> chunkErrors = new ArrayList<>();
            String language = (targetLanguage == null || targetLanguage.isBlank()) ? "en" : targetLanguage.trim();

            try {
                // Validate chunk content
                if (chunk.getContent() == null || chunk.getContent().trim().isEmpty()) {
                    throw new AiServiceException("Chunk content is empty or null");
                }

                // Check if chunk content is too short for meaningful questions
                if (chunk.getContent().length() < 100) {
                    log.warn("Chunk {} content is very short ({} chars), may not generate good questions",
                            chunk.getChunkIndex(), chunk.getContent().length());
                }

                for (Map.Entry<QuestionType, Integer> entry : questionsPerType.entrySet()) {
                    QuestionType questionType = entry.getKey();
                    Integer questionCount = entry.getValue();

                    if (questionCount > 0) {
                        boolean success = false;
                        try {
                            List<Question> questions = generateQuestionsByTypeWithFallbacks(
                                    chunk.getContent(),
                                    questionType,
                                    questionCount,
                                    difficulty,
                                    chunk.getChunkIndex(),
                                    jobId,
                                    language
                            );
                            
                            if (!questions.isEmpty()) {
                                allQuestions.addAll(questions);
                                success = true;
                            } else {
                                chunkErrors.add(String.format("Failed to generate any %s questions after all fallback attempts",
                                        questionType));
                            }
                        } finally {
                            // Increment task counter after each question type batch completes (success or failure)
                            // This is a terminal outcome for this task
                            if (jobId != null) {
                                String status = success ? "done" : "failed";
                                updateJobTaskProgressSafely(jobId, 1, 
                                    String.format("Chunk %d · %s · %s", chunk.getChunkIndex(), questionType, status));
                                
                                // Update in-memory progress to keep it in sync with DB
                                GenerationProgress jobProgress = generationProgress.get(jobId);
                                if (jobProgress != null) {
                                    jobProgress.incrementCompletedTasks();
                                }
                            }
                        }
                    }
                }

                // If we have errors but also some successful questions, log warning but continue
                if (!chunkErrors.isEmpty() && !allQuestions.isEmpty()) {
                    log.warn("Chunk {} completed with {} errors but generated {} questions",
                            chunk.getChunkIndex(), chunkErrors.size(), allQuestions.size());
                }

                // If no questions were generated at all, throw exception
                if (allQuestions.isEmpty()) {
                    throw new AiServiceException("Failed to generate any questions for chunk " +
                            chunk.getChunkIndex() + ". Errors: " + String.join("; ", chunkErrors));
                }

                return allQuestions;

            } catch (Exception e) {
                propagateProviderUsagePersistenceFailure(e);
                log.error("Error generating questions for chunk {}", chunk.getChunkIndex(), e);
                throw new AiServiceException("Failed to generate questions for chunk " +
                        chunk.getChunkIndex() + ": " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<List<Question>> generateQuestionsFromChunkWithJob(
            DocumentChunk chunk,
            Map<QuestionType, Integer> questionsPerType,
            Difficulty difficulty,
            UUID jobId
    ) {
        return generateQuestionsFromChunkWithJob(chunk, questionsPerType, difficulty, jobId, "en");
    }

    @Override
    public List<Question> generateQuestionsByType(
            String chunkContent,
            QuestionType questionType,
            int questionCount,
            Difficulty difficulty
    ) {
        return generateQuestionsByTypeWithJobId(chunkContent, questionType, questionCount, difficulty, null, "en");
    }

    /**
     * Internal version that accepts jobId for cancellation checks and provider-usage tracking.
     * Uses structured AI client for schema-validated responses.
     */
    private List<Question> generateQuestionsByTypeWithJobId(
            String chunkContent,
            QuestionType questionType,
            int questionCount,
            Difficulty difficulty,
            UUID jobId,
            String targetLanguage
    ) {
        // Input validation
        if (chunkContent == null || chunkContent.trim().isEmpty()) {
            throw new IllegalArgumentException("Chunk content cannot be null or empty");
        }

        if (chunkContent.trim().length() < 10) {
            throw new IllegalArgumentException("Chunk content must be at least 10 characters long");
        }

        if (questionType == null) {
            throw new IllegalArgumentException("Question type cannot be null");
        }

        if (questionCount <= 0) {
            throw new IllegalArgumentException("Question count must be greater than 0");
        }

        if (difficulty == null) {
            throw new IllegalArgumentException("Difficulty cannot be null");
        }

        String language = (targetLanguage == null || targetLanguage.isBlank()) ? "en" : targetLanguage.trim();

        try {
            // Check for cancellation before LLM call
            if (isJobCancelled(jobId)) {
                log.info("Job {} cancelled before LLM call, stopping question generation", jobId);
                return new ArrayList<>();
            }

            // Record that AI calls have started (idempotent)
            if (jobId != null) {
                recordAiCallStarted(jobId);
            }

            // Build structured request with cancellation checker
            StructuredQuestionRequest structuredRequest = StructuredQuestionRequest.builder()
                    .chunkContent(chunkContent)
                    .questionType(questionType)
                    .questionCount(questionCount)
                    .difficulty(difficulty)
                    .language(language)
                    .metadata(jobId != null ? Map.of("jobId", jobId.toString()) : Map.of())
                    .cancellationChecker(jobId != null ? () -> isJobCancelled(jobId) : null)
                    .providerUsageObserver(jobId != null ? usage -> recordProviderUsage(jobId, usage) : null)
                    .build();

            // Use structured AI client (handles retries internally)
            StructuredQuestionResponse structuredResponse = structuredAiClient.generateQuestions(structuredRequest);

            // Validate response
            if (structuredResponse == null) {
                throw new AiServiceException("Structured AI client returned null response");
            }

            // Log warnings if any
            if (structuredResponse.getWarnings() != null && !structuredResponse.getWarnings().isEmpty()) {
                log.warn("Structured generation completed with {} warnings for {} type {}: {}",
                        structuredResponse.getWarnings().size(), questionCount, questionType,
                        structuredResponse.getWarnings());
            }

            // Convert StructuredQuestion to domain Question
            List<Question> questions = convertStructuredQuestions(structuredResponse.getQuestions());

            // Validate we got the expected number of questions
            if (questions.size() < questionCount) {
                log.warn("Expected {} questions but got {} for type {} (based on diagnostics: {})",
                        questionCount, questions.size(), questionType, 
                        structuredResponse.getWarnings() != null ? structuredResponse.getWarnings() : List.of());
            }

            return questions;

        } catch (Exception e) {
            propagateProviderUsagePersistenceFailure(e);
            log.error("Error generating {} questions of type {} using structured client",
                    questionCount, questionType, e);
            throw new AiServiceException("Failed to generate questions: " + e.getMessage(), e);
        }
    }

    /**
     * Convert StructuredQuestion DTOs to domain Question entities.
     * Phase 3: Maps from structured output to domain model.
     * Applies content shuffling to remove AI positional bias.
     */
    public List<Question> convertStructuredQuestions(List<StructuredQuestion> structuredQuestions) {
        List<Question> questions = new ArrayList<>();

        for (int index = 0; index < structuredQuestions.size(); index++) {
            StructuredQuestion sq = structuredQuestions.get(index);
            if (!hasRuntimeValidContent(sq, index)) {
                continue;
            }

            Question question = new Question();
            question.setQuestionText(sq.getQuestionText());
            question.setType(sq.getType());
            question.setDifficulty(sq.getDifficulty());
            
            // Apply content shuffling to remove AI positional bias
            String shuffledContent = questionContentShuffler.shuffleContent(
                sq.getContent(), 
                sq.getType(), 
                ThreadLocalRandom::current
            );
            question.setContent(shuffledContent);
            
            question.setHint(sq.getHint());
            question.setExplanation(sq.getExplanation());
            
            questions.add(question);
        }
        
        return questions;
    }

    private boolean hasRuntimeValidContent(StructuredQuestion question, int index) {
        if (question == null || question.getContent() == null || question.getContent().isBlank()) {
            logGeneratedContentRejection(index, question != null ? question.getType() : null, "malformed_content");
            return false;
        }

        JsonNode content;
        try {
            content = objectMapper.readTree(question.getContent());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            logGeneratedContentRejection(index, question.getType(), "malformed_content");
            return false;
        }
        if (content == null) {
            logGeneratedContentRejection(index, question.getType(), "malformed_content");
            return false;
        }

        try {
            questionContentValidationService.validateContent(question.getType(), content);
            return true;
        } catch (RuntimeException exception) {
            logGeneratedContentRejection(index, question.getType(), "runtime_validation_failed");
            return false;
        }
    }

    private void logGeneratedContentRejection(int index, QuestionType type, String reason) {
        log.warn("Rejected generated question content at position {} for type {}: {}", index, type, reason);
    }

    /**
     * Retries generation without changing the user-requested type or difficulty.
     * Reduced quantity is allowed because the job-level coverage policy makes the
     * final partial-success decision after redistribution.
     */
    private List<Question> generateQuestionsByTypeWithFallbacks(
            String chunkContent,
            QuestionType questionType,
            int questionCount,
            Difficulty difficulty,
            Integer chunkIndex,
            UUID jobId,
            String targetLanguage
    ) {
        String language = (targetLanguage == null || targetLanguage.isBlank()) ? "en" : targetLanguage.trim();

        // Update job status to show fallback attempt
        updateJobStatusSafely(jobId, "Generating " + questionType + " questions for chunk " + chunkIndex);

        // Strategy 1: Try normal generation (multiple attempts)
        int normalAttempts = 3;
        for (int attempt = 1; attempt <= normalAttempts; attempt++) {
            try {
                updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " attempt " + attempt + "/3");
                
                List<Question> questions = generateQuestionsByTypeWithJobId(
                        chunkContent,
                        questionType,
                        questionCount,
                        difficulty,
                        jobId,
                        language
                );
                if (questions.size() >= questionCount) {
                    updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " generated successfully");
                    return questions;
                } else {
                    log.warn("Strategy 1 (normal) attempt {} generated only {}/{} questions for {} chunk {}", 
                            attempt, questions.size(), questionCount, questionType, chunkIndex);
                    // If we got some questions but not enough, and this is the last attempt, return what we have
                    if (attempt == normalAttempts && !questions.isEmpty()) {
                        log.info("Strategy 1 (normal) returning partial result: {}/{} questions for {} chunk {}", 
                                questions.size(), questionCount, questionType, chunkIndex);
                        updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " partial success (" + questions.size() + "/" + questionCount + ")");
                        return questions;
                    }
                }
            } catch (Exception e) {
                propagateProviderUsagePersistenceFailure(e);
                log.warn("Strategy 1 (normal) attempt {} failed for {} chunk {}: {}", 
                        attempt, questionType, chunkIndex, e.getMessage());
                updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " attempt " + attempt + " failed, retrying...");
                // Continue to next attempt unless this is the last one
            }
        }

        // Strategy 2: Try with reduced count (multiple attempts, if requesting more than 1)
        if (questionCount > 1) {
            updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " using reduced count strategy");
            
            int reducedAttempts = 2;
            int reducedCount = Math.max(1, questionCount / 2);
            
            for (int attempt = 1; attempt <= reducedAttempts; attempt++) {
                try {
                    updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " reduced count attempt " + attempt + "/2");
                    log.debug("Strategy 2: Trying with reduced count {} (attempt {}) for {} chunk {}", 
                            reducedCount, attempt, questionType, chunkIndex);
                    
                    List<Question> questions = generateQuestionsByTypeWithJobId(
                            chunkContent,
                            questionType,
                            reducedCount,
                            difficulty,
                            jobId,
                            language
                    );
                    if (!questions.isEmpty()) {
                        log.info("Strategy 2 (reduced count) succeeded on attempt {}: {}/{} questions for {} chunk {}", 
                                attempt, questions.size(), questionCount, questionType, chunkIndex);
                        updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " reduced count success");
                        return questions;
                    }
                } catch (Exception e) {
                    propagateProviderUsagePersistenceFailure(e);
                    log.warn("Strategy 2 (reduced count) attempt {} failed for {} chunk {}: {}", 
                            attempt, questionType, chunkIndex, e.getMessage());
                    updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " reduced count attempt " + attempt + " failed");
                    // Continue to next attempt unless this is the last one
                }
            }
        }

        log.error("All same-contract generation strategies failed for {} questions of type {} in chunk {}",
                questionCount, questionType, chunkIndex);
        updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " generation failed completely");
        return new ArrayList<>();
    }

    /**
     * Attempt to generate missing question types from chunks that performed well
     */
    private void redistributeMissingQuestions(
            List<DocumentChunk> chunks,
            Map<QuestionType, Integer> missingTypes,
            Difficulty difficulty,
            Map<Integer, List<Question>> chunkQuestions,
            List<Question> allQuestions,
            Map<QuestionType, Integer> generatedByType,
            UUID jobId,
            String targetLanguage) {
        
        String language = (targetLanguage == null || targetLanguage.isBlank()) ? "en" : targetLanguage.trim();
        
        // Find chunks that generated questions successfully (have more than average content)
        List<DocumentChunk> goodChunks = chunks.stream()
                .filter(chunk -> chunkQuestions.containsKey(chunk.getChunkIndex()))
                .filter(chunk -> chunk.getContent().length() > 200) // Decent content length
                .sorted((a, b) -> Integer.compare(
                        chunkQuestions.get(b.getChunkIndex()).size(),
                        chunkQuestions.get(a.getChunkIndex()).size()))
                .limit(Math.min(5, chunks.size())) // Try up to 5 best chunks
                .toList();

        log.debug("Attempting redistribution using {} good chunks", goodChunks.size());
        updateJobStatusSafely(jobId, "Redistribution: Found " + goodChunks.size() + " suitable chunks for missing types");

        for (Map.Entry<QuestionType, Integer> entry : missingTypes.entrySet()) {
            QuestionType missingType = entry.getKey();
            int neededCount = entry.getValue();
            int attemptedCount = 0;

            log.debug("Attempting to generate {} missing {} questions", neededCount, missingType);
            updateJobStatusSafely(jobId, "Redistribution: Attempting to generate " + neededCount + " missing " + missingType + " questions");

            for (DocumentChunk chunk : goodChunks) {
                if (attemptedCount >= neededCount) {
                    break; // We have enough
                }

                try {
                    int countToTry = Math.min(neededCount - attemptedCount, 2); // Max 2 per chunk
                    
                    List<Question> redistributedQuestions = generateQuestionsByTypeWithFallbacks(
                            chunk.getContent(),
                            missingType,
                            countToTry,
                            difficulty,
                            chunk.getChunkIndex(),
                            jobId,
                            language
                    );

                    if (!redistributedQuestions.isEmpty()) {
                        allQuestions.addAll(redistributedQuestions);
                        
                        // Add to chunk questions (append to existing)
                        chunkQuestions.computeIfAbsent(chunk.getChunkIndex(), k -> new ArrayList<>())
                                     .addAll(redistributedQuestions);
                        
                        // Update counter
                        generatedByType.merge(missingType, redistributedQuestions.size(), Integer::sum);
                        attemptedCount += redistributedQuestions.size();

                        log.info("Redistributed {} {} questions to chunk {}", 
                                redistributedQuestions.size(), missingType, chunk.getChunkIndex());
                    }
                } catch (Exception e) {
                    propagateProviderUsagePersistenceFailure(e);
                    log.warn("Failed to redistribute {} questions to chunk {}: {}", 
                            missingType, chunk.getChunkIndex(), e.getMessage());
                }
            }

            if (attemptedCount > 0) {
                log.info("Successfully redistributed {}/{} {} questions", 
                        attemptedCount, neededCount, missingType);
                updateJobStatusSafely(jobId, "Redistribution: Successfully added " + attemptedCount + "/" + neededCount + " " + missingType + " questions");
            } else {
                log.warn("Failed to redistribute any {} questions", missingType);
                updateJobStatusSafely(jobId, "Redistribution: Could not generate any " + missingType + " questions");
            }
        }
    }

    /**
     * Format a coverage summary for logging
     */
    private String formatCoverageSummary(
            Map<QuestionType, Integer> generated, 
            Map<QuestionType, Integer> requested) {
        
        List<String> summaryParts = new ArrayList<>();
        
        for (Map.Entry<QuestionType, Integer> entry : requested.entrySet()) {
            QuestionType type = entry.getKey();
            int requestedCount = entry.getValue();
            int generatedCount = generated.getOrDefault(type, 0);
            
            String coverage = String.format("%s: %d/%d", type, generatedCount, requestedCount);
            if (generatedCount >= requestedCount) {
                coverage += " ✓";
            } else if (generatedCount > 0) {
                coverage += " ⚠";
            } else {
                coverage += " ✗";
            }
            
            summaryParts.add(coverage);
        }
        
        return String.join(", ", summaryParts);
    }

    @Override
    public void validateDocumentForGeneration(UUID documentId, String username) {
        Document document = documentRepository.findByIdWithChunksAndUser(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + documentId));

        // Check if document belongs to user
        if (!document.getUploadedBy().getUsername().equals(username)) {
            throw new IllegalArgumentException("User not authorized to access this document");
        }

        // Check if document is processed
        if (document.getStatus() != Document.DocumentStatus.PROCESSED) {
            throw new IllegalArgumentException("Document must be processed before generating quiz");
        }

        // Check if document has chunks
        if (document.getChunks() == null || document.getChunks().isEmpty()) {
            throw new IllegalArgumentException("Document has no chunks available for quiz generation");
        }

        log.debug("Document {} validated for quiz generation", documentId);
    }

    @Override
    public int calculateEstimatedGenerationTime(int totalChunks, Map<QuestionType, Integer> questionsPerType) {
        // Base time per chunk (AI API call + processing)
        int baseTimePerChunk = 30; // seconds

        // Additional time per question type
        int timePerQuestionType = 10; // seconds

        // Estimate: base time per chunk + additional time for question types
        int estimatedTime = (totalChunks * baseTimePerChunk) + (questionsPerType.size() * timePerQuestionType);

        // Add buffer for network latency and processing
        estimatedTime = (int) (estimatedTime * 1.2);

        return estimatedTime;
    }

    @Override
    public int calculateTotalChunks(UUID documentId, GenerateQuizFromDocumentRequest request) {
        try {
            log.debug("Calculating total chunks for document: {} with scope: {}", documentId, request.quizScope());
            
            Document document = documentRepository.findByIdWithChunks(documentId)
                    .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + documentId));

            log.debug("Document {} status: {}, chunks: {}", documentId, document.getStatus(), 
                    document.getChunks() != null ? document.getChunks().size() : "null");

            List<DocumentChunk> chunks = getChunksForScope(document, request);
            log.debug("Found {} chunks for document: {} with scope: {}", chunks.size(), documentId, request.quizScope());
            
            if (chunks.isEmpty()) {
                log.warn("No chunks found for document: {} with scope: {}", documentId, request.quizScope());
                // Return 1 as default to prevent "Total chunks must be positive" error
                return 1;
            }
            
            return chunks.size();
        } catch (Exception e) {
            log.error("Error calculating total chunks for document: {}", documentId, e);
            // Return a reasonable default if calculation fails
            return 1;
        }
    }

    /**
     * Create a new generation job and store request data
     */
        public QuizGenerationJob createGenerationJob(UUID documentId, String username, GenerateQuizFromDocumentRequest request) {
        // Input validation
        if (username == null) {
            throw new IllegalArgumentException("Username cannot be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        
        try {
            // Serialize request data to JSON
            String requestData = objectMapper.writeValueAsString(request);
            
            // Get user by username
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
            
            // Create job entity
            QuizGenerationJob job = new QuizGenerationJob();
            job.setUser(user);
            job.setDocumentId(documentId);
            job.setStatus(GenerationStatus.PENDING);
            job.setRequestData(requestData);

            // Calculate estimated completion time
            Document document = documentRepository.findByIdWithChunksAndUser(documentId)
                    .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + documentId));

            List<DocumentChunk> chunks = getChunksForScope(document, request);
            int estimatedSeconds = calculateEstimatedGenerationTime(chunks.size(), request.questionsPerType());
            job.setEstimatedCompletion(LocalDateTime.now().plusSeconds(estimatedSeconds));

            // Save job
            return jobRepository.save(job);

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize request data for job creation", e);
            throw new AiServiceException("Failed to create generation job", e);
        }
    }

    /**
     * Get job by ID with user authorization
     */
    public QuizGenerationJob getJobByIdAndUsername(UUID jobId, String username) {
        // Input validation
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null");
        }
        if (username == null) {
            throw new IllegalArgumentException("Username cannot be null");
        }
        if (username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        
        return jobRepository.findById(jobId)
                .filter(job -> job.getUser().getUsername().equals(username))
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found or access denied"));
    }

    /**
     * Update job progress in database
     */
    public void updateJobProgress(UUID jobId, int processedChunks, String currentChunk) {
        // Input validation
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null");
        }
        if (processedChunks < 0) {
            throw new IllegalArgumentException("Processed chunks cannot be negative");
        }
        if (currentChunk == null) {
            throw new IllegalArgumentException("Current chunk cannot be null");
        }
        if (currentChunk.trim().isEmpty()) {
            throw new IllegalArgumentException("Current chunk cannot be empty");
        }
        
        QuizGenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz generation job not found with ID: " + jobId));

        job.updateProgress(processedChunks, currentChunk);
        jobRepository.save(job);
    }

         /**
      * Update job status safely, ensuring transaction integrity
      */
     private void updateJobStatusSafely(UUID jobId, String statusMessage) {
         if (jobId == null) {
             // When called from public interface without jobId, just log
             log.debug("Job status update (no jobId): {}", statusMessage);
             return;
         }
         
         try {
             QuizGenerationJob job = jobRepository.findById(jobId)
                     .orElse(null);
             if (job != null) {
                 job.updateProgress(job.getProcessedChunks(), statusMessage);
                 jobRepository.save(job);
             }
         } catch (Exception e) {
             log.error("Failed to update job status for job {}: {}", jobId, statusMessage, e);
         }
     }

     /**
      * Update job task progress safely using atomic repository update.
      * This performs a single atomic UPDATE without loading the entity.
      * Uses TransactionTemplate to avoid self-invocation issues.
      */
     public void updateJobTaskProgressSafely(UUID jobId, int completedDelta, String statusMessage) {
         if (jobId == null) {
             return;
         }
         
         try {
             transactionTemplate.executeWithoutResult(status -> {
                 int updated = jobRepository.incrementCompletedTasks(jobId, completedDelta, statusMessage);
                 if (updated == 0) {
                     log.debug("Task progress was not updated for job {} because it is terminal, missing, or the increment is invalid",
                             jobId);
                 } else {
                     log.debug("Updated task progress for job {}: +{} tasks, status: {}", 
                             jobId, completedDelta, statusMessage);
                 }
             });
         } catch (Exception e) {
             log.error("Failed to update task progress for job {}: {}", jobId, statusMessage, e);
         }
     }

     /**
     * Update chunk-level progress atomically. When task counters exist, they remain authoritative
     * for the percentage, preventing stale entity saves from overwriting atomic task increments.
      * Uses TransactionTemplate to avoid self-invocation issues.
      */
     public void updateJobChunkProgressSafely(UUID jobId, int processedChunks, String statusMessage) {
         if (jobId == null) {
             return;
         }
         
         try {
             transactionTemplate.executeWithoutResult(status -> {
                 int updated = jobRepository.updateProcessedChunksAndStatus(jobId, processedChunks, statusMessage);
                 if (updated == 0) {
                     log.debug("Chunk progress was not updated for job {} because it is terminal, missing, or the counter is invalid",
                             jobId);
                 }
             });
         } catch (Exception e) {
             log.error("Failed to update chunk progress for job {}: {}", jobId, statusMessage, e);
         }
     }

    /**
     * Get chunks based on the quiz scope
     */
    private List<DocumentChunk> getChunksForScope(Document document, GenerateQuizFromDocumentRequest request) {
        List<DocumentChunk> allChunks = document.getChunks();
        log.debug("Document {} has {} total chunks", document.getId(), allChunks != null ? allChunks.size() : 0);

        if (request.quizScope() == null || request.quizScope() == QuizScope.ENTIRE_DOCUMENT) {
            log.debug("Using entire document scope, returning all {} chunks", allChunks != null ? allChunks.size() : 0);
            return allChunks != null ? allChunks : new ArrayList<>();
        }

        switch (request.quizScope()) {
            case SPECIFIC_CHUNKS:
                if (request.chunkIndices() == null || request.chunkIndices().isEmpty()) {
                    throw new IllegalArgumentException("Chunk indices must be specified for SPECIFIC_CHUNKS scope");
                }
                assert allChunks != null;
                List<DocumentChunk> specificChunks = allChunks.stream()
                        .filter(chunk -> request.chunkIndices().contains(chunk.getChunkIndex()))
                        .collect(Collectors.toList());
                log.debug("Filtered to {} specific chunks for indices: {}", specificChunks.size(), request.chunkIndices());
                return specificChunks;

            case SPECIFIC_CHAPTER:
                assert allChunks != null;
                return allChunks.stream()
                        .filter(chunk1 -> matchesChapter(chunk1, request.chapterTitle(), request.chapterNumber()))
                        .collect(Collectors.toList());

            case SPECIFIC_SECTION:
                List<DocumentChunk> sectionChunks = allChunks.stream()
                        .filter(chunk -> matchesSection(chunk, request.chapterTitle(), request.chapterNumber()))
                        .collect(Collectors.toList());
                log.debug("Filtered to {} chunks for section: title={}, number={}", 
                        sectionChunks.size(), request.chapterTitle(), request.chapterNumber());
                return sectionChunks;

            default:
                log.debug("Using default scope, returning all {} chunks", allChunks != null ? allChunks.size() : 0);
                return allChunks != null ? allChunks : new ArrayList<>();
        }
    }

    private boolean matchesChapter(DocumentChunk chunk, String chapterTitle, Integer chapterNumber) {
        if (chapterTitle != null && chunk.getChapterTitle() != null) {
            return chunk.getChapterTitle().equalsIgnoreCase(chapterTitle);
        }
        if (chapterNumber != null && chunk.getChapterNumber() != null) {
            return chunk.getChapterNumber().equals(chapterNumber);
        }
        return false;
    }

    private boolean matchesSection(DocumentChunk chunk, String sectionTitle, Integer sectionNumber) {
        if (sectionTitle != null && chunk.getSectionTitle() != null) {
            return chunk.getSectionTitle().equalsIgnoreCase(sectionTitle);
        }
        if (sectionNumber != null && chunk.getSectionNumber() != null) {
            return chunk.getSectionNumber().equals(sectionNumber);
        }
        return false;
    }

    /**
     * Get generation progress for a job
     */
    public GenerationProgress getProgress(UUID jobId) {
        return generationProgress.get(jobId);
    }

    /**
     * Inner class to track generation progress
     */
    public static class GenerationProgress {
        private final AtomicInteger processedChunks = new AtomicInteger(0);
        private final AtomicInteger completedTasks = new AtomicInteger(0);
        @Setter
        @Getter
        private int totalChunks;
        @Setter
        @Getter
        private int totalTasks;
        @Setter
        @Getter
        private boolean completed = false;
        @Setter
        @Getter
        private List<Question> generatedQuestions = new ArrayList<>();
        @Getter
        private List<String> errors = new ArrayList<>();
        @Getter
        private final Instant startTime = Instant.now();

        public void incrementProcessedChunks() {
            processedChunks.incrementAndGet();
        }

        public void incrementCompletedTasks() {
            completedTasks.incrementAndGet();
        }

        public void addError(String error) {
            errors.add(error);
        }

        public double getProgressPercentage() {
            // Prefer task counters when available
            if (totalTasks > 0) {
                return QuizGenerationJob.calculateActiveProgressPercentage(completedTasks.get(), totalTasks);
            }
            // Fall back to chunk counters
            return QuizGenerationJob.calculateActiveProgressPercentage(processedChunks.get(), totalChunks);
        }

        public Duration getElapsedTime() {
            return Duration.between(startTime, Instant.now());
        }

        // Getters
        public int getProcessedChunks() {
            return processedChunks.get();
        }

        public int getCompletedTasks() {
            return completedTasks.get();
        }
    }

    /**
     * Check if the exception is a rate limit error (429)
     */
    public boolean isRateLimitError(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        
        // Check for common rate limit indicators
        return message.contains("429") || 
               message.contains("rate limit") || 
               message.contains("rate_limit_exceeded") ||
               message.contains("Too Many Requests") ||
               message.contains("TPM") ||
               message.contains("RPM");
    }

    /**
     * Calculate exponential backoff delay with jitter
     * Uses configuration values for base delay, max delay, and jitter factor
     */
    public long calculateBackoffDelay(int retryCount) {
        // Exponential backoff: 2^retryCount * baseDelay
        long exponentialDelay = rateLimitConfig.getBaseDelayMs() * (long) Math.pow(2, retryCount);
        
        // Add jitter to prevent thundering herd
        double jitterRange = rateLimitConfig.getJitterFactor();
        double jitter = (1.0 - jitterRange) + (Math.random() * 2 * jitterRange);
        
        long delayWithJitter = (long) (exponentialDelay * jitter);
        
        // Cap at maximum delay
        return Math.min(delayWithJitter, rateLimitConfig.getMaxDelayMs());
    }

    /**
     * Sleep for the specified delay during rate limiting
     * This method can be overridden in tests to avoid actual sleeping
     */
    protected void sleepForRateLimit(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AiServiceException("Interrupted while waiting for rate limit", ie);
        }
    }

    /**
     * Update job status to PROCESSING in a short transaction
     */
    public QuizGenerationJob updateJobStatusToProcessing(UUID jobId) {
        return transactionTemplate.execute(status -> {
            QuizGenerationJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
            
            // Initialize lazy relationships we will need outside the transaction
            job.getUser().getId();
            job.getUser().getUsername();
            
            job.setStatus(GenerationStatus.PROCESSING);
            return jobRepository.save(job);
        });
    }

    /**
     * Update job total chunks in a short transaction
     */
    public void updateJobTotalChunks(UUID jobId, int totalChunks) {
        transactionTemplate.executeWithoutResult(status -> {
            QuizGenerationJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
            job.setTotalChunks(totalChunks);
            jobRepository.save(job);
        });
    }

    /**
     * Update job total chunks and total tasks in a short transaction
     */
    public void updateJobTotalChunksAndTasks(UUID jobId, int totalChunks, int totalTasks) {
        transactionTemplate.executeWithoutResult(status -> {
            QuizGenerationJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
            job.setTotalChunks(totalChunks);
            job.setTotalTasks(totalTasks);
            jobRepository.save(job);
        });
    }

    /**
     * Compute total tasks for a generation job.
     * Total tasks = number of chunks × number of requested question types (with count > 0)
     * Package-private for testing.
     */
    int computeTotalTasks(int chunkCount, Map<QuestionType, Integer> questionsPerType) {
        if (questionsPerType == null || questionsPerType.isEmpty()) {
            return chunkCount; // Default: one task per chunk
        }
        
        // Count how many question types are requested (with count > 0)
        long requestedTypes = questionsPerType.values().stream()
                .filter(count -> count != null && count > 0)
                .count();
        
        return chunkCount * (int) requestedTypes;
    }

    /**
     * Check if a job has been cancelled.
     * Used for cooperative cancellation - the generator checks this before each LLM call
     * and stops processing if the job is cancelled.
     */
    private boolean isJobCancelled(UUID jobId) {
        if (jobId == null) {
            return false;
        }
        
        try {
            Optional<QuizGenerationJob> job = jobRepository.findById(jobId);
            if (job.isEmpty()) {
                return false;
            }
            
            GenerationStatus status = job.get().getStatus();
            boolean cancelled = status == GenerationStatus.CANCELLED;
            if (cancelled) {
                log.info("Job {} detected as cancelled (status: {})", jobId, status);
            }
            return cancelled;
        } catch (Exception e) {
            log.error("Error checking cancellation status for job {}", jobId, e);
            return false; // On error, continue processing rather than aborting
        }
    }

    /**
     * Record that AI calls have started for this job (for billing on cancel).
     * This is idempotent - only sets the flag and timestamp on the first call.
     * Uses TransactionTemplate to avoid self-invocation issues.
     */
    public void recordAiCallStarted(UUID jobId) {
        if (jobId == null) {
            return;
        }
        
        try {
            transactionTemplate.executeWithoutResult(status -> {
                QuizGenerationJob job = jobRepository.findById(jobId).orElse(null);
                if (job != null) {
                    if (!Boolean.TRUE.equals(job.getHasStartedAiCalls())) {
                        job.setHasStartedAiCalls(true);
                        job.setFirstAiCallAt(java.time.LocalDateTime.now());
                    }
                    if (job.getBillingReservationId() != null
                            && job.getBillingState() == BillingState.RESERVED) {
                        var renewedReservation = internalBillingService.renewReservationLease(
                                job.getUser().getId(),
                                job.getBillingReservationId(),
                                jobId
                        );
                        job.setReservationExpiresAt(renewedReservation.expiresAt());
                    }
                    jobRepository.save(job);
                    log.debug("Recorded AI call and refreshed reservation lease for job {}", jobId);
                }
            });
        } catch (Exception e) {
            log.error("Error recording AI call start for job {}", jobId, e);
            // Don't fail the generation if tracking fails
        }
    }

    void recordProviderUsage(UUID jobId, ProviderUsageObservation usage) {
        try {
            if (usage.isReported()) {
                providerUsageService.recordReported(
                        jobId,
                        usage.providerAttemptId(),
                        usage.providerLlmTokens()
                );
            } else {
                providerUsageService.recordMissing(jobId, usage.providerAttemptId());
            }
        } catch (RuntimeException exception) {
            throw new ProviderUsagePersistenceException(
                    "Provider usage could not be persisted for generation job " + jobId,
                    exception
            );
        }
    }

    private void propagateProviderUsagePersistenceFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ProviderUsagePersistenceException persistenceFailure) {
                throw persistenceFailure;
            }
            current = current.getCause();
        }
    }
}
