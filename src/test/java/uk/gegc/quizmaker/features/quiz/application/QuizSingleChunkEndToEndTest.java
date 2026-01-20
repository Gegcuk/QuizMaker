package uk.gegc.quizmaker.features.quiz.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for single-chunk quiz generation feature.
 * Tests the complete flow from document setup through async generation to quiz creation,
 * verifying that single-chunk documents generate only one consolidated quiz.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:mysql://localhost:3306/quizmaker_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&createDatabaseIfNotExist=true",
    "spring.datasource.username=bestuser",
    "spring.datasource.password=bestuser",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
    "spring.jpa.database-platform=org.hibernate.dialect.MySQL8Dialect",
    "spring.jpa.hibernate.ddl-auto=update"
})
@ActiveProfiles("test-mysql")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Single-Chunk Quiz End-to-End Tests")
class QuizSingleChunkEndToEndTest {

    @Autowired
    private QuizServiceImpl quizService;

    @Autowired
    private AiQuizGenerationService aiQuizGenerationService;

    @Autowired
    private QuizGenerationJobRepository jobRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private QuizGenerationJobService jobService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private User testUser;
    private Document singleChunkDocument;
    private String uniqueId;

    @BeforeEach
    void setUp() {
        uniqueId = UUID.randomUUID().toString().substring(0, 8);
        cleanupDatabase();
        
        // Create test user
        testUser = new User();
        testUser.setUsername("e2euser_" + uniqueId);
        testUser.setEmail("e2e_" + uniqueId + "@example.com");
        testUser.setHashedPassword("password");
        testUser = userRepository.save(testUser);

        // Create document with single chunk
        singleChunkDocument = createDocumentWithSingleChunk();
    }

    @Test
    @DisplayName("E2E: Single-chunk document should generate exactly one quiz")
    void singleChunkDocument_shouldGenerateOnlyOneQuiz() throws Exception {
        // Given
        Map<QuestionType, Integer> questionsPerType = new HashMap<>();
        questionsPerType.put(QuestionType.MCQ_SINGLE, 3);
        questionsPerType.put(QuestionType.TRUE_FALSE, 2);
        
        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                singleChunkDocument.getId(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Single Chunk E2E Quiz",
                "E2E test for single chunk",
                questionsPerType,
                Difficulty.MEDIUM,
                null,
                null,
                null
        );

        // When
        UUID jobId = createJobOnly(testUser.getUsername(), request);
        QuizGenerationJob jobToProcess = transactionTemplate.execute(status -> 
            jobRepository.findById(jobId).orElseThrow()
        );
        aiQuizGenerationService.generateQuizFromDocumentAsync(jobToProcess, request);

        waitForJobCompletion(jobId, Duration.ofMinutes(2));
        waitForEventHandlerCompletion(jobId, Duration.ofMinutes(1));

        // Then
        QuizGenerationJob completedJob = transactionTemplate.execute(status -> 
            jobRepository.findById(jobId).orElseThrow()
        );
        
        assertThat(completedJob.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(completedJob.getGeneratedQuizId()).isNotNull();

        // Verify ONLY ONE quiz was created
        List<Quiz> allQuizzes = transactionTemplate.execute(status -> 
            quizRepository.findAll()
        );
        
        assertThat(allQuizzes).hasSize(1);
        Quiz consolidatedQuiz = allQuizzes.get(0);
        assertThat(consolidatedQuiz.getId()).isEqualTo(completedJob.getGeneratedQuizId());
        assertThat(consolidatedQuiz.getTitle()).isEqualTo("Single Chunk E2E Quiz");
        assertThat(consolidatedQuiz.getDescription()).isEqualTo("E2E test for single chunk");
    }

    @Test
    @DisplayName("E2E: SPECIFIC_CHUNKS scope with single chunk should create only one quiz")
    void specificChunksScope_withSingleChunk_shouldCreateOnlyOneQuiz() throws Exception {
        // Given
        Map<QuestionType, Integer> questionsPerType = new HashMap<>();
        questionsPerType.put(QuestionType.MCQ_SINGLE, 2);
        
        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                singleChunkDocument.getId(),
                QuizScope.SPECIFIC_CHUNKS,
                List.of(0), // Only chunk 0
                null,
                null,
                "Specific Chunk E2E Quiz",
                "E2E test for specific single chunk",
                questionsPerType,
                Difficulty.MEDIUM,
                null,
                null,
                null
        );

        // When
        UUID jobId = createJobOnly(testUser.getUsername(), request);
        QuizGenerationJob jobToProcess = transactionTemplate.execute(status -> 
            jobRepository.findById(jobId).orElseThrow()
        );
        aiQuizGenerationService.generateQuizFromDocumentAsync(jobToProcess, request);

        waitForJobCompletion(jobId, Duration.ofMinutes(2));
        waitForEventHandlerCompletion(jobId, Duration.ofMinutes(1));

        // Then
        List<Quiz> allQuizzes = transactionTemplate.execute(status -> 
            quizRepository.findAll()
        );
        
        assertThat(allQuizzes).hasSize(1);
        assertThat(allQuizzes.get(0).getTitle()).isEqualTo("Specific Chunk E2E Quiz");
    }

    @Test
    @DisplayName("E2E: SPECIFIC_CHAPTER scope with single chapter should create only one quiz")
    void specificChapterScope_withSingleChapter_shouldCreateOnlyOneQuiz() throws Exception {
        // Given
        Map<QuestionType, Integer> questionsPerType = new HashMap<>();
        questionsPerType.put(QuestionType.MCQ_SINGLE, 2);
        
        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                singleChunkDocument.getId(),
                QuizScope.SPECIFIC_CHAPTER,
                null,
                null,
                1, // Chapter 1 (which is the only chapter)
                "Chapter E2E Quiz",
                "E2E test for single chapter",
                questionsPerType,
                Difficulty.MEDIUM,
                null,
                null,
                null
        );

        // When
        UUID jobId = createJobOnly(testUser.getUsername(), request);
        QuizGenerationJob jobToProcess = transactionTemplate.execute(status -> 
            jobRepository.findById(jobId).orElseThrow()
        );
        aiQuizGenerationService.generateQuizFromDocumentAsync(jobToProcess, request);

        waitForJobCompletion(jobId, Duration.ofMinutes(2));
        waitForEventHandlerCompletion(jobId, Duration.ofMinutes(1));

        // Then
        List<Quiz> allQuizzes = transactionTemplate.execute(status -> 
            quizRepository.findAll()
        );
        
        assertThat(allQuizzes).hasSize(1);
    }

    @Test
    @DisplayName("E2E: Duplicate event should be idempotent (no duplicate quiz)")
    void duplicateEvent_shouldBeIdempotent() throws Exception {
        // Given - Create a single-chunk document and generate questions manually
        Map<QuestionType, Integer> questionsPerType = new HashMap<>();
        questionsPerType.put(QuestionType.MCQ_SINGLE, 2);
        
        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                singleChunkDocument.getId(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Idempotency Test Quiz",
                "Testing duplicate event handling",
                questionsPerType,
                Difficulty.MEDIUM,
                null,
                null,
                null
        );

        UUID jobId = createJobOnly(testUser.getUsername(), request);
        
        // Simulate questions generated (bypass actual AI generation)
        Map<Integer, List<Question>> chunkQuestions = new HashMap<>();
        List<Question> questions = new ArrayList<>();
        questions.add(createMockQuestion("Q1", QuestionType.MCQ_SINGLE));
        questions.add(createMockQuestion("Q2", QuestionType.MCQ_SINGLE));
        chunkQuestions.put(0, questions);

        // When - Trigger quiz creation twice to simulate duplicate event
        transactionTemplate.execute(status -> {
            quizService.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, request);
            return null;
        });

        int quizCountAfterFirst = transactionTemplate.execute(status -> 
            quizRepository.findAll().size()
        );

        // Second call - should be idempotent
        try {
            transactionTemplate.execute(status -> {
                quizService.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, request);
                return null;
            });
        } catch (Exception e) {
            // May fail due to job already completed, which is acceptable
        }

        // Then - Verify only one quiz exists (idempotent)
        List<Quiz> allQuizzes = transactionTemplate.execute(status -> 
            quizRepository.findAll()
        );
        
        assertThat(allQuizzes).hasSize(quizCountAfterFirst);
        assertThat(allQuizzes).hasSize(1); // Should still be just one quiz
        
        QuizGenerationJob job = transactionTemplate.execute(status -> 
            jobRepository.findById(jobId).orElseThrow()
        );
        assertThat(job.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
    }

    @Test
    @DisplayName("E2E: Job completion should have correct counts for single chunk")
    void jobCompletion_shouldHaveCorrectCounts() throws Exception {
        // Given
        Map<QuestionType, Integer> questionsPerType = new HashMap<>();
        questionsPerType.put(QuestionType.MCQ_SINGLE, 3);
        
        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                singleChunkDocument.getId(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Count Test Quiz",
                "Testing question counts",
                questionsPerType,
                Difficulty.MEDIUM,
                null,
                null,
                null
        );

        // When
        UUID jobId = createJobOnly(testUser.getUsername(), request);
        QuizGenerationJob jobToProcess = transactionTemplate.execute(status -> 
            jobRepository.findById(jobId).orElseThrow()
        );
        aiQuizGenerationService.generateQuizFromDocumentAsync(jobToProcess, request);

        waitForJobCompletion(jobId, Duration.ofMinutes(2));
        waitForEventHandlerCompletion(jobId, Duration.ofMinutes(1));

        // Then
        QuizGenerationJob completedJob = transactionTemplate.execute(status -> 
            jobRepository.findById(jobId).orElseThrow()
        );
        
        assertThat(completedJob.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(completedJob.getTotalQuestionsGenerated()).isGreaterThan(0);
        assertThat(completedJob.getCompletedAt()).isNotNull();
        
        // Verify quiz has the same question count
        Integer questionCount = transactionTemplate.execute(status -> {
            Quiz quiz = quizRepository.findById(completedJob.getGeneratedQuizId()).orElseThrow();
            return quiz.getQuestions().size();
        });
        
        assertThat(questionCount).isEqualTo(completedJob.getTotalQuestionsGenerated());
    }

    @Test
    @DisplayName("E2E: Multi-chunk document should create multiple quizzes (baseline)")
    void multiChunkDocument_shouldCreateMultipleQuizzes() throws Exception {
        // Given - Create a document with multiple chunks
        Document multiChunkDoc = createDocumentWithMultipleChunks();
        
        Map<QuestionType, Integer> questionsPerType = new HashMap<>();
        questionsPerType.put(QuestionType.MCQ_SINGLE, 2);
        
        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                multiChunkDoc.getId(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Multi Chunk E2E Quiz",
                "E2E test for multi chunk",
                questionsPerType,
                Difficulty.MEDIUM,
                null,
                null,
                null
        );

        // When
        UUID jobId = createJobOnly(testUser.getUsername(), request);
        QuizGenerationJob jobToProcess = transactionTemplate.execute(status -> 
            jobRepository.findById(jobId).orElseThrow()
        );
        aiQuizGenerationService.generateQuizFromDocumentAsync(jobToProcess, request);

        waitForJobCompletion(jobId, Duration.ofMinutes(2));
        waitForEventHandlerCompletion(jobId, Duration.ofMinutes(1));

        // Then - Should create 2 per-chunk quizzes + 1 consolidated = 3 total
        List<Quiz> allQuizzes = transactionTemplate.execute(status -> 
            quizRepository.findAll()
        );
        
        assertThat(allQuizzes).hasSizeGreaterThanOrEqualTo(3);
        
        // Verify job points to consolidated quiz (the one with most questions)
        QuizGenerationJob completedJob = transactionTemplate.execute(status -> 
            jobRepository.findById(jobId).orElseThrow()
        );
        
        Quiz consolidatedQuiz = transactionTemplate.execute(status -> 
            quizRepository.findById(completedJob.getGeneratedQuizId()).orElseThrow()
        );
        
        // Consolidated quiz should have title matching request
        assertThat(consolidatedQuiz.getTitle()).isEqualTo("Multi Chunk E2E Quiz");
    }

    // ========== Helper Methods ==========

    private Document createDocumentWithSingleChunk() {
        Document doc = new Document();
        doc.setTitle("Single Chunk Document " + uniqueId);
        doc.setOriginalFilename("single-chunk-" + uniqueId + ".pdf");
        doc.setContentType("application/pdf");
        doc.setFileSize(1000L);
        doc.setFilePath("/uploads/single-chunk-" + uniqueId + ".pdf");
        doc.setStatus(Document.DocumentStatus.PROCESSED);
        doc.setUploadedBy(testUser);
        doc.setTotalChunks(1);
        doc = documentRepository.save(doc);

        // Create single chunk
        DocumentChunk chunk = createChunk(doc, 0, 
            "Introduction to Java\n" +
            "Java is a high-level, object-oriented programming language. " +
            "It was developed by Sun Microsystems in 1995. " +
            "Java applications are compiled to bytecode and run on the Java Virtual Machine (JVM). " +
            "Key features include platform independence, automatic memory management, and strong typing. " +
            "The Spring Framework is a popular framework for building enterprise Java applications.");
        
        doc.getChunks().add(chunk);
        return documentRepository.save(doc);
    }

    private Document createDocumentWithMultipleChunks() {
        Document doc = new Document();
        doc.setTitle("Multi Chunk Document " + uniqueId);
        doc.setOriginalFilename("multi-chunk-" + uniqueId + ".pdf");
        doc.setContentType("application/pdf");
        doc.setFileSize(2000L);
        doc.setFilePath("/uploads/multi-chunk-" + uniqueId + ".pdf");
        doc.setStatus(Document.DocumentStatus.PROCESSED);
        doc.setUploadedBy(testUser);
        doc.setTotalChunks(2);
        doc = documentRepository.save(doc);

        // Create multiple chunks
        DocumentChunk chunk0 = createChunk(doc, 0, 
            "Chapter 1: Basics\n" +
            "This chapter covers fundamental concepts of programming. " +
            "Variables store data, functions perform operations.");
        
        DocumentChunk chunk1 = createChunk(doc, 1, 
            "Chapter 2: Advanced\n" +
            "This chapter covers advanced topics including design patterns and best practices.");
        
        doc.getChunks().add(chunk0);
        doc.getChunks().add(chunk1);
        return documentRepository.save(doc);
    }

    private DocumentChunk createChunk(Document doc, int index, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(doc);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setTitle("Chapter " + (index + 1));
        chunk.setStartPage(index * 10 + 1);
        chunk.setEndPage(index * 10 + 10);
        chunk.setWordCount(content.split("\\s+").length);
        chunk.setCharacterCount(content.length());
        chunk.setCreatedAt(LocalDateTime.now());
        chunk.setChapterTitle("Chapter " + (index + 1));
        chunk.setChapterNumber(index + 1);
        chunk.setChunkType(DocumentChunk.ChunkType.CHAPTER);
        return chunk;
    }

    private Question createMockQuestion(String text, QuestionType type) {
        Question question = new Question();
        question.setQuestionText(text);
        question.setType(type);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setContent("{\"question\":\"" + text + "\",\"answer\":true}");
        question.setExplanation("Explanation for " + text);
        return question;
    }

    private UUID createJobOnly(String username, GenerateQuizFromDocumentRequest request) throws JsonProcessingException {
        return transactionTemplate.execute(status -> {
            try {
                User user = userRepository.findByUsername(username)
                        .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));

                int totalChunks = aiQuizGenerationService.calculateTotalChunks(request.documentId(), request);
                int estimatedSeconds = aiQuizGenerationService.calculateEstimatedGenerationTime(
                        totalChunks, request.questionsPerType());

                QuizGenerationJob job = jobService.createJob(user, request.documentId(),
                        objectMapper.writeValueAsString(request), totalChunks, estimatedSeconds);
                
                return job.getId();
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void waitForJobCompletion(UUID jobId, Duration timeout) throws InterruptedException {
        Instant startTime = Instant.now();
        while (Duration.between(startTime, Instant.now()).compareTo(timeout) < 0) {
            QuizGenerationJob job = transactionTemplate.execute(status -> 
                jobRepository.findById(jobId).orElse(null)
            );
            
            if (job != null && job.getStatus().isTerminal()) {
                if (job.getStatus() == GenerationStatus.FAILED) {
                    System.out.println("Job " + jobId + " failed with error: " + job.getErrorMessage());
                }
                return;
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("Job did not complete within timeout: " + timeout);
    }

    private void waitForEventHandlerCompletion(UUID jobId, Duration timeout) throws InterruptedException {
        Instant startTime = Instant.now();
        while (Duration.between(startTime, Instant.now()).compareTo(timeout) < 0) {
            QuizGenerationJob job = transactionTemplate.execute(status -> 
                jobRepository.findById(jobId).orElse(null)
            );
            
            if (job != null && job.getStatus() == GenerationStatus.COMPLETED && job.getGeneratedQuizId() != null) {
                return;
            }
            Thread.sleep(500);
        }
        throw new RuntimeException("Event handler did not complete within timeout: " + timeout);
    }

    private void cleanupDatabase() {
        try {
            transactionTemplate.execute(status -> {
                jobRepository.deleteAll();
                quizRepository.deleteAll();
                documentRepository.deleteAll();
                userRepository.deleteAll();
                return null;
            });
        } catch (Exception e) {
            try {
                transactionTemplate.execute(status -> {
                    entityManager.createNativeQuery("DELETE FROM quiz_generation_jobs").executeUpdate();
                    entityManager.createNativeQuery("DELETE FROM quiz_questions").executeUpdate();
                    entityManager.createNativeQuery("DELETE FROM quiz_tags").executeUpdate();
                    entityManager.createNativeQuery("DELETE FROM quizzes").executeUpdate();
                    entityManager.createNativeQuery("DELETE FROM document_chunks").executeUpdate();
                    entityManager.createNativeQuery("DELETE FROM documents").executeUpdate();
                    entityManager.createNativeQuery("DELETE FROM users").executeUpdate();
                    return null;
                });
            } catch (Exception e2) {
                // Ignore cleanup errors
            }
        }
    }
}

