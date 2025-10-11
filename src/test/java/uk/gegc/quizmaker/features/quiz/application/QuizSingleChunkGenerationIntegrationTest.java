package uk.gegc.quizmaker.features.quiz.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for single-chunk quiz generation feature.
 * Tests the complete flow from event handling to database persistence,
 * verifying that single-chunk documents generate only one consolidated quiz.
 */
@DisplayName("Single-Chunk Quiz Generation Integration Tests")
class QuizSingleChunkGenerationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private QuizServiceImpl quizService;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private QuizGenerationJobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private User testUser;
    private Document testDocument;
    private Category testCategory;

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = new User();
        testUser.setUsername("testuser-" + UUID.randomUUID().toString().substring(0, 8));
        testUser.setEmail(testUser.getUsername() + "@example.com");
        testUser.setHashedPassword("hashedPassword");
        testUser.setActive(true);
        testUser = userRepository.save(testUser);

        // Create test category
        testCategory = categoryRepository.findByName("AI Generated")
                .orElseGet(() -> {
                    Category category = new Category();
                    category.setName("AI Generated");
                    category.setDescription("AI generated quizzes");
                    return categoryRepository.save(category);
                });

        // Create test document with single chunk
        testDocument = new Document();
        testDocument.setOriginalFilename("test-doc.txt");
        testDocument.setContentType("text/plain");
        testDocument.setFileSize(1000L);
        testDocument.setFilePath("/test/path/test-doc.txt");
        testDocument.setStatus(Document.DocumentStatus.PROCESSED);
        testDocument.setUploadedBy(testUser);
        testDocument.setUploadedAt(LocalDateTime.now());
        testDocument.setProcessedAt(LocalDateTime.now());
        testDocument.setTotalChunks(1);
        testDocument = documentRepository.save(testDocument);

        // Create single chunk for the document
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(testDocument);
        chunk.setChunkIndex(0);
        chunk.setTitle("Test Chunk");
        chunk.setContent("Test content for quiz generation");
        chunk.setStartPage(1);
        chunk.setEndPage(1);
        chunk.setWordCount(50);
        chunk.setCharacterCount(200);
        chunk.setCreatedAt(LocalDateTime.now());
        testDocument.getChunks().add(chunk);
        testDocument = documentRepository.save(testDocument);
    }

    @Test
    @DisplayName("Single chunk: should create exactly one consolidated quiz")
    void singleChunk_shouldCreateExactlyOneConsolidatedQuiz() {
        // Given
        QuizGenerationJob job = createTestJob();
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();
        GenerateQuizFromDocumentRequest request = createTestRequest("Single Chunk Quiz", "Description for single chunk");

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(job.getId(), singleChunkQuestions, request);

        // Then
        List<Quiz> allQuizzes = quizRepository.findAll();
        assertThat(allQuizzes).hasSize(1);
        
        Quiz consolidatedQuiz = allQuizzes.get(0);
        assertThat(consolidatedQuiz.getTitle()).isEqualTo("Single Chunk Quiz");
        assertThat(consolidatedQuiz.getDescription()).isEqualTo("Description for single chunk");
        assertThat(consolidatedQuiz.getQuestions()).hasSize(3);
        assertThat(consolidatedQuiz.getCreator().getId()).isEqualTo(testUser.getId());

        // Verify job completion
        QuizGenerationJob updatedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(updatedJob.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(updatedJob.getGeneratedQuizId()).isEqualTo(consolidatedQuiz.getId());
        assertThat(updatedJob.getTotalQuestionsGenerated()).isEqualTo(3);
    }

    @Test
    @DisplayName("Multi-chunk: should create per-chunk quizzes and consolidated quiz")
    void multiChunk_shouldCreatePerChunkAndConsolidatedQuizzes() {
        // Given
        QuizGenerationJob job = createTestJob();
        Map<Integer, List<Question>> multiChunkQuestions = createMultiChunkQuestions();
        GenerateQuizFromDocumentRequest request = createTestRequest("Multi Chunk Quiz", "Description for multi chunk");

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(job.getId(), multiChunkQuestions, request);

        // Then
        List<Quiz> allQuizzes = quizRepository.findAll();
        assertThat(allQuizzes).hasSize(3); // 2 chunk quizzes + 1 consolidated

        // Find consolidated quiz (should have the most questions)
        Quiz consolidatedQuiz = allQuizzes.stream()
                .max(Comparator.comparingInt(q -> q.getQuestions().size()))
                .orElseThrow();
        
        assertThat(consolidatedQuiz.getTitle()).isEqualTo("Multi Chunk Quiz");
        assertThat(consolidatedQuiz.getQuestions()).hasSize(6); // 3 + 3 from two chunks

        // Verify job points to consolidated quiz
        QuizGenerationJob updatedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(updatedJob.getGeneratedQuizId()).isEqualTo(consolidatedQuiz.getId());
        assertThat(updatedJob.getTotalQuestionsGenerated()).isEqualTo(6);
    }

    @Test
    @DisplayName("SPECIFIC_CHUNKS with single index: should create only consolidated quiz")
    void specificChunksWithSingleIndex_shouldCreateOnlyConsolidatedQuiz() {
        // Given
        QuizGenerationJob job = createTestJob();
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();
        
        Map<QuestionType, Integer> questionsPerType = new HashMap<>();
        questionsPerType.put(QuestionType.MCQ_SINGLE, 3);
        
        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                testDocument.getId(),
                QuizScope.SPECIFIC_CHUNKS,
                List.of(0), // Single specific chunk
                null,
                null,
                "Specific Single Chunk Quiz",
                "Quiz for specific chunk",
                questionsPerType,
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(job.getId(), singleChunkQuestions, request);

        // Then
        List<Quiz> allQuizzes = quizRepository.findAll();
        assertThat(allQuizzes).hasSize(1);
        
        Quiz consolidatedQuiz = allQuizzes.get(0);
        assertThat(consolidatedQuiz.getTitle()).isEqualTo("Specific Single Chunk Quiz");
    }

    @Test
    @DisplayName("Title uniqueness: should append suffix when title exists")
    void titleUniqueness_shouldAppendSuffixWhenTitleExists() {
        // Given
        // Pre-insert quiz with same title
        Quiz existingQuiz = new Quiz();
        existingQuiz.setTitle("Duplicate Title Quiz");
        existingQuiz.setDescription("Existing quiz");
        existingQuiz.setCreator(testUser);
        existingQuiz.setCategory(testCategory);
        existingQuiz.setEstimatedTime(10);
        existingQuiz.setIsTimerEnabled(false);
        existingQuiz.setIsRepetitionEnabled(false);
        existingQuiz.setDifficulty(Difficulty.MEDIUM);
        existingQuiz.setVisibility(Visibility.PRIVATE);
        existingQuiz.setStatus(QuizStatus.DRAFT);
        quizRepository.save(existingQuiz);

        QuizGenerationJob job = createTestJob();
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();
        GenerateQuizFromDocumentRequest request = createTestRequest("Duplicate Title Quiz", "New quiz with duplicate title");

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(job.getId(), singleChunkQuestions, request);

        // Then
        List<Quiz> allQuizzes = quizRepository.findAll();
        assertThat(allQuizzes).hasSize(2);
        
        // Verify the new quiz has suffixed title
        Quiz newQuiz = allQuizzes.stream()
                .filter(q -> !q.getId().equals(existingQuiz.getId()))
                .findFirst()
                .orElseThrow();
        
        assertThat(newQuiz.getTitle()).isEqualTo("Duplicate Title Quiz-2");
        assertThat(newQuiz.getDescription()).isEqualTo("New quiz with duplicate title");
    }

    @Test
    @DisplayName("Null title: should use default 'Complete Document Quiz'")
    void nullTitle_shouldUseDefaultTitle() {
        // Given
        QuizGenerationJob job = createTestJob();
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();
        GenerateQuizFromDocumentRequest request = createTestRequest(null, null);

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(job.getId(), singleChunkQuestions, request);

        // Then
        List<Quiz> allQuizzes = quizRepository.findAll();
        assertThat(allQuizzes).hasSize(1);
        
        Quiz consolidatedQuiz = allQuizzes.get(0);
        assertThat(consolidatedQuiz.getTitle()).isEqualTo("Complete Document Quiz");
    }

    @Test
    @DisplayName("Question relationships: all questions should belong to consolidated quiz")
    void questionRelationships_shouldBelongToConsolidatedQuiz() {
        // Given
        QuizGenerationJob job = createTestJob();
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();
        GenerateQuizFromDocumentRequest request = createTestRequest("Relationship Test Quiz", "Testing relationships");

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(job.getId(), singleChunkQuestions, request);

        // Then
        List<Quiz> allQuizzes = quizRepository.findAll();
        assertThat(allQuizzes).hasSize(1);
        
        Quiz consolidatedQuiz = allQuizzes.get(0);
        assertThat(consolidatedQuiz.getQuestions()).isNotEmpty();
        
        // Verify all questions from the input are present
        Set<UUID> originalQuestionIds = new HashSet<>();
        singleChunkQuestions.values().forEach(questions -> 
            questions.forEach(q -> originalQuestionIds.add(q.getId()))
        );
        
        Set<UUID> savedQuestionIds = new HashSet<>();
        consolidatedQuiz.getQuestions().forEach(q -> savedQuestionIds.add(q.getId()));
        
        assertThat(savedQuestionIds).containsAll(originalQuestionIds);
    }

    @Test
    @DisplayName("Job completion fields: should be set correctly for single chunk")
    void jobCompletionFields_shouldBeSetCorrectlyForSingleChunk() {
        // Given
        QuizGenerationJob job = createTestJob();
        job.setTotalChunks(1);
        job.setProcessedChunks(0);
        job = jobRepository.save(job);
        
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();
        GenerateQuizFromDocumentRequest request = createTestRequest("Completion Test Quiz", "Testing completion");

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(job.getId(), singleChunkQuestions, request);

        // Then
        QuizGenerationJob updatedJob = jobRepository.findById(job.getId()).orElseThrow();
        
        assertThat(updatedJob.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(updatedJob.getGeneratedQuizId()).isNotNull();
        assertThat(updatedJob.getTotalQuestionsGenerated()).isEqualTo(3);
        assertThat(updatedJob.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Empty chunks: should handle gracefully")
    void emptyChunks_shouldHandleGracefully() {
        // Given
        QuizGenerationJob job = createTestJob();
        Map<Integer, List<Question>> emptyChunkQuestions = new HashMap<>();
        GenerateQuizFromDocumentRequest request = createTestRequest("Empty Quiz", "Quiz with no questions");

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(job.getId(), emptyChunkQuestions, request);

        // Then
        List<Quiz> allQuizzes = quizRepository.findAll();
        assertThat(allQuizzes).hasSize(1);
        
        Quiz consolidatedQuiz = allQuizzes.get(0);
        assertThat(consolidatedQuiz.getQuestions()).isEmpty();
        
        QuizGenerationJob updatedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(updatedJob.getTotalQuestionsGenerated()).isEqualTo(0);
    }

    @Test
    @DisplayName("Direct call: should process quiz generation completion correctly")
    void directCall_shouldProcessCompletionCorrectly() {
        // Given
        QuizGenerationJob job = createTestJob();
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();
        GenerateQuizFromDocumentRequest request = createTestRequest("Direct Call Test Quiz", "Testing direct call");

        // When - Call createQuizCollectionFromGeneratedQuestions directly instead of event handler
        quizService.createQuizCollectionFromGeneratedQuestions(job.getId(), singleChunkQuestions, request);

        // Then
        List<Quiz> allQuizzes = quizRepository.findAll();
        assertThat(allQuizzes).hasSize(1);
        
        QuizGenerationJob updatedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(updatedJob.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
    }

    // ========== Helper Methods ==========

    private QuizGenerationJob createTestJob() {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setUser(testUser);
        job.setDocumentId(testDocument.getId());
        job.setStatus(GenerationStatus.PROCESSING);
        job.setTotalChunks(1);
        job.setProcessedChunks(0);
        return jobRepository.save(job);
    }

    private Map<Integer, List<Question>> createSingleChunkQuestions() {
        Map<Integer, List<Question>> chunkQuestions = new HashMap<>();
        List<Question> questions = new ArrayList<>();
        
        for (int i = 0; i < 3; i++) {
            Question question = createQuestion("Question " + (i + 1), QuestionType.MCQ_SINGLE);
            questions.add(question);
        }
        
        chunkQuestions.put(0, questions);
        return chunkQuestions;
    }

    private Map<Integer, List<Question>> createMultiChunkQuestions() {
        Map<Integer, List<Question>> chunkQuestions = new HashMap<>();
        
        // Chunk 0
        List<Question> chunk0Questions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            chunk0Questions.add(createQuestion("Chunk 0 Question " + (i + 1), QuestionType.MCQ_SINGLE));
        }
        chunkQuestions.put(0, chunk0Questions);
        
        // Chunk 1
        List<Question> chunk1Questions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            chunk1Questions.add(createQuestion("Chunk 1 Question " + (i + 1), QuestionType.TRUE_FALSE));
        }
        chunkQuestions.put(1, chunk1Questions);
        
        return chunkQuestions;
    }

    private Question createQuestion(String text, QuestionType type) {
        Question question = new Question();
        question.setQuestionText(text);
        question.setType(type);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setContent("{\"question\":\"" + text + "\",\"answer\":true}");
        question.setExplanation("Explanation for " + text);
        return questionRepository.save(question);
    }

    private GenerateQuizFromDocumentRequest createTestRequest(String title, String description) {
        Map<QuestionType, Integer> questionsPerType = new HashMap<>();
        questionsPerType.put(QuestionType.MCQ_SINGLE, 3);
        
        return new GenerateQuizFromDocumentRequest(
                testDocument.getId(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                title,
                description,
                questionsPerType,
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );
    }
}

