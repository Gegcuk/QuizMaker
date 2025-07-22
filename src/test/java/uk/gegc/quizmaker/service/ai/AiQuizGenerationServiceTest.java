package uk.gegc.quizmaker.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import uk.gegc.quizmaker.dto.quiz.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.dto.quiz.QuizScope;
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
import uk.gegc.quizmaker.service.ai.impl.AiQuizGenerationServiceImpl;
import uk.gegc.quizmaker.service.ai.parser.QuestionResponseParser;
import uk.gegc.quizmaker.service.quiz.QuizGenerationJobService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class AiQuizGenerationServiceTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private QuestionResponseParser questionResponseParser;

    @Mock
    private QuizGenerationJobRepository jobRepository;

    @Mock
    private QuizGenerationJobService jobService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AiQuizGenerationServiceImpl aiQuizGenerationService;

    private User testUser;
    private Document testDocument;
    private DocumentChunk testChunk;
    private QuizGenerationJob testJob;
    private GenerateQuizFromDocumentRequest testRequest;
    private UUID testDocumentId;
    private UUID testJobId;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setUsername("testuser");

        testDocumentId = UUID.randomUUID();
        testJobId = UUID.randomUUID();

        testChunk = new DocumentChunk();
        testChunk.setId(UUID.randomUUID());
        testChunk.setChunkIndex(1);
        testChunk.setContent("This is a test chunk content about machine learning.");
        testChunk.setTitle("Test Chunk");

        testDocument = new Document();
        testDocument.setId(testDocumentId);
        testDocument.setTitle("Test Document");
        testDocument.setUploadedBy(testUser);
        testDocument.setStatus(Document.DocumentStatus.PROCESSED);
        testDocument.setChunks(List.of(testChunk));

        testChunk.setDocument(testDocument);

        testJob = new QuizGenerationJob();
        testJob.setId(testJobId);
        testJob.setUser(testUser);
        testJob.setDocumentId(testDocumentId);
        testJob.setStatus(GenerationStatus.PENDING);

        Map<QuestionType, Integer> questionsPerType = new HashMap<>();
        questionsPerType.put(QuestionType.MCQ_SINGLE, 3);
        questionsPerType.put(QuestionType.TRUE_FALSE, 2);

        testRequest = new GenerateQuizFromDocumentRequest(
                testDocumentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Test Quiz",
                "Test description",
                questionsPerType,
                Difficulty.MEDIUM,
                2, // estimatedTimePerQuestion
                null, // categoryId
                List.of() // tagIds
        );
    }

    @Test
    void shouldCalculateEstimatedGenerationTimeCorrectly() {
        // Given
        Map<QuestionType, Integer> questionsPerType = Map.of(
                QuestionType.MCQ_SINGLE, 5,
                QuestionType.TRUE_FALSE, 3,
                QuestionType.OPEN, 2
        );

        // When
        int estimatedTime = aiQuizGenerationService.calculateEstimatedGenerationTime(3, questionsPerType);

        // Then
        assertTrue(estimatedTime > 0);
        // The calculation should consider the number of question types and questions per type
        // This is a basic validation - the actual calculation logic may vary
    }

    @Test
    void shouldValidateDocumentForGenerationSuccessfully() {
        // Given
        when(documentRepository.findById(testDocumentId)).thenReturn(java.util.Optional.of(testDocument));

        // When & Then
        assertDoesNotThrow(() -> {
            aiQuizGenerationService.validateDocumentForGeneration(testDocumentId, "testuser");
        });
    }

    @Test
    void shouldThrowDocumentNotFoundExceptionWhenDocumentNotFound() {
        // Given
        when(documentRepository.findById(testDocumentId)).thenReturn(java.util.Optional.empty());

        // When & Then
        assertThrows(DocumentNotFoundException.class, () -> {
            aiQuizGenerationService.validateDocumentForGeneration(testDocumentId, "testuser");
        });
    }

    @Test
    void shouldThrowValidationExceptionWhenUserNotAuthorized() {
        // Given
        User otherUser = new User();
        otherUser.setUsername("otheruser");
        testDocument.setUploadedBy(otherUser);

        when(documentRepository.findById(testDocumentId)).thenReturn(java.util.Optional.of(testDocument));

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.validateDocumentForGeneration(testDocumentId, "testuser");
        });
    }

    @Test
    void shouldCreateGenerationJobSuccessfully() throws JsonProcessingException {
        // Given
        String serializedRequest = "{\"documentId\":\"" + testDocumentId + "\"}";
        when(objectMapper.writeValueAsString(testRequest)).thenReturn(serializedRequest);
        when(userRepository.findByUsername("testuser")).thenReturn(java.util.Optional.of(testUser));
        when(documentRepository.findById(testDocumentId)).thenReturn(java.util.Optional.of(testDocument));
        when(jobRepository.save(any(QuizGenerationJob.class))).thenReturn(testJob);

        // When
        QuizGenerationJob createdJob = aiQuizGenerationService.createGenerationJob(testDocumentId, "testuser", testRequest);

        // Then
        assertNotNull(createdJob);
        assertEquals(testJobId, createdJob.getId());
        assertEquals(testDocumentId, createdJob.getDocumentId());
        assertEquals(GenerationStatus.PENDING, createdJob.getStatus());

        verify(objectMapper).writeValueAsString(testRequest);
        verify(jobRepository).save(any(QuizGenerationJob.class));
    }

    @Test
    void shouldHandleJobCreationSerializationFailure() throws JsonProcessingException {
        // Given
        when(objectMapper.writeValueAsString(testRequest)).thenThrow(new JsonProcessingException("Serialization failed") {
        });

        // When & Then
        assertThrows(AiServiceException.class, () -> {
            aiQuizGenerationService.createGenerationJob(testDocumentId, "testuser", testRequest);
        });
    }

    @Test
    void shouldGetJobByIdAndUsernameSuccessfully() {
        // Given
        when(jobRepository.findById(testJobId)).thenReturn(java.util.Optional.of(testJob));

        // When
        QuizGenerationJob foundJob = aiQuizGenerationService.getJobByIdAndUsername(testJobId, "testuser");

        // Then
        assertNotNull(foundJob);
        assertEquals(testJobId, foundJob.getId());
        assertEquals("testuser", foundJob.getUser().getUsername());
    }

    @Test
    void shouldThrowResourceNotFoundExceptionWhenJobNotFound() {
        // Given
        when(jobRepository.findById(testJobId)).thenReturn(java.util.Optional.empty());

        // When & Then
        assertThrows(ResourceNotFoundException.class, () -> {
            aiQuizGenerationService.getJobByIdAndUsername(testJobId, "testuser");
        });
    }

    @Test
    void shouldThrowResourceNotFoundExceptionWhenUserNotAuthorized() {
        // Given
        User otherUser = new User();
        otherUser.setUsername("otheruser");
        testJob.setUser(otherUser);

        when(jobRepository.findById(testJobId)).thenReturn(java.util.Optional.of(testJob));

        // When & Then
        assertThrows(ResourceNotFoundException.class, () -> {
            aiQuizGenerationService.getJobByIdAndUsername(testJobId, "testuser");
        });
    }

    @Test
    void shouldUpdateJobProgressSuccessfully() {
        // Given
        when(jobRepository.findById(testJobId)).thenReturn(java.util.Optional.of(testJob));
        when(jobRepository.save(any(QuizGenerationJob.class))).thenReturn(testJob);

        // When
        aiQuizGenerationService.updateJobProgress(testJobId, 2, "3");

        // Then
        verify(jobRepository).save(testJob);
        assertEquals(2, testJob.getProcessedChunks());
        assertEquals("3", testJob.getCurrentChunk());
    }

    @Test
    void shouldHandleJobProgressUpdateWhenJobNotFound() {
        // Given
        when(jobRepository.findById(testJobId)).thenReturn(java.util.Optional.empty());

        // When & Then
        assertThrows(ResourceNotFoundException.class, () -> {
            aiQuizGenerationService.updateJobProgress(testJobId, 2, "3");
        });

        verify(jobRepository, never()).save(any());
    }

    @Test
    void shouldHandleEmptyQuestionsPerType() {
        // Given
        Map<QuestionType, Integer> emptyQuestionsPerType = new HashMap<>();
        
        // When
        int estimatedTime = aiQuizGenerationService.calculateEstimatedGenerationTime(3, emptyQuestionsPerType);
        
        // Then
        assertTrue(estimatedTime > 0);
        // Should still calculate base time even with no questions
    }

    @Test
    void shouldHandleZeroChunks() {
        // Given
        Map<QuestionType, Integer> questionsPerType = Map.of(QuestionType.MCQ_SINGLE, 5);
        
        // When
        int estimatedTime = aiQuizGenerationService.calculateEstimatedGenerationTime(0, questionsPerType);
        
        // Then
        assertTrue(estimatedTime > 0);
        // Should calculate time for question types even with zero chunks
    }

    @Test
    void shouldHandleLargeNumberOfChunks() {
        // Given
        Map<QuestionType, Integer> questionsPerType = Map.of(QuestionType.MCQ_SINGLE, 3);
        int largeChunkCount = 1000;
        
        // When
        int estimatedTime = aiQuizGenerationService.calculateEstimatedGenerationTime(largeChunkCount, questionsPerType);
        
        // Then
        assertTrue(estimatedTime > 0);
        // Should handle large numbers without overflow
    }

    @Test
    void shouldHandleMaximumQuestionTypes() {
        // Given
        Map<QuestionType, Integer> allQuestionTypes = new HashMap<>();
        for (QuestionType type : QuestionType.values()) {
            allQuestionTypes.put(type, 1);
        }
        
        // When
        int estimatedTime = aiQuizGenerationService.calculateEstimatedGenerationTime(5, allQuestionTypes);
        
        // Then
        assertTrue(estimatedTime > 0);
        // Should handle all question types
    }

    @Test
    void shouldValidateDocumentWithNullChunks() {
        // Given
        testDocument.setChunks(null);
        when(documentRepository.findById(testDocumentId)).thenReturn(java.util.Optional.of(testDocument));

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.validateDocumentForGeneration(testDocumentId, "testuser");
        });
    }

    @Test
    void shouldValidateDocumentWithEmptyChunks() {
        // Given
        testDocument.setChunks(List.of());
        when(documentRepository.findById(testDocumentId)).thenReturn(java.util.Optional.of(testDocument));

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.validateDocumentForGeneration(testDocumentId, "testuser");
        });
    }

    @Test
    void shouldHandleJobProgressUpdateWithNegativeValues() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.updateJobProgress(testJobId, -1, "current");
        });
    }

    @Test
    void shouldHandleJobProgressUpdateWithNullCurrentChunk() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.updateJobProgress(testJobId, 1, null);
        });
    }

    @Test
    void shouldHandleJobProgressUpdateWithEmptyCurrentChunk() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.updateJobProgress(testJobId, 1, "");
        });
    }

    @Test
    void shouldHandleConcurrentJobAccess() {
        // Given
        when(jobRepository.findById(testJobId)).thenReturn(java.util.Optional.of(testJob));
        when(jobRepository.save(any(QuizGenerationJob.class))).thenReturn(testJob);

        // When - simulate concurrent access
        CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
            aiQuizGenerationService.updateJobProgress(testJobId, 1, "chunk1");
        });
        
        CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
            aiQuizGenerationService.updateJobProgress(testJobId, 2, "chunk2");
        });

        // Then - should complete without exceptions
        assertDoesNotThrow(() -> {
            CompletableFuture.allOf(future1, future2).join();
        });
    }

    @Test
    void shouldHandleJobCreationWithNullUser() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.createGenerationJob(testDocumentId, null, testRequest);
        });
    }

    @Test
    void shouldHandleJobCreationWithNullRequest() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.createGenerationJob(testDocumentId, "testuser", null);
        });
    }

    @Test
    void shouldHandleJobRetrievalWithNullJobId() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.getJobByIdAndUsername(null, "testuser");
        });
    }

    @Test
    void shouldHandleJobRetrievalWithNullUsername() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.getJobByIdAndUsername(testJobId, null);
        });
    }

    @Test
    void shouldHandleJobRetrievalWithEmptyUsername() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.getJobByIdAndUsername(testJobId, "");
        });
    }
} 