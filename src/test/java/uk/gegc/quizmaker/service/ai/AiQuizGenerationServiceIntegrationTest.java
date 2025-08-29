package uk.gegc.quizmaker.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test-mysql")
@Transactional
class AiQuizGenerationServiceIntegrationTest {

    @Autowired
    private AiQuizGenerationService aiQuizGenerationService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private QuizGenerationJobRepository jobRepository;

    @Autowired
    private QuizGenerationJobService jobService;

    private User testUser;
    private Document testDocument;
    private DocumentChunk testChunk;
    private GenerateQuizFromDocumentRequest testRequest;
    private UUID testDocumentId;

    @BeforeEach
    void setUp() {
        // Create test user with unique username for each test
        testUser = new User();
        testUser.setUsername("integrationtestuser_" + UUID.randomUUID().toString().substring(0, 8));
        testUser.setEmail("integration_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com");
        testUser.setHashedPassword("password");
        testUser = userRepository.save(testUser);

        // Create test document - let JPA generate the ID
        testDocument = new Document();
        testDocument.setTitle("Integration Test Document " + UUID.randomUUID().toString().substring(0, 8));
        testDocument.setOriginalFilename("test-document.pdf");
        testDocument.setContentType("application/pdf");
        testDocument.setFileSize(1024L);
        testDocument.setFilePath("/test/path/document.pdf");
        testDocument.setUploadedBy(testUser);
        testDocument.setStatus(Document.DocumentStatus.PROCESSED);

        // Create test chunk
        testChunk = new DocumentChunk();
        testChunk.setDocument(testDocument);
        testChunk.setChunkIndex(1);
        testChunk.setContent("This is a test chunk content for integration testing. It contains information about machine learning and artificial intelligence.");
        testChunk.setTitle("Test Chunk");
        testChunk.setStartPage(1);
        testChunk.setEndPage(1);
        testChunk.setWordCount(20);
        testChunk.setCharacterCount(120);
        testChunk.setCreatedAt(LocalDateTime.now());

        testDocument.setChunks(List.of(testChunk));
        testDocument = documentRepository.save(testDocument);
        
        // Get the generated ID after saving
        testDocumentId = testDocument.getId();

        // Create test request
        Map<QuestionType, Integer> questionsPerType = new HashMap<>();
        questionsPerType.put(QuestionType.MCQ_SINGLE, 2);
        questionsPerType.put(QuestionType.TRUE_FALSE, 1);

        testRequest = new GenerateQuizFromDocumentRequest(
                testDocumentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Integration Test Quiz",
                "Test description for integration testing",
                questionsPerType,
                Difficulty.MEDIUM,
                2, // estimatedTimePerQuestion
                null, // categoryId
                List.of() // tagIds
        );
    }

    @Test
    void shouldCalculateEstimatedGenerationTime() {
        // Given
        Map<QuestionType, Integer> questionsPerType = new HashMap<>();
        questionsPerType.put(QuestionType.MCQ_SINGLE, 5);
        questionsPerType.put(QuestionType.TRUE_FALSE, 3);

        // When
        int estimatedTime = aiQuizGenerationService.calculateEstimatedGenerationTime(3, questionsPerType);

        // Then
        assertTrue(estimatedTime > 0);
        // Should be reasonable (not too high, not too low)
        assertTrue(estimatedTime < 3600); // Less than 1 hour
        assertTrue(estimatedTime > 30); // More than 30 seconds
    }

    @Test
    void shouldHandleJobProgressTracking() {
        // Given
        QuizGenerationJob job = jobService.createJob(testUser, testDocumentId, "test-request-data", 1, 300);

        // When
        jobService.updateJobProgress(job.getId(), 1, 1, 5);
        jobService.updateJobProgress(job.getId(), 2, 2, 10);

        // Then
        QuizGenerationJob updatedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(2, updatedJob.getProcessedChunks());
        assertEquals("2", updatedJob.getCurrentChunk());
    }

    @Test
    void shouldValidateDocumentAccess() {
        // Given
        final User otherUser = new User();
        otherUser.setUsername("otheruser_" + UUID.randomUUID().toString().substring(0, 8));
        otherUser.setEmail("other_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com");
        otherUser.setHashedPassword("password");
        final User savedOtherUser = userRepository.save(otherUser);

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> aiQuizGenerationService.validateDocumentForGeneration(testDocumentId, savedOtherUser.getUsername()));
    }

    @Test
    void shouldHandleDocumentNotFound() {
        // Given
        UUID nonExistentDocumentId = UUID.randomUUID();

        // When & Then
        assertThrows(DocumentNotFoundException.class, () -> aiQuizGenerationService.validateDocumentForGeneration(nonExistentDocumentId, testUser.getUsername()));
    }

    @Test
    void shouldHandleJobRetrievalByUser() {
        // Given
        QuizGenerationJob job = jobService.createJob(testUser, testDocumentId, "test-request-data", 1, 300);

        // When
        QuizGenerationJob retrievedJob = jobService.getJobByIdAndUsername(job.getId(), testUser.getUsername());

        // Then
        assertNotNull(retrievedJob);
        assertEquals(job.getId(), retrievedJob.getId());
        assertEquals(testUser.getUsername(), retrievedJob.getUser().getUsername());
    }

    @Test
    void shouldHandleJobRetrievalUnauthorized() {
        // Given
        QuizGenerationJob job = jobService.createJob(testUser, testDocumentId, "test-request-data", 1, 300);

        // When & Then
        assertThrows(ValidationException.class, () -> jobService.getJobByIdAndUsername(job.getId(), "unauthorizeduser"));
    }
} 