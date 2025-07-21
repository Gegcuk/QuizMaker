package uk.gegc.quizmaker.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.quiz.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.model.document.Document;
import uk.gegc.quizmaker.model.document.DocumentChunk;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.model.quiz.GenerationStatus;
import uk.gegc.quizmaker.model.quiz.QuizGenerationJob;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.document.DocumentRepository;
import uk.gegc.quizmaker.repository.quiz.QuizGenerationJobRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.ai.AiQuizGenerationService;
import uk.gegc.quizmaker.service.quiz.QuizGenerationJobService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AiQuizGenerationServiceIntegrationTest {

    @Autowired
    private AiQuizGenerationService aiQuizGenerationService;

    @SpyBean
    private org.springframework.ai.chat.client.ChatClient chatClient;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private QuizGenerationJobRepository jobRepository;

    @Autowired
    private ObjectMapper objectMapper;

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
                uk.gegc.quizmaker.dto.quiz.QuizScope.ENTIRE_DOCUMENT,
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Integration Test Quiz",
                "Test description for integration testing",
                questionsPerType,
                uk.gegc.quizmaker.model.question.Difficulty.MEDIUM,
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
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.validateDocumentForGeneration(testDocumentId, savedOtherUser.getUsername());
        });
    }

    @Test
    void shouldHandleDocumentNotFound() {
        // Given
        UUID nonExistentDocumentId = UUID.randomUUID();

        // When & Then
        assertThrows(uk.gegc.quizmaker.exception.DocumentNotFoundException.class, () -> {
            aiQuizGenerationService.validateDocumentForGeneration(nonExistentDocumentId, testUser.getUsername());
        });
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
        assertThrows(uk.gegc.quizmaker.exception.ValidationException.class, () -> {
            jobService.getJobByIdAndUsername(job.getId(), "unauthorizeduser");
        });
    }
} 