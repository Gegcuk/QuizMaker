package uk.gegc.quizmaker.service.ai.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.dto.quiz.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.dto.quiz.QuizScope;
import uk.gegc.quizmaker.exception.AIResponseParseException;
import uk.gegc.quizmaker.exception.AiServiceException;
import uk.gegc.quizmaker.exception.DocumentNotFoundException;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.document.Document;
import uk.gegc.quizmaker.model.document.DocumentChunk;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.model.quiz.GenerationStatus;
import uk.gegc.quizmaker.model.quiz.QuizGenerationJob;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.document.DocumentRepository;
import uk.gegc.quizmaker.repository.quiz.QuizGenerationJobRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.ai.AiQuizGenerationService;
import uk.gegc.quizmaker.service.ai.PromptTemplateService;
import uk.gegc.quizmaker.service.ai.parser.QuestionResponseParser;
import uk.gegc.quizmaker.service.quiz.QuizGenerationJobService;

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
    private final QuizGenerationJobService jobService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // In-memory tracking for generation progress (will be replaced with database in Phase 2)
    private final Map<UUID, GenerationProgress> generationProgress = new ConcurrentHashMap<>();

    @Override
    @Async
    public void generateQuizFromDocumentAsync(UUID jobId, GenerateQuizFromDocumentRequest request) {
        Instant startTime = Instant.now();
        log.info("Starting quiz generation for job {} with document {}", jobId, request.documentId());

        try {
            // Get the job from database and update status to PROCESSING
            QuizGenerationJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));

            job.setStatus(GenerationStatus.PROCESSING);
            jobRepository.save(job);

            // Initialize progress tracking
            GenerationProgress progress = new GenerationProgress();
            generationProgress.put(jobId, progress);

            // Validate document
            validateDocumentForGeneration(request.documentId(), job.getUser().getUsername());

            // Get document and chunks
            Document document = documentRepository.findById(request.documentId())
                    .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + request.documentId()));

            List<DocumentChunk> chunks = getChunksForScope(document, request);
            progress.setTotalChunks(chunks.size());

            // Update job with total chunks
            job.setTotalChunks(chunks.size());
            jobRepository.save(job);

            log.info("Processing {} chunks for document {}", chunks.size(), request.documentId());

            // Process chunks asynchronously
            List<CompletableFuture<List<Question>>> chunkFutures = chunks.stream()
                    .map(chunk -> generateQuestionsFromChunk(chunk, request.questionsPerType(), request.difficulty()))
                    .collect(Collectors.toList());

            // Collect all generated questions with progress tracking
            List<Question> allQuestions = new ArrayList<>();
            int processedChunks = 0;

            for (CompletableFuture<List<Question>> future : chunkFutures) {
                try {
                    List<Question> chunkQuestions = future.get();
                    allQuestions.addAll(chunkQuestions);
                    processedChunks++;

                    // Update progress in database
                    job.updateProgress(processedChunks, "Processing chunk " + processedChunks + " of " + chunks.size());
                    jobRepository.save(job);

                    log.debug("Completed chunk processing for job {}, total questions: {}",
                            jobId, allQuestions.size());
                } catch (Exception e) {
                    log.error("Error processing chunk for job {}", jobId, e);
                    progress.addError("Chunk processing failed: " + e.getMessage());

                    // Update job with error
                    job.updateProgress(processedChunks, "Error in chunk " + processedChunks);
                    jobRepository.save(job);
                }
            }

            // Organize questions by chunk for quiz collection creation
            Map<Integer, List<Question>> chunkQuestions = new HashMap<>();
            int chunkIndex = 0;
            for (CompletableFuture<List<Question>> future : chunkFutures) {
                try {
                    List<Question> chunkQuestionsList = future.get();
                    if (!chunkQuestionsList.isEmpty()) {
                        chunkQuestions.put(chunkIndex, chunkQuestionsList);
                    }
                    chunkIndex++;
                } catch (Exception e) {
                    log.error("Error getting questions for chunk {}", chunkIndex, e);
                }
            }

            // Create quiz collection (individual chunk quizzes + consolidated quiz)
            log.info("Quiz generation completed for job {} in {} seconds. Generated {} questions across {} chunks.",
                    jobId, Duration.between(startTime, Instant.now()).getSeconds(), allQuestions.size(), chunkQuestions.size());

            // Call quiz service to create the quiz collection
            // This will be implemented in the quiz service
            // quizService.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, request);

            // For now, mark job as completed with total questions
            job.markCompleted(null, allQuestions.size()); // TODO: Add generated quiz ID when quiz is created
            jobRepository.save(job);

            progress.setCompleted(true);
            progress.setGeneratedQuestions(allQuestions);

        } catch (Exception e) {
            log.error("Quiz generation failed for job {}", jobId, e);

            // Update job status to failed
            try {
                QuizGenerationJob job = jobRepository.findById(jobId).orElse(null);
                if (job != null) {
                    job.markFailed("Generation failed: " + e.getMessage());
                    jobRepository.save(job);
                }
            } catch (Exception saveError) {
                log.error("Failed to update job status to failed for job {}", jobId, saveError);
            }

            GenerationProgress progress = generationProgress.get(jobId);
            if (progress != null) {
                progress.setCompleted(true);
                progress.addError("Generation failed: " + e.getMessage());
            }

            throw new AiServiceException("Failed to generate quiz: " + e.getMessage(), e);
        }
    }

    @Override
    @Async
    public CompletableFuture<List<Question>> generateQuestionsFromChunk(
            DocumentChunk chunk,
            Map<QuestionType, Integer> questionsPerType,
            Difficulty difficulty
    ) {
        return CompletableFuture.supplyAsync(() -> {
            Instant startTime = Instant.now();
            log.debug("Generating questions for chunk {} (index: {})", chunk.getId(), chunk.getChunkIndex());

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
                        try {
                            List<Question> questions = generateQuestionsByType(
                                    chunk.getContent(),
                                    questionType,
                                    questionCount,
                                    difficulty
                            );
                            allQuestions.addAll(questions);

                            log.debug("Generated {} {} questions for chunk {}",
                                    questions.size(), questionType, chunk.getChunkIndex());

                        } catch (Exception e) {
                            log.error("Error generating {} questions of type {} for chunk {}",
                                    questionCount, questionType, chunk.getChunkIndex(), e);
                            chunkErrors.add(String.format("Failed to generate %s %s questions: %s",
                                    questionCount, questionType, e.getMessage()));
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

                log.debug("Completed chunk {} processing in {} ms with {} questions",
                        chunk.getChunkIndex(), Duration.between(startTime, Instant.now()).toMillis(),
                        allQuestions.size());

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

        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                // Build prompt for this question type
                String prompt = promptTemplateService.buildPromptForChunk(
                        chunkContent, questionType, questionCount, difficulty
                );

                log.debug("Sending prompt to AI for {} questions of type {} (attempt {})",
                        questionCount, questionType, retryCount + 1);

                // Send to AI with timeout
                ChatResponse response = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .chatResponse();

                if (response == null || response.getResult() == null) {
                    throw new AiServiceException("No response received from AI service");
                }

                String aiResponse = response.getResult().getOutput().getText();

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
                    continue;
                } else {
                    throw new AiServiceException("Failed to parse AI response after " + maxRetries + " attempts", e);
                }

            } catch (Exception e) {
                log.error("Error generating {} questions of type {} (attempt {})",
                        questionCount, questionType, retryCount + 1, e);

                if (retryCount < maxRetries - 1) {
                    retryCount++;
                    log.info("Retrying due to error for type {}", questionType);
                    continue;
                } else {
                    throw new AiServiceException("Failed to generate questions after " + maxRetries + " attempts: " + e.getMessage(), e);
                }
            }
        }

        throw new AiServiceException("Failed to generate questions after " + maxRetries + " attempts");
    }

    @Override
    public void validateDocumentForGeneration(UUID documentId, String username) {
        Document document = documentRepository.findById(documentId)
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

        // Calculate total questions
        int totalQuestions = questionsPerType.values().stream().mapToInt(Integer::intValue).sum();

        // Estimate: base time per chunk + additional time for question types
        int estimatedTime = (totalChunks * baseTimePerChunk) + (questionsPerType.size() * timePerQuestionType);

        // Add buffer for network latency and processing
        estimatedTime = (int) (estimatedTime * 1.2);

        return estimatedTime;
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
            Document document = documentRepository.findById(documentId)
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
     * Get chunks based on the quiz scope
     */
    private List<DocumentChunk> getChunksForScope(Document document, GenerateQuizFromDocumentRequest request) {
        List<DocumentChunk> allChunks = document.getChunks();

        if (request.quizScope() == null || request.quizScope() == QuizScope.ENTIRE_DOCUMENT) {
            return allChunks;
        }

        switch (request.quizScope()) {
            case SPECIFIC_CHUNKS:
                if (request.chunkIndices() == null || request.chunkIndices().isEmpty()) {
                    throw new IllegalArgumentException("Chunk indices must be specified for SPECIFIC_CHUNKS scope");
                }
                return allChunks.stream()
                        .filter(chunk -> request.chunkIndices().contains(chunk.getChunkIndex()))
                        .collect(Collectors.toList());

            case SPECIFIC_CHAPTER:
                return allChunks.stream()
                        .filter(chunk -> matchesChapter(chunk, request.chapterTitle(), request.chapterNumber()))
                        .collect(Collectors.toList());

            case SPECIFIC_SECTION:
                return allChunks.stream()
                        .filter(chunk -> matchesSection(chunk, request.chapterTitle(), request.chapterNumber()))
                        .collect(Collectors.toList());

            default:
                return allChunks;
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
        private int totalChunks;
        private boolean completed = false;
        private List<Question> generatedQuestions = new ArrayList<>();
        private List<String> errors = new ArrayList<>();
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

        public int getTotalChunks() {
            return totalChunks;
        }

        public void setTotalChunks(int totalChunks) {
            this.totalChunks = totalChunks;
        }

        public boolean isCompleted() {
            return completed;
        }

        public void setCompleted(boolean completed) {
            this.completed = completed;
        }

        public List<Question> getGeneratedQuestions() {
            return generatedQuestions;
        }

        public void setGeneratedQuestions(List<Question> generatedQuestions) {
            this.generatedQuestions = generatedQuestions;
        }

        public List<String> getErrors() {
            return errors;
        }

        public Instant getStartTime() {
            return startTime;
        }
    }
} 