package uk.gegc.quizmaker.service.quiz.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.api.dto.*;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizServiceImpl;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizCommandService;
 import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("QuizService Additional Coverage Tests")
class QuizServiceImplAdditionalTest {

    @Mock
    private QuizRepository quizRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private QuizMapper quizMapper;
    @Mock
    private UserRepository userRepository;
    @Mock
    private QuestionHandlerFactory questionHandlerFactory;
    @Mock
    private QuizGenerationJobRepository jobRepository;
    @Mock
    private QuizGenerationJobService jobService;
    @Mock
    private AiQuizGenerationService aiQuizGenerationService;
    @Mock
    private DocumentProcessingService documentProcessingService;
    @Mock
    private QuizHashCalculator quizHashCalculator;
    @Mock
    private BillingService billingService;
    @Mock
    private InternalBillingService internalBillingService;
    @Mock
    private EstimationService estimationService;
    @Mock
    private FeatureFlags featureFlags;
    @Mock
    private AppPermissionEvaluator appPermissionEvaluator;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private Authentication authentication;
    @Mock
    QuizQueryService quizQueryService;
    @Mock
    QuizCommandService quizCommandService;

    @InjectMocks
    private QuizServiceImpl quizService;

    private User testUser;
    private User moderatorUser;

    @BeforeEach
    void setUp() {
        testUser = createTestUser();
        moderatorUser = createModeratorUser();
        
        lenient().when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        lenient().when(userRepository.findByEmail("testuser")).thenReturn(Optional.empty());
        lenient().when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
        lenient().when(userRepository.findByEmail("moderator")).thenReturn(Optional.empty());
        
        lenient().when(appPermissionEvaluator.hasPermission(eq(testUser), any(PermissionName.class))).thenReturn(false);
        lenient().when(appPermissionEvaluator.hasPermission(eq(moderatorUser), any(PermissionName.class))).thenReturn(true);
        
        lenient().when(quizHashCalculator.calculateContentHash(any())).thenReturn("hash123");
    }

    private User createTestUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setRoles(new HashSet<>());
        return user;
    }

    private User createModeratorUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("moderator");
        user.setEmail("mod@example.com");
        
        Role role = new Role();
        role.setRoleId(1L);
        role.setRoleName("ROLE_MODERATOR");
        
        Set<Permission> permissions = new HashSet<>();
        Permission quizModerate = new Permission();
        quizModerate.setPermissionId(1L);
        quizModerate.setPermissionName("QUIZ_MODERATE");
        permissions.add(quizModerate);
        
        role.setPermissions(permissions);
        user.setRoles(Set.of(role));
        return user;
    }

    @Nested
    @DisplayName("getQuizzes Scope Tests")
    class GetQuizzesScopeTests {

        @Test
        @DisplayName("scope=public without authentication returns public quizzes")
        void getQuizzes_scopePublicWithoutAuth_returnsPublicQuizzes() {
            // Given - Line 145: user = null
            Pageable pageable = PageRequest.of(0, 10);
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            
            QuizDto quizDto = new QuizDto(UUID.randomUUID(), null, null, "Test", "Desc",
                    Visibility.PUBLIC, null, QuizStatus.PUBLISHED, 10, false, false, 5, List.of(), null, null);
            Page<QuizDto> resultPage = new PageImpl<>(List.of(quizDto));
            
            when(quizQueryService.getQuizzes(pageable, criteria, "public", null)).thenReturn(resultPage);

            // When - authentication = null, so user = null at line 145
            Page<QuizDto> result = quizService.getQuizzes(pageable, criteria, "public", null);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("scope=me without authentication throws ForbiddenException")
        void getQuizzes_scopeMeWithoutAuth_throwsForbiddenException() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            
            when(quizQueryService.getQuizzes(pageable, criteria, "me", null))
                    .thenThrow(new ForbiddenException("Authentication required for scope=me"));

            // When & Then
            assertThatThrownBy(() -> quizService.getQuizzes(pageable, criteria, "me", null))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Authentication required for scope=me");
        }

        @Test
        @DisplayName("scope=all without moderator permissions throws ForbiddenException")
        void getQuizzes_scopeAllWithoutModeratorPermissions_throwsForbiddenException() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            
            when(quizQueryService.getQuizzes(pageable, criteria, "all", authentication))
                    .thenThrow(new ForbiddenException("Moderator/Admin permissions required for scope=all"));

            // When & Then
            assertThatThrownBy(() -> quizService.getQuizzes(pageable, criteria, "all", authentication))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Moderator/Admin permissions required for scope=all");
        }

        @Test
        @DisplayName("scope=me returns user's quizzes")
        void getQuizzes_scopeMe_returnsUserQuizzes() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            
            QuizDto quizDto = new QuizDto(UUID.randomUUID(), testUser.getId(), null, "Test", "Desc",
                    Visibility.PRIVATE, null, QuizStatus.DRAFT, 10, false, false, 5, List.of(), null, null);
            Page<QuizDto> resultPage = new PageImpl<>(List.of(quizDto));
            
            when(quizQueryService.getQuizzes(pageable, criteria, "me", authentication)).thenReturn(resultPage);

            // When
            Page<QuizDto> result = quizService.getQuizzes(pageable, criteria, "me", authentication);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("scope=all with moderator returns all quizzes")
        void getQuizzes_scopeAllWithModerator_returnsAllQuizzes() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            
            QuizDto quizDto = new QuizDto(UUID.randomUUID(), null, null, "Test", "Desc",
                    Visibility.PUBLIC, null, QuizStatus.PUBLISHED, 10, false, false, 5, List.of(), null, null);
            Page<QuizDto> resultPage = new PageImpl<>(List.of(quizDto));
            
            when(quizQueryService.getQuizzes(pageable, criteria, "all", authentication)).thenReturn(resultPage);

            // When
            Page<QuizDto> result = quizService.getQuizzes(pageable, criteria, "all", authentication);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("getQuizById Access Control Tests")
    class GetQuizByIdAccessControlTests {

        @Test
        @DisplayName("Anonymous user accessing private quiz throws ForbiddenException")
        void getQuizById_anonymousUserAccessingPrivateQuiz_throwsForbiddenException() {
            // Given
            UUID quizId = UUID.randomUUID();
            
            when(quizQueryService.getQuizById(quizId, null))
                    .thenThrow(new ForbiddenException("Access denied: quiz is not public"));

            // When & Then
            assertThatThrownBy(() -> quizService.getQuizById(quizId, null))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Access denied: quiz is not public");
        }

        @Test
        @DisplayName("Non-owner accessing draft quiz throws ForbiddenException")
        void getQuizById_nonOwnerAccessingDraftQuiz_throwsForbiddenException() {
            // Given
            UUID quizId = UUID.randomUUID();
            
            lenient().when(authentication.getName()).thenReturn("otheruser");
            lenient().when(authentication.isAuthenticated()).thenReturn(true);
            
            when(quizQueryService.getQuizById(quizId, authentication))
                    .thenThrow(new ForbiddenException("Access denied: quiz is not public"));

            // When & Then
            assertThatThrownBy(() -> quizService.getQuizById(quizId, authentication))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Access denied: quiz is not public");
        }

        @Test
        @DisplayName("Anonymous user can access PUBLIC published quiz")
        void getQuizById_anonymousUserPublicQuiz_returnsQuiz() {
            // Given - Line 213: return quizMapper.toDto(quiz);
            UUID quizId = UUID.randomUUID();

            QuizDto expectedDto = new QuizDto(quizId, null, null, "Test", "Desc",
                    Visibility.PUBLIC, null, QuizStatus.PUBLISHED, 10, false, false, 5, List.of(), null, null);

            when(quizQueryService.getQuizById(quizId, null)).thenReturn(expectedDto);

            // When
            QuizDto result = quizService.getQuizById(quizId, null);

            // Then - Line 213 should be covered
            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(quizId);
        }
    }

    @Nested
    @DisplayName("updateQuiz Tests")
    class UpdateQuizTests {

        @Test
        @DisplayName("Non-owner updating quiz throws ForbiddenException")
        void updateQuiz_nonOwner_throwsForbiddenException() {
            // Given
            UUID quizId = UUID.randomUUID();
            UpdateQuizRequest request = new UpdateQuizRequest("Updated", "Desc", null, null, null, null, null, null, null, null);

            when(quizCommandService.updateQuiz("otheruser", quizId, request))
                    .thenThrow(new ForbiddenException("Not allowed to update this quiz"));

            // When & Then
            assertThatThrownBy(() -> quizService.updateQuiz("otheruser", quizId, request))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Not allowed to update this quiz");
        }

        @Test
        @DisplayName("Updating PENDING_REVIEW quiz reverts to DRAFT")
        void updateQuiz_pendingReview_revertsToNot() {
            // Given
            UUID quizId = UUID.randomUUID();
            UpdateQuizRequest request = new UpdateQuizRequest("Updated", "Desc", null, null, null, null, null, null, null, null);
            QuizDto expectedDto = new QuizDto(quizId, testUser.getId(), null, "Updated", "Desc",
                    Visibility.PRIVATE, null, QuizStatus.DRAFT, 10, false, false, 5, List.of(), null, null);

            when(quizCommandService.updateQuiz("testuser", quizId, request)).thenReturn(expectedDto);

            // When
            QuizDto result = quizService.updateQuiz("testuser", quizId, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(QuizStatus.DRAFT);
        }

        @Test
        @DisplayName("Updating PUBLISHED quiz with content hash change reverts to PENDING_REVIEW")
        void updateQuiz_publishedQuizContentHashChanges_setsPendingReview() {
            // Given
            UUID quizId = UUID.randomUUID();
            UpdateQuizRequest request = new UpdateQuizRequest(
                    "Updated Title", "Updated Desc", 
                    null, null, null, null, null, null, null, null);

            QuizDto afterDto = new QuizDto(quizId, testUser.getId(), null, "Updated Title", "Updated Desc",
                    Visibility.PRIVATE, null, QuizStatus.PENDING_REVIEW, 10, false, false, 5, List.of(), null, null);

            when(quizCommandService.updateQuiz("testuser", quizId, request)).thenReturn(afterDto);

            // When
            QuizDto result = quizService.updateQuiz("testuser", quizId, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(QuizStatus.PENDING_REVIEW);
        }

    }  // Close UpdateQuizTests

    @Nested
    @DisplayName("deleteQuizById Tests")
    class DeleteQuizByIdTests {

        @Test
        @DisplayName("Non-owner deleting quiz throws ForbiddenException")
        void deleteQuizById_nonOwner_throwsForbiddenException() {
            // Given
            UUID quizId = UUID.randomUUID();
            User otherUser = new User();
            otherUser.setId(UUID.randomUUID());
            otherUser.setUsername("otheruser");

            Quiz quiz = new Quiz();
            quiz.setId(quizId);
            quiz.setCreator(testUser);

            lenient().when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
            lenient().when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            lenient().when(userRepository.findByEmail("otheruser")).thenReturn(Optional.empty());
            lenient().when(appPermissionEvaluator.hasPermission(eq(otherUser), any(PermissionName.class))).thenReturn(false);

            // When & Then
            assertThatThrownBy(() -> quizService.deleteQuizById("otheruser", quizId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Not allowed to delete this quiz");
        }
    }  // Close DeleteQuizByIdTests

    @Nested
    @DisplayName("deleteQuizzesByIds Tests")
    class DeleteQuizzesByIdsTests {

        @Test
        @DisplayName("Empty list returns early")
        void deleteQuizzesByIds_emptyList_returnsEarly() {
            // Given
            List<UUID> emptyList = List.of();

            // When
            quizService.deleteQuizzesByIds("testuser", emptyList);

            // Then
            verify(quizRepository, never()).findAllById(any());
        }
    }

    @Nested
    @DisplayName("Document Verification Tests")
    class DocumentVerificationTests {

        @Test
        @DisplayName("verifyDocumentChunks throws when no chunks available - Line 436")
        void verifyDocumentChunks_noChunks_throwsRuntimeException() {
            // Given
            UUID documentId = UUID.randomUUID();
            GenerateQuizFromUploadRequest request =
                mock(GenerateQuizFromUploadRequest.class);

            // Mock calculateTotalChunks to return 0 (no chunks)
            when(aiQuizGenerationService.calculateTotalChunks(eq(documentId), any()))
                .thenReturn(0);

            // When & Then - Line 436: throw when totalChunks <= 0
            assertThatThrownBy(() -> quizService.verifyDocumentChunks(documentId, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Document has no chunks available for quiz generation");
        }

        @Test
        @DisplayName("verifyDocumentChunks (text request) throws when no chunks")
        void verifyDocumentChunks_textRequest_noChunks_throwsRuntimeException() {
            // Given
            UUID documentId = UUID.randomUUID();
            GenerateQuizFromTextRequest request =
                mock(GenerateQuizFromTextRequest.class);

            // Mock calculateTotalChunks to return 0 (no chunks)
            when(aiQuizGenerationService.calculateTotalChunks(eq(documentId), any()))
                .thenReturn(0);

            // When & Then - Line 450: throw when totalChunks <= 0
            assertThatThrownBy(() -> quizService.verifyDocumentChunks(documentId, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Document has no chunks available for quiz generation");
        }

        @Test
        @DisplayName("processDocumentCompletely throws when file.getBytes() fails")
        void processDocumentCompletely_ioException_throwsRuntimeException() throws Exception {
            // Given
            MultipartFile mockFile = mock(MultipartFile.class);
            GenerateQuizFromUploadRequest request =
                mock(GenerateQuizFromUploadRequest.class);

            // Mock file.getBytes() to throw IOException (line 415)
            when(mockFile.getBytes()).thenThrow(new java.io.IOException("Failed to read file"));

            // When & Then - Lines 423-424: catch IOException and wrap in RuntimeException
            assertThatThrownBy(() -> quizService.processDocumentCompletely("testuser", mockFile, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to read file bytes: Failed to read file");
        }

        @Test
        @DisplayName("generateQuizFromUpload generic Exception wrapped")
        void generateQuizFromUpload_genericException_wrapsInRuntimeException() throws Exception {
            // Given
            MultipartFile mockFile = mock(MultipartFile.class);
            GenerateQuizFromUploadRequest request =
                mock(GenerateQuizFromUploadRequest.class);

            when(mockFile.getBytes()).thenReturn("test content".getBytes());
            
            // Mock to throw generic exception during document processing
            when(documentProcessingService.uploadAndProcessDocument(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Document upload failed"));

            // When & Then - Lines 375-377: catch Exception and wrap in RuntimeException
            assertThatThrownBy(() -> quizService.generateQuizFromUpload("testuser", mockFile, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to generate quiz from upload: Document upload failed");
        }

        @Test
        @DisplayName("generateQuizFromUpload InsufficientTokensException propagates")
        void generateQuizFromUpload_insufficientTokens_propagates() throws Exception {
            // Given - Use spy to mock startQuizGeneration
            QuizServiceImpl spyService = spy(quizService);
            
            MultipartFile mockFile = mock(MultipartFile.class);
            GenerateQuizFromUploadRequest request =
                mock(GenerateQuizFromUploadRequest.class);
            
            UUID documentId = UUID.randomUUID();
            DocumentDto mockDoc = mock(DocumentDto.class);
            when(mockDoc.getId()).thenReturn(documentId);
            
            // Mock processDocumentCompletely to succeed
            doReturn(mockDoc).when(spyService).processDocumentCompletely(eq("testuser"), eq(mockFile), eq(request));
            // Mock verifyDocumentChunks to succeed
            doNothing().when(spyService).verifyDocumentChunks(eq(documentId), eq(request));

            // Mock startQuizGeneration to throw InsufficientTokensException
            InsufficientTokensException expectedException =
                new InsufficientTokensException(
                    "Not enough tokens", 100L, 50L, 50L, java.time.LocalDateTime.now()
                );
            doThrow(expectedException).when(spyService).startQuizGeneration(eq("testuser"), any());

            // When & Then - Lines 372-374: catch and rethrow InsufficientTokensException
            assertThatThrownBy(() -> spyService.generateQuizFromUpload("testuser", mockFile, request))
                .isSameAs(expectedException);
        }

        @Test
        @DisplayName("generateQuizFromText InsufficientTokensException propagates")
        void generateQuizFromText_insufficientTokens_propagates() throws Exception {
            // Given - Use spy to mock startQuizGeneration
            QuizServiceImpl spyService = spy(quizService);
            
            GenerateQuizFromTextRequest request =
                mock(GenerateQuizFromTextRequest.class);
            when(request.text()).thenReturn("test text");
            
            UUID documentId = UUID.randomUUID();
            DocumentDto mockDoc = mock(DocumentDto.class);
            when(mockDoc.getId()).thenReturn(documentId);
            
            // Mock processTextAsDocument to succeed
            doReturn(mockDoc).when(spyService).processTextAsDocument(eq("testuser"), eq(request));
            // Mock verifyDocumentChunks to succeed
            doNothing().when(spyService).verifyDocumentChunks(eq(documentId), eq(request));

            // Mock startQuizGeneration to throw InsufficientTokensException
            InsufficientTokensException expectedException =
                new InsufficientTokensException(
                    "Not enough tokens", 100L, 50L, 50L, java.time.LocalDateTime.now()
                );
            doThrow(expectedException).when(spyService).startQuizGeneration(eq("testuser"), any());

            // When & Then - Lines 398-400: catch and rethrow InsufficientTokensException
            assertThatThrownBy(() -> spyService.generateQuizFromText("testuser", request))
                .isSameAs(expectedException);
        }

        @Test
        @DisplayName("startQuizGeneration with stale job retry succeeds")
        void startQuizGeneration_staleJobRetrySucceeds_createsJob() throws Exception {
            // Given
            GenerateQuizFromDocumentRequest request =
                mock(GenerateQuizFromDocumentRequest.class);
            when(request.documentId()).thenReturn(UUID.randomUUID());

            UUID jobId = UUID.randomUUID();
            QuizGenerationJob mockJob =
                new QuizGenerationJob();
            mockJob.setId(jobId);

            // Mock user lookup
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            
            // Mock estimation and reservation
            when(estimationService.estimateQuizGeneration(any(), any())).thenReturn(
                new EstimationDto(
                    100L, 100L, null, "USD", true, "~100 tokens", UUID.randomUUID()
                )
            );
            
            ReservationDto mockReservation =
                new ReservationDto(
                    UUID.randomUUID(), testUser.getId(), 
                    ReservationState.ACTIVE,
                    100L, 100L, LocalDateTime.now().plusMinutes(30),
                    null, LocalDateTime.now(), LocalDateTime.now()
                );
            when(billingService.reserve(eq(testUser.getId()), eq(100L), eq("quiz-generation"), anyString())).thenReturn(mockReservation);
            
            when(aiQuizGenerationService.calculateTotalChunks(any(), any())).thenReturn(3);
            when(aiQuizGenerationService.calculateEstimatedGenerationTime(eq(3), any())).thenReturn(60);

            // First call throws DataIntegrityViolationException with active_user_id in message
            DataIntegrityViolationException firstException =
                new DataIntegrityViolationException("Constraint violation on active_user_id");
            
            QuizGenerationJob staleJob =
                new QuizGenerationJob();
            staleJob.setId(UUID.randomUUID());
            
            // Use doThrow for the first call, then doReturn for retry
            doThrow(firstException).doReturn(mockJob)
                .when(jobService).createJob(eq(testUser), any(), any(), eq(3), eq(60));
            when(jobService.findAndCancelStaleJobForUser("testuser")).thenReturn(Optional.of(staleJob));
            when(jobRepository.save(any())).thenReturn(mockJob);

            // Mock transactionTemplate to actually execute the callback
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(null);
            });

            // When
            QuizGenerationResponse result =
                quizService.startQuizGeneration("testuser", request);

            // Then - Lines 524-529: stale job cancelled and retry succeeds
            assertThat(result).isNotNull();
            assertThat(result.jobId()).isEqualTo(jobId);
            // Verify stale job cancellation was attempted
            verify(jobService).findAndCancelStaleJobForUser("testuser");
            // Verify job creation was called twice (first fails, retry succeeds)
            verify(jobService, times(2)).createJob(eq(testUser), any(), any(), eq(3), eq(60));
            // Verify the stale job was found
            verify(jobService).findAndCancelStaleJobForUser("testuser");
        }

    }

}














