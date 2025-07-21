package uk.gegc.quizmaker.service.ai.impl;

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
import uk.gegc.quizmaker.repository.document.DocumentRepository;
import uk.gegc.quizmaker.service.ai.AiQuizGenerationService;
import uk.gegc.quizmaker.service.ai.PromptTemplateService;
import uk.gegc.quizmaker.service.ai.parser.QuestionResponseParser;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    
    // In-memory tracking for generation progress (will be replaced with database in Phase 2)
    private final Map<UUID, GenerationProgress> generationProgress = new ConcurrentHashMap<>();

    @Override
    @Async
    public void generateQuizFromDocumentAsync(UUID jobId, GenerateQuizFromDocumentRequest request) {
        Instant startTime = Instant.now();
        log.info("Starting quiz generation for job {} with document {}", jobId, request.documentId());
        
        try {
            // Initialize progress tracking
            GenerationProgress progress = new GenerationProgress();
            generationProgress.put(jobId, progress);
            
            // Validate document (username will be passed separately in actual implementation)
            validateDocumentForGeneration(request.documentId(), "username"); // TODO: Get username from context
            
            // Get document and chunks
            Document document = documentRepository.findById(request.documentId())
                    .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + request.documentId()));
            
            List<DocumentChunk> chunks = getChunksForScope(document, request);
            progress.setTotalChunks(chunks.size());
            
            log.info("Processing {} chunks for document {}", chunks.size(), request.documentId());
            
            // Process chunks asynchronously
            List<CompletableFuture<List<Question>>> chunkFutures = chunks.stream()
                    .map(chunk -> generateQuestionsFromChunk(chunk, request.questionsPerType(), request.difficulty()))
                    .collect(Collectors.toList());
            
            // Wait for all chunks to complete
            CompletableFuture<Void> allChunksFuture = CompletableFuture.allOf(
                    chunkFutures.toArray(new CompletableFuture[0])
            );
            
            // Collect all generated questions
            List<Question> allQuestions = new ArrayList<>();
            for (CompletableFuture<List<Question>> future : chunkFutures) {
                try {
                    List<Question> chunkQuestions = future.get();
                    allQuestions.addAll(chunkQuestions);
                    progress.incrementProcessedChunks();
                    log.debug("Completed chunk processing for job {}, total questions: {}", 
                            jobId, allQuestions.size());
                } catch (Exception e) {
                    log.error("Error processing chunk for job {}", jobId, e);
                    progress.addError("Chunk processing failed: " + e.getMessage());
                }
            }
            
            // Wait for all chunks to complete
            allChunksFuture.get();
            
            // Create final quiz (this will be implemented in Phase 3)
            log.info("Quiz generation completed for job {} in {} seconds. Generated {} questions.", 
                    jobId, Duration.between(startTime, Instant.now()).getSeconds(), allQuestions.size());
            
            progress.setCompleted(true);
            progress.setGeneratedQuestions(allQuestions);
            
        } catch (Exception e) {
            log.error("Quiz generation failed for job {}", jobId, e);
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
            
            try {
                for (Map.Entry<QuestionType, Integer> entry : questionsPerType.entrySet()) {
                    QuestionType questionType = entry.getKey();
                    Integer questionCount = entry.getValue();
                    
                    if (questionCount > 0) {
                        List<Question> questions = generateQuestionsByType(
                                chunk.getContent(),
                                questionType,
                                questionCount,
                                difficulty
                        );
                        allQuestions.addAll(questions);
                        
                        log.debug("Generated {} {} questions for chunk {}", 
                                questions.size(), questionType, chunk.getChunkIndex());
                    }
                }
                
                log.debug("Completed chunk {} processing in {} ms", 
                        chunk.getChunkIndex(), Duration.between(startTime, Instant.now()).toMillis());
                
                return allQuestions;
                
            } catch (Exception e) {
                log.error("Error generating questions for chunk {}", chunk.getChunkIndex(), e);
                throw new AiServiceException("Failed to generate questions for chunk: " + e.getMessage(), e);
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
        try {
            // Build prompt for this question type
            String prompt = promptTemplateService.buildPromptForChunk(
                    chunkContent, questionType, questionCount, difficulty
            );
            
            log.debug("Sending prompt to AI for {} questions of type {}", questionCount, questionType);
            
            // Send to AI
            ChatResponse response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .chatResponse();
            
            if (response == null || response.getResult() == null) {
                throw new AiServiceException("No response received from AI service");
            }
            
            String aiResponse = response.getResult().getOutput().getText();
            
            // Parse AI response into questions
            List<Question> questions = questionResponseParser.parseQuestionsFromAIResponse(
                    aiResponse, questionType
            );
            
            // Validate we got the expected number of questions
            if (questions.size() != questionCount) {
                log.warn("Expected {} questions but got {} for type {}", 
                        questionCount, questions.size(), questionType);
            }
            
            return questions;
            
        } catch (Exception e) {
            log.error("Error generating {} questions of type {}", questionCount, questionType, e);
            throw new AiServiceException("Failed to generate questions: " + e.getMessage(), e);
        }
    }

    @Override
    public void validateDocumentForGeneration(UUID documentId, String username) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + documentId));
        
        // Check if document belongs to user
        if (!document.getUploadedBy().getUsername().equals(username)) {
            throw new ResourceNotFoundException("Document not found or access denied");
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
        public int getProcessedChunks() { return processedChunks.get(); }
        public int getTotalChunks() { return totalChunks; }
        public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }
        public boolean isCompleted() { return completed; }
        public void setCompleted(boolean completed) { this.completed = completed; }
        public List<Question> getGeneratedQuestions() { return generatedQuestions; }
        public void setGeneratedQuestions(List<Question> generatedQuestions) { this.generatedQuestions = generatedQuestions; }
        public List<String> getErrors() { return errors; }
        public Instant getStartTime() { return startTime; }
    }
} 