package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionResponseParser;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCompletedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;
import uk.gegc.quizmaker.shared.exception.AiServiceException;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

            // Update job with total chunks in a short transaction
            updateJobTotalChunks(jobId, chunks.size());

            log.info("Processing {} chunks for document {}", chunks.size(), request.documentId());

            // Process chunks asynchronously
            List<CompletableFuture<List<Question>>> chunkFutures = chunks.stream()
                    .map(chunk -> generateQuestionsFromChunkWithJob(chunk, request.questionsPerType(), request.difficulty(), jobId))
                    .toList();

            // Collect all generated questions with enhanced tracking
            List<Question> allQuestions = new ArrayList<>();
            Map<Integer, List<Question>> chunkQuestions = new HashMap<>();
            Map<QuestionType, Integer> generatedByType = new EnumMap<>(QuestionType.class);
            Map<QuestionType, Integer> requestedByType = new EnumMap<>(request.questionsPerType());
            
            // Initialize counters
            for (QuestionType type : QuestionType.values()) {
                generatedByType.put(type, 0);
            }
            
            int processedChunks = 0;

            // Collect results from all chunks
            for (int chunkIndex = 0; chunkIndex < chunkFutures.size(); chunkIndex++) {
                try {
                    CompletableFuture<List<Question>> future = chunkFutures.get(chunkIndex);
                    List<Question> chunkQuestionsList = future.get();
                    
                    if (!chunkQuestionsList.isEmpty()) {
                        allQuestions.addAll(chunkQuestionsList);
                        chunkQuestions.put(chunkIndex, chunkQuestionsList);
                        
                        // Track generated question types
                        for (Question question : chunkQuestionsList) {
                            generatedByType.merge(question.getType(), 1, Integer::sum);
                        }
                    }
                    
                    processedChunks++;

                    // Update progress in database
                    freshJob.updateProgress(processedChunks, "Processing chunk " + processedChunks + " of " + chunks.size());
                    jobRepository.save(freshJob);

                } catch (Exception e) {
                    log.error("Error processing chunk {} for job {}", chunkIndex, jobId, e);
                    progress.addError("Chunk " + chunkIndex + " processing failed: " + e.getMessage());

                    // Update job with error
                    freshJob.updateProgress(processedChunks, "Error in chunk " + chunkIndex);
                    jobRepository.save(freshJob);
                }
            }

            if (allQuestions.isEmpty()) {
                throw new AiServiceException("Failed to generate any questions for job " + jobId + ". All generation attempts failed.");
            }

            // Analyze coverage and attempt to fill gaps
            Map<QuestionType, Integer> missingTypes = findMissingQuestionTypes(requestedByType, generatedByType);
            
            if (!missingTypes.isEmpty()) {
                log.info("Missing question types detected for job {}: {}. Attempting redistribution...", 
                        jobId, missingTypes);
                
                // Update job status to show redistribution phase
                freshJob.updateProgress(chunks.size(), "Analyzing coverage: " + missingTypes.size() + " question types need redistribution");
                jobRepository.save(freshJob);
                
                // Attempt to generate missing types from successful chunks
                redistributeMissingQuestions(chunks, missingTypes, request.difficulty(), 
                                           chunkQuestions, allQuestions, generatedByType, jobId);
                        
                // Update progress after redistribution
                String finalCoverage = formatCoverageSummary(generatedByType, requestedByType);
                freshJob.updateProgress(chunks.size(), "Generation completed with redistribution: " + finalCoverage);
                jobRepository.save(freshJob);
            } else {
                // Update progress when no redistribution needed
                String coverage = formatCoverageSummary(generatedByType, requestedByType);
                freshJob.updateProgress(chunks.size(), "Generation completed successfully: " + coverage);
                jobRepository.save(freshJob);
            }

            log.info("Quiz generation completed for job {} in {} seconds. Generated {} questions across {} chunks. Coverage: {}",
                    jobId, Duration.between(startTime, Instant.now()).getSeconds(), 
                    allQuestions.size(), chunkQuestions.size(), formatCoverageSummary(generatedByType, requestedByType));

            // Publish event to trigger quiz creation
            eventPublisher.publishEvent(new QuizGenerationCompletedEvent(
                    this, jobId, chunkQuestions, request, allQuestions));

            // Mark job as completed with total questions count
            freshJob.markCompleted(null, allQuestions.size());
            jobRepository.save(freshJob);

            progress.setCompleted(true);
            progress.setGeneratedQuestions(allQuestions);

        } catch (Exception e) {
            log.error("Quiz generation failed for job {}", jobId, e);

            transactionTemplate.executeWithoutResult(status -> {
                QuizGenerationJob failedJob = jobRepository.findById(jobId).orElse(null);
                if (failedJob != null) {
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

            throw new AiServiceException("Failed to generate quiz: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<Question>> generateQuestionsFromChunk(
            DocumentChunk chunk,
            Map<QuestionType, Integer> questionsPerType,
            Difficulty difficulty
    ) {
        return generateQuestionsFromChunkWithJob(chunk, questionsPerType, difficulty, null);
    }

    /**
     * Enhanced version with job status updates
     */
    public CompletableFuture<List<Question>> generateQuestionsFromChunkWithJob(
            DocumentChunk chunk,
            Map<QuestionType, Integer> questionsPerType,
            Difficulty difficulty,
            UUID jobId
    ) {
        return CompletableFuture.supplyAsync(() -> {
            List<Question> allQuestions = new ArrayList<>();
            List<String> chunkErrors = new ArrayList<>();

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
                        List<Question> questions = generateQuestionsByTypeWithFallbacks(
                                chunk.getContent(),
                                questionType,
                                questionCount,
                                difficulty,
                                chunk.getChunkIndex(),
                                jobId
                        );
                        
                        if (!questions.isEmpty()) {
                            allQuestions.addAll(questions);
                        } else {
                            chunkErrors.add(String.format("Failed to generate any %s questions after all fallback attempts",
                                    questionType));
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
                log.error("Error generating questions for chunk {}", chunk.getChunkIndex(), e);
                throw new AiServiceException("Failed to generate questions for chunk " +
                        chunk.getChunkIndex() + ": " + e.getMessage(), e);
            }
        });
    }

    @Override
    public List<Question> generateQuestionsByType(
            String chunkContent,
            QuestionType questionType,
            int questionCount,
            Difficulty difficulty
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

        int maxRetries = rateLimitConfig.getMaxRetries();
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                // Build prompt for this question type
                String prompt = promptTemplateService.buildPromptForChunk(
                        chunkContent, questionType, questionCount, difficulty
                );

                // Send to AI with timeout
                ChatResponse response = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .chatResponse();

                if (response == null || response.getResult() == null) {
                    throw new AiServiceException("No response received from AI service");
                }

                String aiResponse = response.getResult().getOutput().getText();

                // AI response logging disabled to avoid generating per-run files

                // Validate AI response is not empty
                if (aiResponse == null || aiResponse.trim().isEmpty()) {
                    throw new AiServiceException("Empty response received from AI service");
                }

                // Parse AI response into questions
                List<Question> questions = questionResponseParser.parseQuestionsFromAIResponse(
                        aiResponse, questionType
                );

                // Validate we got the expected number of questions
                if (questions.size() != questionCount) {
                    log.warn("Expected {} questions but got {} for type {}",
                            questionCount, questions.size(), questionType);

                    // If we got fewer questions than expected, try to generate more
                    if (questions.size() < questionCount && retryCount < maxRetries - 1) {
                        log.info("Retrying to generate additional questions for type {}", questionType);
                        retryCount++;
                        continue;
                    }
                }

                return questions;

            } catch (AIResponseParseException e) {
                log.error("AI response parsing failed for {} questions of type {} (attempt {})",
                        questionCount, questionType, retryCount + 1, e);

                if (retryCount < maxRetries - 1) {
                    retryCount++;
                    log.info("Retrying due to parsing error for type {}", questionType);
                } else {
                    throw new AiServiceException("Failed to parse AI response after " + maxRetries + " attempts", e);
                }

            } catch (Exception e) {
                log.error("Error generating {} questions of type {} (attempt {})",
                        questionCount, questionType, retryCount + 1, e);

                // Check if this is a rate limit error (429)
                if (isRateLimitError(e)) {
                    long delayMs = calculateBackoffDelay(retryCount);
                    log.warn("Rate limit hit for {} questions of type {} (attempt {}). Waiting {} ms before retry.",
                            questionCount, questionType, retryCount + 1, delayMs);
                    
                    if (retryCount < maxRetries - 1) {
                        sleepForRateLimit(delayMs);
                        retryCount++;
                        log.info("Retrying after rate limit delay for type {}", questionType);
                        continue;
                    } else {
                        throw new AiServiceException("Rate limit exceeded after " + maxRetries + " attempts. Please try again later.", e);
                    }
                }

                if (retryCount < maxRetries - 1) {
                    retryCount++;
                    log.info("Retrying due to error for type {}", questionType);
                } else {
                    throw new AiServiceException("Failed to generate questions after " + maxRetries + " attempts: " + e.getMessage(), e);
                }
            }
        }

        throw new AiServiceException("Failed to generate questions after " + maxRetries + " attempts");
    }

    /**
     * Enhanced question generation with multiple fallback strategies
     */
    private List<Question> generateQuestionsByTypeWithFallbacks(
            String chunkContent,
            QuestionType questionType,
            int questionCount,
            Difficulty difficulty,
            Integer chunkIndex,
            UUID jobId
    ) {

        // Update job status to show fallback attempt
        updateJobStatusSafely(jobId, "Generating " + questionType + " questions for chunk " + chunkIndex);

        // Strategy 1: Try normal generation (multiple attempts)
        int normalAttempts = 3;
        for (int attempt = 1; attempt <= normalAttempts; attempt++) {
            try {
                updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " attempt " + attempt + "/3");
                
                List<Question> questions = generateQuestionsByType(chunkContent, questionType, questionCount, difficulty);
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
                    
                    List<Question> questions = generateQuestionsByType(chunkContent, questionType, reducedCount, difficulty);
                    if (!questions.isEmpty()) {
                        log.info("Strategy 2 (reduced count) succeeded on attempt {}: {}/{} questions for {} chunk {}", 
                                attempt, questions.size(), questionCount, questionType, chunkIndex);
                        updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " reduced count success");
                        return questions;
                    }
                } catch (Exception e) {
                    log.warn("Strategy 2 (reduced count) attempt {} failed for {} chunk {}: {}", 
                            attempt, questionType, chunkIndex, e.getMessage());
                    updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " reduced count attempt " + attempt + " failed");
                    // Continue to next attempt unless this is the last one
                }
            }
        }

        // Strategy 3: Try with easier difficulty (if not already EASY)
        if (difficulty != Difficulty.EASY) {
            updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " using easier difficulty");
            
            try {
                Difficulty easierDifficulty = getEasierDifficulty(difficulty);
                log.debug("Strategy 3: Trying with {} difficulty for {} chunk {}", 
                        easierDifficulty, questionType, chunkIndex);
                
                List<Question> questions = generateQuestionsByType(chunkContent, questionType, questionCount, easierDifficulty);
                if (!questions.isEmpty()) {
                    log.info("Strategy 3 (easier difficulty) succeeded: {}/{} questions for {} chunk {}", 
                            questions.size(), questionCount, questionType, chunkIndex);
                    updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " easier difficulty success");
                    return questions;
                }
            } catch (Exception e) {
                log.warn("Strategy 3 (easier difficulty) failed for {} chunk {}: {}", questionType, chunkIndex, e.getMessage());
                updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " easier difficulty failed");
            }
        }

        // Strategy 4: Try alternative question type that might work better with this content
        QuestionType alternativeType = findAlternativeQuestionType(questionType);
        if (alternativeType != null) {
            updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": trying " + alternativeType + " instead of " + questionType);
            
            try {
                log.debug("Strategy 4: Trying alternative type {} instead of {} for chunk {}", 
                        alternativeType, questionType, chunkIndex);
                
                List<Question> questions = generateQuestionsByType(chunkContent, alternativeType, questionCount, difficulty);
                if (!questions.isEmpty()) {
                    log.info("Strategy 4 (alternative type) succeeded: {} {} questions instead of {} for chunk {}", 
                            questions.size(), alternativeType, questionType, chunkIndex);
                    updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + alternativeType + " alternative success");
                    return questions;
                }
            } catch (Exception e) {
                log.warn("Strategy 4 (alternative type) failed for {} chunk {}: {}", alternativeType, chunkIndex, e.getMessage());
                updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + alternativeType + " alternative failed");
            }
        }

        // Strategy 5: Last resort - try the most reliable question type (MCQ_SINGLE)
        if (questionType != QuestionType.MCQ_SINGLE) {
            updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": last resort MCQ_SINGLE attempt");
            
            try {
                log.debug("Strategy 5: Last resort - trying MCQ_SINGLE for chunk {}", chunkIndex);
                
                List<Question> questions = generateQuestionsByType(chunkContent, QuestionType.MCQ_SINGLE, questionCount, difficulty);
                if (!questions.isEmpty()) {
                    log.info("Strategy 5 (last resort MCQ) succeeded: {} questions for chunk {} (requested {})", 
                            questions.size(), chunkIndex, questionType);
                    updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": MCQ_SINGLE last resort success");
                    return questions;
                }
            } catch (Exception e) {
                log.error("Strategy 5 (last resort MCQ) failed for chunk {}: {}", chunkIndex, e.getMessage());
                updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": all fallback strategies failed");
            }
        }

        log.error("All fallback strategies failed for {} questions of type {} in chunk {}", 
                questionCount, questionType, chunkIndex);
        updateJobStatusSafely(jobId, "Chunk " + chunkIndex + ": " + questionType + " generation failed completely");
        return new ArrayList<>();
    }

    /**
     * Get an easier difficulty level for fallback attempts
     */
    private Difficulty getEasierDifficulty(Difficulty current) {
        return switch (current) {
            case HARD -> Difficulty.MEDIUM;
            case MEDIUM -> Difficulty.EASY;
            case EASY -> Difficulty.EASY; // Already easiest
        };
    }

    /**
     * Find an alternative question type that might work better with certain content
     */
    private QuestionType findAlternativeQuestionType(QuestionType original) {
        return switch (original) {
            case ORDERING, HOTSPOT, TRUE_FALSE, MCQ_MULTI -> QuestionType.MCQ_SINGLE;
            case COMPLIANCE, OPEN, MCQ_SINGLE -> QuestionType.TRUE_FALSE;
            case FILL_GAP -> QuestionType.OPEN;
            default -> null;
        };
    }

    /**
     * Find question types that are missing or under-represented
     */
    private Map<QuestionType, Integer> findMissingQuestionTypes(
            Map<QuestionType, Integer> requested, 
            Map<QuestionType, Integer> generated) {
        
        Map<QuestionType, Integer> missing = new EnumMap<>(QuestionType.class);
        
        for (Map.Entry<QuestionType, Integer> entry : requested.entrySet()) {
            QuestionType type = entry.getKey();
            int requestedCount = entry.getValue();
            int generatedCount = generated.getOrDefault(type, 0);
            
            if (generatedCount < requestedCount) {
                missing.put(type, requestedCount - generatedCount);
            }
        }
        
        return missing;
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
            UUID jobId) {
        
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
                            jobId
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
    @Transactional(readOnly = true)
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
        @Setter
        @Getter
        private int totalChunks;
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

        public void addError(String error) {
            errors.add(error);
        }

        public double getProgressPercentage() {
            if (totalChunks == 0) return 0.0;
            return (double) processedChunks.get() / totalChunks * 100.0;
        }

        public Duration getElapsedTime() {
            return Duration.between(startTime, Instant.now());
        }

        // Getters and setters
        public int getProcessedChunks() {
            return processedChunks.get();
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
    @Transactional
    public QuizGenerationJob updateJobStatusToProcessing(UUID jobId) {
        QuizGenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
        
        // Initialize lazy relationships we will need outside the transaction
        job.getUser().getId();
        job.getUser().getUsername();
        
        job.setStatus(GenerationStatus.PROCESSING);
        return jobRepository.save(job);
    }

    /**
     * Update job total chunks in a short transaction
     */
    @Transactional
    public void updateJobTotalChunks(UUID jobId, int totalChunks) {
        QuizGenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
        job.setTotalChunks(totalChunks);
        jobRepository.save(job);
    }
}