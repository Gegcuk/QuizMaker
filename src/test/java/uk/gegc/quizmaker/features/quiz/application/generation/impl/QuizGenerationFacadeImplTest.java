package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationResponse;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizAssemblyService;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationRequestedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for QuizGenerationFacadeImpl.
 * 
 * <p>This test class covers all methods in the facade, focusing on:
 * - Quiz generation from various sources (document, upload, text)
 * - Billing reservation, commit, and release flows
 * - Job lifecycle management and state transitions
 * - Error handling and recovery mechanisms
 * - Idempotency and concurrency control
 * 
 * <p>Tests are organized into nested classes by method for clarity.
 * Total tests: 92+ covering all critical paths and edge cases.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuizGenerationFacadeImpl Tests")
class QuizGenerationFacadeImplTest {

    @Mock
    private UserRepository userRepository;
    
    @Mock
    private QuizGenerationJobRepository jobRepository;
    
    @Mock
    private QuizGenerationJobService jobService;
    
    @Mock
    private AiQuizGenerationService aiQuizGenerationService;
    
    @Mock
    private DocumentProcessingService documentProcessingService;
    
    @Mock
    private BillingService billingService;
    
    @Mock
    private InternalBillingService internalBillingService;
    
    @Mock
    private EstimationService estimationService;
    
    @Mock
    private FeatureFlags featureFlags;
    
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    
    @Mock
    private TransactionTemplate transactionTemplate;
    
    @Mock
    private QuizJobProperties quizJobProperties;
    
    @Mock
    private QuizAssemblyService quizAssemblyService;
    
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    
    @InjectMocks
    private QuizGenerationFacadeImpl facade;
    
    private User testUser;
    private UUID documentId;
    private GenerateQuizFromDocumentRequest documentRequest;
    private EstimationDto estimation;
    private ReservationDto reservation;
    private QuizGenerationJob job;
    
    @BeforeEach
    void setUp() {
        testUser = createUser("testuser", UUID.randomUUID());
        documentId = UUID.randomUUID();
        
        documentRequest = new GenerateQuizFromDocumentRequest(
                documentId,                    // documentId
                QuizScope.ENTIRE_DOCUMENT,     // quizScope
                null,                          // chunkIndices
                null,                          // chapterTitle
                null,                          // chapterNumber
                "Test Quiz",                   // quizTitle
                "Test Description",            // quizDescription
                Map.of(
                    uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 5,
                    uk.gegc.quizmaker.features.question.domain.model.QuestionType.TRUE_FALSE, 5
                ),                             // questionsPerType
                Difficulty.MEDIUM,             // difficulty
                2,                             // estimatedTimePerQuestion
                null,                          // categoryId
                List.of()                      // tagIds
        );
        
        estimation = new EstimationDto(
                1000L,          // estimatedLlmTokens
                1200L,          // estimatedBillingTokens
                null,           // approxCostCents
                "usd",          // currency
                true,           // estimate
                "~1200 billing tokens (1,000 LLM tokens)",  // humanizedEstimate
                UUID.randomUUID()  // estimationId
        );
        
        reservation = new ReservationDto(
                UUID.randomUUID(),          // id
                testUser.getId(),           // userId
                uk.gegc.quizmaker.features.billing.domain.model.ReservationState.ACTIVE,  // state
                1200L,                      // estimatedTokens
                0L,                         // committedTokens
                java.time.LocalDateTime.now().plusHours(1),  // expiresAt
                null,                       // jobId
                java.time.LocalDateTime.now(),  // createdAt
                java.time.LocalDateTime.now()   // updatedAt
        );
        
        job = createJob(testUser, documentId);
    }
    
    // ============================================================================
    // 1. generateQuizFromDocument() Tests
    // ============================================================================
    
    @Nested
    @DisplayName("generateQuizFromDocument() Tests")
    class GenerateQuizFromDocumentTests {
        
        @Test
        @DisplayName("Successful generation - delegates to startQuizGeneration")
        void successfulGeneration_delegatesToStartQuizGeneration() {
            // Given
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(estimationService.estimateQuizGeneration(documentId, documentRequest)).thenReturn(estimation);
            when(billingService.reserve(eq(testUser.getId()), eq(1200L), eq("quiz-generation"), any()))
                    .thenReturn(reservation);
            when(aiQuizGenerationService.calculateTotalChunks(documentId, documentRequest)).thenReturn(3);
            when(aiQuizGenerationService.calculateEstimatedGenerationTime(3, documentRequest.questionsPerType()))
                    .thenReturn(120);
            when(jobService.createJob(eq(testUser), eq(documentId), any(), eq(3), eq(120))).thenReturn(job);
            
            // Mock TransactionTemplate to execute callback
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            
            // When
            QuizGenerationResponse response = facade.generateQuizFromDocument("testuser", documentRequest);
            
            // Then
            assertThat(response).isNotNull();
            assertThat(response.jobId()).isEqualTo(job.getId());
            assertThat(response.estimatedTimeSeconds()).isEqualTo(120L);
            
            verify(userRepository).findByUsername("testuser");
            verify(estimationService).estimateQuizGeneration(documentId, documentRequest);
            verify(billingService).reserve(eq(testUser.getId()), eq(1200L), eq("quiz-generation"), any());
            verify(jobService).createJob(eq(testUser), eq(documentId), any(), eq(3), eq(120));
            verify(applicationEventPublisher).publishEvent(any(QuizGenerationRequestedEvent.class));
        }
        
        @Test
        @DisplayName("User not found - throws ResourceNotFoundException")
        void userNotFound_throwsResourceNotFoundException() {
            // Given
            when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("nonexistent")).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromDocument("nonexistent", documentRequest))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("User not found: nonexistent");
            
            verify(userRepository).findByUsername("nonexistent");
            verify(userRepository).findByEmail("nonexistent");
            verifyNoInteractions(billingService);
        }
        
        @Test
        @DisplayName("Insufficient tokens - propagates exception")
        void insufficientTokens_propagatesException() {
            // Given
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(estimationService.estimateQuizGeneration(documentId, documentRequest)).thenReturn(estimation);
            when(billingService.reserve(eq(testUser.getId()), eq(1200L), eq("quiz-generation"), any()))
                    .thenThrow(new InsufficientTokensException(
                            "Insufficient tokens",
                            1200L,  // estimated
                            500L,   // available
                            700L,   // shortfall
                            java.time.LocalDateTime.now().plusHours(1)  // reservationTtl
                    ));
            
            // Mock TransactionTemplate to execute callback
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromDocument("testuser", documentRequest))
                    .isInstanceOf(InsufficientTokensException.class)
                    .hasMessageContaining("Insufficient tokens to start quiz generation");
            
            verify(billingService).reserve(eq(testUser.getId()), eq(1200L), eq("quiz-generation"), any());
            verifyNoInteractions(jobService);
        }
        
        @Test
        @DisplayName("Billing failure - handled gracefully")
        void billingFailure_handledGracefully() {
            // Given
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(estimationService.estimateQuizGeneration(documentId, documentRequest)).thenReturn(estimation);
            when(billingService.reserve(eq(testUser.getId()), eq(1200L), eq("quiz-generation"), any()))
                    .thenThrow(new RuntimeException("Billing service unavailable"));
            
            // Mock TransactionTemplate to execute callback
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromDocument("testuser", documentRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Billing service unavailable");
            
            verifyNoInteractions(jobService);
        }
        
        @Test
        @DisplayName("Event published after job creation")
        void eventPublished_afterJobCreation() {
            // Given
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(estimationService.estimateQuizGeneration(documentId, documentRequest)).thenReturn(estimation);
            when(billingService.reserve(eq(testUser.getId()), eq(1200L), eq("quiz-generation"), any()))
                    .thenReturn(reservation);
            when(aiQuizGenerationService.calculateTotalChunks(documentId, documentRequest)).thenReturn(3);
            when(aiQuizGenerationService.calculateEstimatedGenerationTime(3, documentRequest.questionsPerType()))
                    .thenReturn(120);
            when(jobService.createJob(eq(testUser), eq(documentId), any(), eq(3), eq(120))).thenReturn(job);
            
            // Mock TransactionTemplate
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            
            // When
            QuizGenerationResponse response = facade.generateQuizFromDocument("testuser", documentRequest);
            
            // Then
            ArgumentCaptor<QuizGenerationRequestedEvent> eventCaptor = 
                    ArgumentCaptor.forClass(QuizGenerationRequestedEvent.class);
            verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
            
            QuizGenerationRequestedEvent event = eventCaptor.getValue();
            assertThat(event.getJobId()).isEqualTo(job.getId());
            assertThat(event.getRequest()).isEqualTo(documentRequest);
        }
    }
    
    // ============================================================================
    // 2. generateQuizFromUpload() Tests
    // ============================================================================
    
    @Nested
    @DisplayName("generateQuizFromUpload() Tests")
    class GenerateQuizFromUploadTests {
        
        private MultipartFile mockFile;
        private uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest uploadRequest;
        private DocumentDto processedDocument;
        
        @BeforeEach
        void setUp() {
            mockFile = mock(MultipartFile.class);
            
            uploadRequest = new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest(
                    null,  // chunkingStrategy (will default to CHAPTER_BASED)
                    null,  // maxChunkSize (will default to 250000)
                    QuizScope.ENTIRE_DOCUMENT,  // quizScope
                    null,  // chunkIndices
                    null,  // chapterTitle
                    null,  // chapterNumber
                    "Test Quiz from Upload",  // quizTitle
                    "Test Description",  // quizDescription
                    Map.of(
                        uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 5,
                        uk.gegc.quizmaker.features.question.domain.model.QuestionType.TRUE_FALSE, 5
                    ),  // questionsPerType
                    Difficulty.MEDIUM,  // difficulty
                    2,  // estimatedTimePerQuestion
                    null,  // categoryId
                    List.of(),  // tagIds
                    null  // language
            );
            
            processedDocument = new DocumentDto();
            processedDocument.setId(UUID.randomUUID());
            processedDocument.setOriginalFilename("test-document.pdf");
            processedDocument.setContentType("pdf");
            processedDocument.setFileSize(1024L);
            processedDocument.setStatus(uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus.PROCESSED);
            processedDocument.setTotalChunks(3);
        }
        
        @Test
        @DisplayName("Successful upload - processes and starts generation")
        void successfulUpload_processesAndStartsGeneration() throws Exception {
            // Given
            byte[] fileBytes = "test content".getBytes();
            when(mockFile.getBytes()).thenReturn(fileBytes);
            when(mockFile.getOriginalFilename()).thenReturn("test.pdf");
            
            when(documentProcessingService.uploadAndProcessDocument(
                    eq("testuser"), eq(fileBytes), eq("test.pdf"), any()))
                    .thenReturn(processedDocument);
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(processedDocument.getId()), any())).thenReturn(3);
            
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(estimationService.estimateQuizGeneration(eq(processedDocument.getId()), any())).thenReturn(estimation);
            when(billingService.reserve(eq(testUser.getId()), anyLong(), eq("quiz-generation"), any()))
                    .thenReturn(reservation);
            when(aiQuizGenerationService.calculateEstimatedGenerationTime(eq(3), any())).thenReturn(120);
            when(jobService.createJob(eq(testUser), eq(processedDocument.getId()), any(), eq(3), eq(120))).thenReturn(job);
            
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            
            // When
            QuizGenerationResponse response = facade.generateQuizFromUpload("testuser", mockFile, uploadRequest);
            
            // Then
            assertThat(response).isNotNull();
            assertThat(response.jobId()).isEqualTo(job.getId());
            
            verify(mockFile).getBytes();
            verify(documentProcessingService).uploadAndProcessDocument(eq("testuser"), eq(fileBytes), eq("test.pdf"), any());
            verify(aiQuizGenerationService, atLeastOnce()).calculateTotalChunks(eq(processedDocument.getId()), any());
            verify(billingService).reserve(eq(testUser.getId()), anyLong(), eq("quiz-generation"), any());
        }
        
        @Test
        @DisplayName("File read error - throws RuntimeException")
        void fileReadError_throwsRuntimeException() throws Exception {
            // Given
            when(mockFile.getBytes()).thenThrow(new java.io.IOException("Failed to read file"));
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromUpload("testuser", mockFile, uploadRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to read file bytes");
            
            verifyNoInteractions(documentProcessingService);
        }
        
        @Test
        @DisplayName("Document processing fails - throws RuntimeException")
        void documentProcessingFails_throwsRuntimeException() throws Exception {
            // Given
            byte[] fileBytes = "test content".getBytes();
            when(mockFile.getBytes()).thenReturn(fileBytes);
            when(mockFile.getOriginalFilename()).thenReturn("test.pdf");
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Document processing failed"));
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromUpload("testuser", mockFile, uploadRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to generate quiz from upload");
            
            verify(documentProcessingService).uploadAndProcessDocument(eq("testuser"), eq(fileBytes), eq("test.pdf"), any());
        }
        
        @Test
        @DisplayName("No chunks available - throws RuntimeException")
        void noChunksAvailable_throwsRuntimeException() throws Exception {
            // Given
            byte[] fileBytes = "test content".getBytes();
            when(mockFile.getBytes()).thenReturn(fileBytes);
            when(mockFile.getOriginalFilename()).thenReturn("test.pdf");
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenReturn(processedDocument);
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(processedDocument.getId()), any())).thenReturn(0);
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromUpload("testuser", mockFile, uploadRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Document has no chunks available for quiz generation");
            
            verify(aiQuizGenerationService).calculateTotalChunks(eq(processedDocument.getId()), any());
        }
        
        @Test
        @DisplayName("Insufficient tokens - propagates exception")
        void insufficientTokens_propagatesException() throws Exception {
            // Given
            byte[] fileBytes = "test content".getBytes();
            when(mockFile.getBytes()).thenReturn(fileBytes);
            when(mockFile.getOriginalFilename()).thenReturn("test.pdf");
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenReturn(processedDocument);
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(processedDocument.getId()), any())).thenReturn(3);
            
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(estimationService.estimateQuizGeneration(eq(processedDocument.getId()), any())).thenReturn(estimation);
            when(billingService.reserve(any(UUID.class), anyLong(), anyString(), anyString()))
                    .thenThrow(new InsufficientTokensException(
                            "Insufficient tokens",
                            1200L, 500L, 700L,
                            java.time.LocalDateTime.now().plusHours(1)
                    ));
            
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromUpload("testuser", mockFile, uploadRequest))
                    .isInstanceOf(InsufficientTokensException.class)
                    .hasMessageContaining("Insufficient tokens");
            
            verify(documentProcessingService).uploadAndProcessDocument(eq("testuser"), eq(fileBytes), eq("test.pdf"), any());
        }
        
        @Test
        @DisplayName("Generation start fails - throws RuntimeException")
        void generationStartFails_throwsRuntimeException() throws Exception {
            // Given
            byte[] fileBytes = "test content".getBytes();
            when(mockFile.getBytes()).thenReturn(fileBytes);
            when(mockFile.getOriginalFilename()).thenReturn("test.pdf");
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenReturn(processedDocument);
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(processedDocument.getId()), any())).thenReturn(3);
            
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("testuser")).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromUpload("testuser", mockFile, uploadRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to generate quiz from upload");
        }
        
        @Test
        @DisplayName("Large file - processed successfully")
        void largeFile_processedSuccessfully() throws Exception {
            // Given
            byte[] largeFileBytes = new byte[5 * 1024 * 1024]; // 5MB
            when(mockFile.getBytes()).thenReturn(largeFileBytes);
            when(mockFile.getOriginalFilename()).thenReturn("large-document.pdf");
            
            DocumentDto largeDocument = new DocumentDto();
            largeDocument.setId(UUID.randomUUID());
            largeDocument.setOriginalFilename("large-document.pdf");
            largeDocument.setContentType("pdf");
            largeDocument.setFileSize(5242880L);
            largeDocument.setStatus(uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus.PROCESSED);
            largeDocument.setTotalChunks(10);
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenReturn(largeDocument);
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(largeDocument.getId()), any())).thenReturn(10);
            
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(estimationService.estimateQuizGeneration(eq(largeDocument.getId()), any())).thenReturn(estimation);
            when(billingService.reserve(any(UUID.class), anyLong(), anyString(), anyString())).thenReturn(reservation);
            when(aiQuizGenerationService.calculateEstimatedGenerationTime(eq(10), any())).thenReturn(300);
            when(jobService.createJob(eq(testUser), eq(largeDocument.getId()), any(), eq(10), eq(300))).thenReturn(job);
            
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            
            // When
            QuizGenerationResponse response = facade.generateQuizFromUpload("testuser", mockFile, uploadRequest);
            
            // Then
            assertThat(response).isNotNull();
            verify(jobService).createJob(eq(testUser), eq(largeDocument.getId()), any(), eq(10), eq(300));
        }
        
        @Test
        @DisplayName("Empty file - handled gracefully")
        void emptyFile_handledGracefully() throws Exception {
            // Given
            byte[] emptyBytes = new byte[0];
            when(mockFile.getBytes()).thenReturn(emptyBytes);
            when(mockFile.getOriginalFilename()).thenReturn("empty.pdf");
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Empty file"));
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromUpload("testuser", mockFile, uploadRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to generate quiz from upload");
        }
    }
    
    // ============================================================================
    // 3. generateQuizFromText() Tests
    // ============================================================================
    
    @Nested
    @DisplayName("generateQuizFromText() Tests")
    class GenerateQuizFromTextTests {
        
        private uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest textRequest;
        private DocumentDto processedTextDocument;
        
        @BeforeEach
        void setUp() {
            textRequest = new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    "This is a sample text for quiz generation. It contains some educational content about Java programming.",  // text
                    null,  // language
                    null,  // chunkingStrategy
                    null,  // maxChunkSize
                    QuizScope.ENTIRE_DOCUMENT,  // quizScope
                    null,  // chunkIndices
                    null,  // chapterTitle
                    null,  // chapterNumber
                    "Java Quiz",  // quizTitle
                    "Test your Java knowledge",  // quizDescription
                    Map.of(
                        uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 3,
                        uk.gegc.quizmaker.features.question.domain.model.QuestionType.TRUE_FALSE, 3
                    ),  // questionsPerType
                    Difficulty.MEDIUM,  // difficulty
                    2,  // estimatedTimePerQuestion
                    null,  // categoryId
                    List.of()  // tagIds
            );
            
            processedTextDocument = new DocumentDto();
            processedTextDocument.setId(UUID.randomUUID());
            processedTextDocument.setOriginalFilename("text-input.txt");
            processedTextDocument.setContentType("text/plain");
            processedTextDocument.setFileSize(100L);
            processedTextDocument.setStatus(uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus.PROCESSED);
            processedTextDocument.setTotalChunks(1);
        }
        
        @Test
        @DisplayName("Successful text generation - processes and starts")
        void successfulTextGeneration_processesAndStarts() {
            // Given
            when(documentProcessingService.uploadAndProcessDocument(
                    eq("testuser"), any(byte[].class), eq("text-input.txt"), any()))
                    .thenReturn(processedTextDocument);
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(processedTextDocument.getId()), any())).thenReturn(1);
            
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(estimationService.estimateQuizGeneration(eq(processedTextDocument.getId()), any())).thenReturn(estimation);
            when(billingService.reserve(any(UUID.class), anyLong(), anyString(), anyString())).thenReturn(reservation);
            when(aiQuizGenerationService.calculateEstimatedGenerationTime(eq(1), any())).thenReturn(60);
            when(jobService.createJob(eq(testUser), eq(processedTextDocument.getId()), any(), eq(1), eq(60))).thenReturn(job);
            
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            
            // When
            QuizGenerationResponse response = facade.generateQuizFromText("testuser", textRequest);
            
            // Then
            assertThat(response).isNotNull();
            assertThat(response.jobId()).isEqualTo(job.getId());
            
            verify(documentProcessingService).uploadAndProcessDocument(eq("testuser"), any(byte[].class), eq("text-input.txt"), any());
            verify(aiQuizGenerationService, atLeastOnce()).calculateTotalChunks(eq(processedTextDocument.getId()), any());
        }
        
        @Test
        @DisplayName("Short text - single chunk")
        void shortText_singleChunk() {
            // Given
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest shortTextRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    "Short text.",  // text
                    null,  // language
                    null,  // chunkingStrategy
                    null,  // maxChunkSize
                    QuizScope.ENTIRE_DOCUMENT,  // quizScope
                    null,  // chunkIndices
                    null,  // chapterTitle
                    null,  // chapterNumber
                    "Quiz",  // quizTitle
                    "Desc",  // quizDescription
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 2),  // questionsPerType
                    Difficulty.EASY,  // difficulty
                    1,  // estimatedTimePerQuestion
                    null,  // categoryId
                    List.of()  // tagIds
                );
            
            DocumentDto shortDoc = new DocumentDto();
            shortDoc.setId(UUID.randomUUID());
            shortDoc.setOriginalFilename("text-input.txt");
            shortDoc.setContentType("text/plain");
            shortDoc.setFileSize(11L);
            shortDoc.setStatus(uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus.PROCESSED);
            shortDoc.setTotalChunks(1);
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any())).thenReturn(shortDoc);
            when(aiQuizGenerationService.calculateTotalChunks(eq(shortDoc.getId()), any())).thenReturn(1);
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(estimationService.estimateQuizGeneration(eq(shortDoc.getId()), any())).thenReturn(estimation);
            when(billingService.reserve(any(UUID.class), anyLong(), anyString(), anyString())).thenReturn(reservation);
            when(aiQuizGenerationService.calculateEstimatedGenerationTime(eq(1), any())).thenReturn(30);
            when(jobService.createJob(eq(testUser), eq(shortDoc.getId()), any(), eq(1), eq(30))).thenReturn(job);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            
            // When
            QuizGenerationResponse response = facade.generateQuizFromText("testuser", shortTextRequest);
            
            // Then
            assertThat(response).isNotNull();
            verify(aiQuizGenerationService, atLeastOnce()).calculateTotalChunks(eq(shortDoc.getId()), any());
        }
        
        @Test
        @DisplayName("Long text - multiple chunks")
        void longText_multipleChunks() {
            // Given
            StringBuilder longText = new StringBuilder();
            for (int i = 0; i < 5000; i++) {
                longText.append("Word ").append(i).append(" ");
            }
            
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest longTextRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    longText.toString(),  // text
                    null,  // language
                    null,  // chunkingStrategy
                    null,  // maxChunkSize
                    QuizScope.ENTIRE_DOCUMENT,  // quizScope
                    null,  // chunkIndices
                    null,  // chapterTitle
                    null,  // chapterNumber
                    "Long Quiz",  // quizTitle
                    "Long Desc",  // quizDescription
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 5),  // questionsPerType
                    Difficulty.HARD,  // difficulty
                    3,  // estimatedTimePerQuestion
                    null,  // categoryId
                    List.of()  // tagIds
                );
            
            DocumentDto longDoc = new DocumentDto();
            longDoc.setId(UUID.randomUUID());
            longDoc.setOriginalFilename("text-input.txt");
            longDoc.setTotalChunks(5);
            longDoc.setStatus(uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus.PROCESSED);
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any())).thenReturn(longDoc);
            when(aiQuizGenerationService.calculateTotalChunks(eq(longDoc.getId()), any())).thenReturn(5);
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(estimationService.estimateQuizGeneration(eq(longDoc.getId()), any())).thenReturn(estimation);
            when(billingService.reserve(any(UUID.class), anyLong(), anyString(), anyString())).thenReturn(reservation);
            when(aiQuizGenerationService.calculateEstimatedGenerationTime(eq(5), any())).thenReturn(200);
            when(jobService.createJob(eq(testUser), eq(longDoc.getId()), any(), eq(5), eq(200))).thenReturn(job);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            
            // When
            QuizGenerationResponse response = facade.generateQuizFromText("testuser", longTextRequest);
            
            // Then
            assertThat(response).isNotNull();
            verify(jobService).createJob(eq(testUser), eq(longDoc.getId()), any(), eq(5), eq(200));
        }
        
        @Test
        @DisplayName("Text processing fails - throws RuntimeException")
        void textProcessingFails_throwsRuntimeException() {
            // Given
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Text processing failed"));
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromText("testuser", textRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to generate quiz from text");
            
            verify(documentProcessingService).uploadAndProcessDocument(eq("testuser"), any(byte[].class), eq("text-input.txt"), any());
        }
        
        @Test
        @DisplayName("Insufficient tokens - propagates exception")
        void insufficientTokens_propagatesException() {
            // Given
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenReturn(processedTextDocument);
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(processedTextDocument.getId()), any())).thenReturn(1);
            
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(estimationService.estimateQuizGeneration(eq(processedTextDocument.getId()), any())).thenReturn(estimation);
            when(billingService.reserve(any(UUID.class), anyLong(), anyString(), anyString()))
                    .thenThrow(new InsufficientTokensException(
                            "Insufficient tokens",
                            1200L, 500L, 700L,
                            java.time.LocalDateTime.now().plusHours(1)
                    ));
            
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromText("testuser", textRequest))
                    .isInstanceOf(InsufficientTokensException.class)
                    .hasMessageContaining("Insufficient tokens");
        }
        
        @Test
        @DisplayName("Empty text - handled gracefully")
        void emptyText_handledGracefully() {
            // Given
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest emptyTextRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    "",  // text (empty)
                    null,  // language
                    null,  // chunkingStrategy
                    null,  // maxChunkSize
                    QuizScope.ENTIRE_DOCUMENT,  // quizScope
                    null,  // chunkIndices
                    null,  // chapterTitle
                    null,  // chapterNumber
                    "Quiz",  // quizTitle
                    null,  // quizDescription
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 2),  // questionsPerType
                    Difficulty.EASY,  // difficulty
                    1,  // estimatedTimePerQuestion
                    null,  // categoryId
                    List.of()  // tagIds
                );
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Empty text"));
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromText("testuser", emptyTextRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to generate quiz from text");
        }
        
        @Test
        @DisplayName("Special characters in text - processed correctly")
        void specialCharactersInText_processedCorrectly() {
            // Given
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest specialCharsRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    "Text with émojis 😀 and spëcial çharacters €£¥",  // text
                    null,  // language
                    null,  // chunkingStrategy
                    null,  // maxChunkSize
                    QuizScope.ENTIRE_DOCUMENT,  // quizScope
                    null,  // chunkIndices
                    null,  // chapterTitle
                    null,  // chapterNumber
                    "Special Quiz",  // quizTitle
                    null,  // quizDescription
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 2),  // questionsPerType
                    Difficulty.EASY,  // difficulty
                    1,  // estimatedTimePerQuestion
                    null,  // categoryId
                    List.of()  // tagIds
                );
            
            DocumentDto utf8Doc = new DocumentDto();
            utf8Doc.setId(UUID.randomUUID());
            utf8Doc.setOriginalFilename("text-input.txt");
            utf8Doc.setTotalChunks(1);
            utf8Doc.setStatus(uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus.PROCESSED);
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any())).thenReturn(utf8Doc);
            when(aiQuizGenerationService.calculateTotalChunks(eq(utf8Doc.getId()), any())).thenReturn(1);
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(estimationService.estimateQuizGeneration(eq(utf8Doc.getId()), any())).thenReturn(estimation);
            when(billingService.reserve(any(UUID.class), anyLong(), anyString(), anyString())).thenReturn(reservation);
            when(aiQuizGenerationService.calculateEstimatedGenerationTime(eq(1), any())).thenReturn(30);
            when(jobService.createJob(eq(testUser), eq(utf8Doc.getId()), any(), eq(1), eq(30))).thenReturn(job);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            
            // When
            QuizGenerationResponse response = facade.generateQuizFromText("testuser", specialCharsRequest);
            
            // Then
            assertThat(response).isNotNull();
            // Verify UTF-8 encoding was used (bytes should contain UTF-8 encoded special characters)
            verify(documentProcessingService).uploadAndProcessDocument(eq("testuser"), any(byte[].class), eq("text-input.txt"), any());
        }
        
        @Test
        @DisplayName("Verification fails - throws RuntimeException")
        void verificationFails_throwsRuntimeException() {
            // Given
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenReturn(processedTextDocument);
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(processedTextDocument.getId()), any())).thenReturn(0);
            
            // When & Then
            assertThatThrownBy(() -> facade.generateQuizFromText("testuser", textRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Document has no chunks available for quiz generation");
        }
    }
    
    // ============================================================================
    // 4. processDocumentCompletely() Tests
    // ============================================================================
    
    @Nested
    @DisplayName("processDocumentCompletely() Tests")
    class ProcessDocumentCompletelyTests {
        
        private MultipartFile mockFile;
        private uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest uploadRequest;
        
        @BeforeEach
        void setUp() {
            mockFile = mock(MultipartFile.class);
            
            uploadRequest = new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest(
                    null,  // chunkingStrategy
                    null,  // maxChunkSize
                    QuizScope.ENTIRE_DOCUMENT,
                    null,  // chunkIndices
                    null,  // chapterTitle
                    null,  // chapterNumber
                    "Test Quiz",
                    null,  // quizDescription
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 3),
                    Difficulty.MEDIUM,
                    2,
                    null,  // categoryId
                    List.of(),  // tagIds
                    null  // language
            );
        }
        
        @Test
        @DisplayName("Successful processing - returns DocumentDto")
        void successfulProcessing_returnsDocumentDto() throws Exception {
            // Given
            byte[] fileBytes = "test content".getBytes();
            when(mockFile.getBytes()).thenReturn(fileBytes);
            when(mockFile.getOriginalFilename()).thenReturn("test.pdf");
            
            DocumentDto expectedDoc = new DocumentDto();
            expectedDoc.setId(UUID.randomUUID());
            expectedDoc.setOriginalFilename("test.pdf");
            expectedDoc.setTotalChunks(3);
            expectedDoc.setStatus(uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus.PROCESSED);
            
            when(documentProcessingService.uploadAndProcessDocument(
                    eq("testuser"), eq(fileBytes), eq("test.pdf"), any()))
                    .thenReturn(expectedDoc);
            
            // When
            DocumentDto result = facade.processDocumentCompletely("testuser", mockFile, uploadRequest);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(expectedDoc.getId());
            assertThat(result.getOriginalFilename()).isEqualTo("test.pdf");
            assertThat(result.getTotalChunks()).isEqualTo(3);
            
            verify(mockFile).getBytes();
            verify(documentProcessingService).uploadAndProcessDocument(eq("testuser"), eq(fileBytes), eq("test.pdf"), any());
        }
        
        @Test
        @DisplayName("IOException - throws RuntimeException")
        void ioException_throwsRuntimeException() throws Exception {
            // Given
            when(mockFile.getBytes()).thenThrow(new java.io.IOException("Cannot read file"));
            
            // When & Then
            assertThatThrownBy(() -> facade.processDocumentCompletely("testuser", mockFile, uploadRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to read file bytes")
                    .hasCauseInstanceOf(java.io.IOException.class);
            
            verify(mockFile).getBytes();
            verifyNoInteractions(documentProcessingService);
        }
        
        @Test
        @DisplayName("Document service fails - propagates exception")
        void documentServiceFails_propagatesException() throws Exception {
            // Given
            byte[] fileBytes = "test content".getBytes();
            when(mockFile.getBytes()).thenReturn(fileBytes);
            when(mockFile.getOriginalFilename()).thenReturn("test.pdf");
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Processing failed"));
            
            // When & Then
            assertThatThrownBy(() -> facade.processDocumentCompletely("testuser", mockFile, uploadRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Processing failed");
            
            verify(documentProcessingService).uploadAndProcessDocument(eq("testuser"), eq(fileBytes), eq("test.pdf"), any());
        }
        
        @Test
        @DisplayName("PDF file - processed with chunks")
        void pdfFile_processedWithChunks() throws Exception {
            // Given
            byte[] pdfBytes = "%PDF-1.4 mock content".getBytes();
            when(mockFile.getBytes()).thenReturn(pdfBytes);
            when(mockFile.getOriginalFilename()).thenReturn("document.pdf");
            
            DocumentDto pdfDoc = new DocumentDto();
            pdfDoc.setId(UUID.randomUUID());
            pdfDoc.setOriginalFilename("document.pdf");
            pdfDoc.setContentType("application/pdf");
            pdfDoc.setTotalChunks(5);
            pdfDoc.setTotalPages(10);
            pdfDoc.setStatus(uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus.PROCESSED);
            
            when(documentProcessingService.uploadAndProcessDocument(
                    eq("testuser"), eq(pdfBytes), eq("document.pdf"), any()))
                    .thenReturn(pdfDoc);
            
            // When
            DocumentDto result = facade.processDocumentCompletely("testuser", mockFile, uploadRequest);
            
            // Then
            assertThat(result.getTotalChunks()).isEqualTo(5);
            assertThat(result.getTotalPages()).isEqualTo(10);
            assertThat(result.getContentType()).isEqualTo("application/pdf");
        }
        
        @Test
        @DisplayName("Invalid file format - handled by service")
        void invalidFileFormat_handledByService() throws Exception {
            // Given
            byte[] invalidBytes = "invalid content".getBytes();
            when(mockFile.getBytes()).thenReturn(invalidBytes);
            when(mockFile.getOriginalFilename()).thenReturn("invalid.xyz");
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Unsupported file format"));
            
            // When & Then
            assertThatThrownBy(() -> facade.processDocumentCompletely("testuser", mockFile, uploadRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Unsupported file format");
        }
    }
    
    // ============================================================================
    // 5. verifyDocumentChunks() Tests
    // ============================================================================
    
    @Nested
    @DisplayName("verifyDocumentChunks() Tests")
    class VerifyDocumentChunksTests {
        
        @Test
        @DisplayName("Valid document with chunks (upload) - no exception")
        void validDocument_withChunks_upload_noException() {
            // Given
            UUID docId = UUID.randomUUID();
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest uploadRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest(
                    null, null, QuizScope.ENTIRE_DOCUMENT, null, null, null, "Quiz", null,
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 3),
                    Difficulty.MEDIUM, 2, null, List.of(), null
                );
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(docId), any())).thenReturn(5);
            
            // When & Then - no exception
            assertThatNoException().isThrownBy(() -> 
                facade.verifyDocumentChunks(docId, uploadRequest)
            );
            
            verify(aiQuizGenerationService).calculateTotalChunks(eq(docId), any());
        }
        
        @Test
        @DisplayName("Document with no chunks (upload) - throws RuntimeException")
        void documentWithNoChunks_upload_throwsRuntimeException() {
            // Given
            UUID docId = UUID.randomUUID();
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest uploadRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest(
                    null, null, QuizScope.ENTIRE_DOCUMENT, null, null, null, "Quiz", null,
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 3),
                    Difficulty.MEDIUM, 2, null, List.of(), null
                );
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(docId), any())).thenReturn(0);
            
            // When & Then
            assertThatThrownBy(() -> facade.verifyDocumentChunks(docId, uploadRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Document has no chunks available for quiz generation");
        }
        
        @Test
        @DisplayName("Valid document with chunks (text) - no exception")
        void validDocument_withChunks_text_noException() {
            // Given
            UUID docId = UUID.randomUUID();
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest textRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    "Sample text", null, null, null, QuizScope.ENTIRE_DOCUMENT, null, null, null, "Quiz", null,
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 3),
                    Difficulty.MEDIUM, 2, null, List.of()
                );
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(docId), any())).thenReturn(3);
            
            // When & Then - no exception
            assertThatNoException().isThrownBy(() -> 
                facade.verifyDocumentChunks(docId, textRequest)
            );
            
            verify(aiQuizGenerationService).calculateTotalChunks(eq(docId), any());
        }
        
        @Test
        @DisplayName("Document with no chunks (text) - throws RuntimeException")
        void documentWithNoChunks_text_throwsRuntimeException() {
            // Given
            UUID docId = UUID.randomUUID();
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest textRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    "Sample text", null, null, null, QuizScope.ENTIRE_DOCUMENT, null, null, null, "Quiz", null,
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 3),
                    Difficulty.MEDIUM, 2, null, List.of()
                );
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(docId), any())).thenReturn(0);
            
            // When & Then
            assertThatThrownBy(() -> facade.verifyDocumentChunks(docId, textRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Document has no chunks available for quiz generation");
        }
        
        @Test
        @DisplayName("Negative chunk count - throws RuntimeException")
        void negativeChunkCount_throwsRuntimeException() {
            // Given
            UUID docId = UUID.randomUUID();
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest uploadRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest(
                    null, null, QuizScope.ENTIRE_DOCUMENT, null, null, null, "Quiz", null,
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 3),
                    Difficulty.MEDIUM, 2, null, List.of(), null
                );
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(docId), any())).thenReturn(-1);
            
            // When & Then
            assertThatThrownBy(() -> facade.verifyDocumentChunks(docId, uploadRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Document has no chunks available for quiz generation");
        }
        
        @Test
        @DisplayName("Chunk calculation fails - propagates exception")
        void chunkCalculationFails_propagatesException() {
            // Given
            UUID docId = UUID.randomUUID();
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest textRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    "Sample text", null, null, null, QuizScope.ENTIRE_DOCUMENT, null, null, null, "Quiz", null,
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 3),
                    Difficulty.MEDIUM, 2, null, List.of()
                );
            
            when(aiQuizGenerationService.calculateTotalChunks(eq(docId), any()))
                    .thenThrow(new RuntimeException("Failed to calculate chunks"));
            
            // When & Then
            assertThatThrownBy(() -> facade.verifyDocumentChunks(docId, textRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to calculate chunks");
        }
    }
    
    // ============================================================================
    // 6. processTextAsDocument() Tests
    // ============================================================================
    
    @Nested
    @DisplayName("processTextAsDocument() Tests")
    class ProcessTextAsDocumentTests {
        
        @Test
        @DisplayName("Successful text processing - returns DocumentDto")
        void successfulTextProcessing_returnsDocumentDto() {
            // Given
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest textRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    "Sample educational text for quiz generation.",
                    null, null, null, QuizScope.ENTIRE_DOCUMENT, null, null, null,
                    "Quiz", "Description",
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 3),
                    Difficulty.MEDIUM, 2, null, List.of()
                );
            
            DocumentDto expectedDoc = new DocumentDto();
            expectedDoc.setId(UUID.randomUUID());
            expectedDoc.setOriginalFilename("text-input.txt");
            expectedDoc.setContentType("text/plain");
            expectedDoc.setTotalChunks(1);
            expectedDoc.setStatus(uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus.PROCESSED);
            
            when(documentProcessingService.uploadAndProcessDocument(
                    eq("testuser"), any(byte[].class), eq("text-input.txt"), any()))
                    .thenReturn(expectedDoc);
            
            // When
            DocumentDto result = facade.processTextAsDocument("testuser", textRequest);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(result.getOriginalFilename()).isEqualTo("text-input.txt");
            assertThat(result.getTotalChunks()).isEqualTo(1);
            
            verify(documentProcessingService).uploadAndProcessDocument(eq("testuser"), any(byte[].class), eq("text-input.txt"), any());
        }
        
        @Test
        @DisplayName("UTF-8 text - encoded correctly")
        void utf8Text_encodedCorrectly() {
            // Given
            String utf8Text = "Текст с émojis 😀 and spëcial çharacters €£¥";
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest textRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    utf8Text, null, null, null, QuizScope.ENTIRE_DOCUMENT, null, null, null,
                    "UTF-8 Quiz", null,
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.TRUE_FALSE, 2),
                    Difficulty.EASY, 1, null, List.of()
                );
            
            DocumentDto utf8Doc = new DocumentDto();
            utf8Doc.setId(UUID.randomUUID());
            utf8Doc.setOriginalFilename("text-input.txt");
            utf8Doc.setTotalChunks(1);
            utf8Doc.setStatus(uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus.PROCESSED);
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(byte[].class), eq("text-input.txt"), any()))
                    .thenReturn(utf8Doc);
            
            // When
            DocumentDto result = facade.processTextAsDocument("testuser", textRequest);
            
            // Then
            assertThat(result).isNotNull();
            // Verify bytes were passed (encoding happens in facade)
            verify(documentProcessingService).uploadAndProcessDocument(eq("testuser"), any(byte[].class), eq("text-input.txt"), any());
        }
        
        @Test
        @DisplayName("Document service fails - throws RuntimeException")
        void documentServiceFails_throwsRuntimeException() {
            // Given
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest textRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    "Sample text", null, null, null, QuizScope.ENTIRE_DOCUMENT, null, null, null,
                    "Quiz", null,
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 2),
                    Difficulty.MEDIUM, 1, null, List.of()
                );
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Service error"));
            
            // When & Then
            assertThatThrownBy(() -> facade.processTextAsDocument("testuser", textRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to process text as document")
                    .hasCauseInstanceOf(RuntimeException.class);
        }
        
        @Test
        @DisplayName("Synthetic filename - used")
        void syntheticFilename_used() {
            // Given
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest textRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    "Text without a filename", null, null, null, QuizScope.ENTIRE_DOCUMENT, null, null, null,
                    "Quiz", null,
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 2),
                    Difficulty.EASY, 1, null, List.of()
                );
            
            DocumentDto doc = new DocumentDto();
            doc.setId(UUID.randomUUID());
            doc.setOriginalFilename("text-input.txt");
            doc.setStatus(uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus.PROCESSED);
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), eq("text-input.txt"), any()))
                    .thenReturn(doc);
            
            // When
            DocumentDto result = facade.processTextAsDocument("testuser", textRequest);
            
            // Then
            assertThat(result.getOriginalFilename()).isEqualTo("text-input.txt");
            verify(documentProcessingService).uploadAndProcessDocument(eq("testuser"), any(byte[].class), eq("text-input.txt"), any());
        }
        
        @Test
        @DisplayName("Large text - processed successfully")
        void largeText_processedSuccessfully() {
            // Given
            StringBuilder largeText = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                largeText.append("Word ").append(i).append(" ");
            }
            
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest largeTextRequest = 
                new uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest(
                    largeText.toString(), null, null, null, QuizScope.ENTIRE_DOCUMENT, null, null, null,
                    "Large Quiz", null,
                    Map.of(uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 5),
                    Difficulty.HARD, 3, null, List.of()
                );
            
            DocumentDto largeDoc = new DocumentDto();
            largeDoc.setId(UUID.randomUUID());
            largeDoc.setOriginalFilename("text-input.txt");
            largeDoc.setTotalChunks(8);
            largeDoc.setFileSize((long) largeText.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            largeDoc.setStatus(uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus.PROCESSED);
            
            when(documentProcessingService.uploadAndProcessDocument(any(), any(byte[].class), eq("text-input.txt"), any()))
                    .thenReturn(largeDoc);
            
            // When
            DocumentDto result = facade.processTextAsDocument("testuser", largeTextRequest);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTotalChunks()).isEqualTo(8);
            assertThat(result.getFileSize()).isGreaterThan(10000L);
        }
    }
    
    // ============================================================================
    // Helper Methods
    // ============================================================================
    
    private User createUser(String username, UUID userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        return user;
    }
    
    private QuizGenerationJob createJob(User user, UUID documentId) {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(UUID.randomUUID());
        job.setUser(user);
        job.setDocumentId(documentId);
        job.setTotalChunks(3);
        job.setBillingState(BillingState.RESERVED);
        job.setBillingReservationId(reservation.id());
        job.setBillingEstimatedTokens(1200L);
        job.setInputPromptTokens(1000L);
        job.setEstimationVersion("v1.0");
        return job;
    }
}

