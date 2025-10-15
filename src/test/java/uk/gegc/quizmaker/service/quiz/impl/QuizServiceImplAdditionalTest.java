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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSearchCriteria;
import uk.gegc.quizmaker.features.quiz.api.dto.UpdateQuizRequest;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
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
    private uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator quizHashCalculator;
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
        @DisplayName("scope=public without authentication returns public quizzes - Line 145")
        void getQuizzes_scopePublicWithoutAuth_returnsPublicQuizzes() {
            // Given - Line 145: user = null
            Pageable pageable = PageRequest.of(0, 10);
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            
            Quiz quiz = new Quiz();
            quiz.setId(UUID.randomUUID());
            quiz.setVisibility(Visibility.PUBLIC);
            quiz.setStatus(QuizStatus.PUBLISHED);
            
            Page<Quiz> quizPage = new PageImpl<>(List.of(quiz));
            when(quizRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(quizPage);
            
            QuizDto quizDto = new QuizDto(quiz.getId(), null, null, "Test", "Desc",
                    Visibility.PUBLIC, null, QuizStatus.PUBLISHED, 10, false, false, 5, List.of(), null, null);
            when(quizMapper.toDto(quiz)).thenReturn(quizDto);

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
            
            when(authentication.getName()).thenReturn("testuser");
            when(authentication.isAuthenticated()).thenReturn(true);

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
            
            when(authentication.getName()).thenReturn("testuser");
            when(authentication.isAuthenticated()).thenReturn(true);
            
            Quiz quiz = new Quiz();
            quiz.setId(UUID.randomUUID());
            quiz.setCreator(testUser);
            
            Page<Quiz> quizPage = new PageImpl<>(List.of(quiz));
            when(quizRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(quizPage);
            
            QuizDto quizDto = new QuizDto(quiz.getId(), testUser.getId(), null, "Test", "Desc",
                    Visibility.PRIVATE, null, QuizStatus.DRAFT, 10, false, false, 5, List.of(), null, null);
            when(quizMapper.toDto(quiz)).thenReturn(quizDto);

            // When
            Page<QuizDto> result = quizService.getQuizzes(pageable, criteria, "me", authentication);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(quizRepository).findAll(any(Specification.class), eq(pageable));
        }

        @Test
        @DisplayName("scope=all with moderator returns all quizzes")
        void getQuizzes_scopeAllWithModerator_returnsAllQuizzes() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            
            when(authentication.getName()).thenReturn("moderator");
            when(authentication.isAuthenticated()).thenReturn(true);
            
            Quiz quiz = new Quiz();
            quiz.setId(UUID.randomUUID());
            
            Page<Quiz> quizPage = new PageImpl<>(List.of(quiz));
            when(quizRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(quizPage);
            
            QuizDto quizDto = new QuizDto(quiz.getId(), null, null, "Test", "Desc",
                    Visibility.PUBLIC, null, QuizStatus.PUBLISHED, 10, false, false, 5, List.of(), null, null);
            when(quizMapper.toDto(quiz)).thenReturn(quizDto);

            // When
            Page<QuizDto> result = quizService.getQuizzes(pageable, criteria, "all", authentication);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(quizRepository).findAll(any(Specification.class), eq(pageable));
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
            Quiz quiz = new Quiz();
            quiz.setId(quizId);
            quiz.setCreator(testUser);
            quiz.setVisibility(Visibility.PRIVATE);
            quiz.setStatus(QuizStatus.DRAFT);

            when(quizRepository.findByIdWithTags(quizId)).thenReturn(Optional.of(quiz));

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
            User otherUser = new User();
            otherUser.setId(UUID.randomUUID());
            otherUser.setUsername("otheruser");

            Quiz quiz = new Quiz();
            quiz.setId(quizId);
            quiz.setCreator(testUser);
            quiz.setVisibility(Visibility.PRIVATE);
            quiz.setStatus(QuizStatus.DRAFT);

            lenient().when(authentication.getName()).thenReturn("otheruser");
            lenient().when(authentication.isAuthenticated()).thenReturn(true);
            lenient().when(quizRepository.findByIdWithTags(quizId)).thenReturn(Optional.of(quiz));
            lenient().when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            lenient().when(userRepository.findByEmail("otheruser")).thenReturn(Optional.empty());
            lenient().when(appPermissionEvaluator.hasPermission(eq(otherUser), any(PermissionName.class))).thenReturn(false);

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
            Quiz quiz = new Quiz();
            quiz.setId(quizId);
            quiz.setVisibility(Visibility.PUBLIC);
            quiz.setStatus(QuizStatus.PUBLISHED);

            QuizDto expectedDto = new QuizDto(quizId, null, null, "Test", "Desc",
                    Visibility.PUBLIC, null, QuizStatus.PUBLISHED, 10, false, false, 5, List.of(), null, null);

            when(quizRepository.findByIdWithTags(quizId)).thenReturn(Optional.of(quiz));
            when(quizMapper.toDto(quiz)).thenReturn(expectedDto);

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
            User otherUser = new User();
            otherUser.setId(UUID.randomUUID());
            otherUser.setUsername("otheruser");

            Quiz quiz = new Quiz();
            quiz.setId(quizId);
            quiz.setCreator(testUser);

            UpdateQuizRequest request = new UpdateQuizRequest("Updated", "Desc", null, null, null, null, null, null, null, null);

            lenient().when(quizRepository.findByIdWithTags(quizId)).thenReturn(Optional.of(quiz));
            lenient().when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            lenient().when(userRepository.findByEmail("otheruser")).thenReturn(Optional.empty());
            lenient().when(appPermissionEvaluator.hasPermission(eq(otherUser), any(PermissionName.class))).thenReturn(false);

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
            Quiz quiz = new Quiz();
            quiz.setId(quizId);
            quiz.setCreator(testUser);
            quiz.setStatus(QuizStatus.PENDING_REVIEW);
            quiz.setCategory(null);
            quiz.setTags(new HashSet<>());

            UpdateQuizRequest request = new UpdateQuizRequest("Updated", "Desc", null, null, null, null, null, null, null, null);
            QuizDto expectedDto = new QuizDto(quizId, testUser.getId(), null, "Updated", "Desc",
                    Visibility.PRIVATE, null, QuizStatus.DRAFT, 10, false, false, 5, List.of(), null, null);

            when(quizRepository.findByIdWithTags(quizId)).thenReturn(Optional.of(quiz));
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(expectedDto);

            // When
            QuizDto result = quizService.updateQuiz("testuser", quizId, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
            verify(quizRepository).save(quiz);
        }

        @Test
        @DisplayName("Updating PUBLISHED quiz with content hash change reverts to PENDING_REVIEW - Lines 272-276")
        void updateQuiz_publishedQuizContentHashChanges_setsPendingReview() {
            // Given
            UUID quizId = UUID.randomUUID();
            Quiz quiz = new Quiz();
            quiz.setId(quizId);
            quiz.setCreator(testUser);
            quiz.setStatus(QuizStatus.PUBLISHED);
            quiz.setContentHash("oldHash");  // IMPORTANT: beforeContentHash != null
            quiz.setCategory(null);
            quiz.setTags(new HashSet<>());

            UpdateQuizRequest request = new UpdateQuizRequest(
                    "Updated Title", "Updated Desc", 
                    null, null, null, null, null, null, null, null);

            QuizDto afterDto = new QuizDto(quizId, testUser.getId(), null, "Updated Title", "Updated Desc",
                    Visibility.PRIVATE, null, QuizStatus.PENDING_REVIEW, 10, false, false, 5, List.of(), null, null);

            when(quizRepository.findByIdWithTags(quizId)).thenReturn(Optional.of(quiz));
            // Mock updateEntity - keep status PUBLISHED
            doAnswer(invocation -> {
                Quiz q = invocation.getArgument(0);
                q.setStatus(QuizStatus.PUBLISHED);  // Stays PUBLISHED after update
                return null;
            }).when(quizMapper).updateEntity(eq(quiz), eq(request), any(), any());
            
            // After updateEntity, toDto is called (line 262)
            when(quizMapper.toDto(quiz)).thenReturn(afterDto).thenReturn(afterDto);
            // calculateContentHash returns newHash (different from oldHash)
            when(quizHashCalculator.calculateContentHash(afterDto)).thenReturn("newHash");
            when(quizHashCalculator.calculatePresentationHash(afterDto)).thenReturn("presentHash");
            when(quizRepository.save(quiz)).thenReturn(quiz);

            // When
            QuizDto result = quizService.updateQuiz("testuser", quizId, request);

            // Then - Lines 272-276 should be covered
            assertThat(result).isNotNull();
            // Verify the hash calculations were called
            verify(quizHashCalculator).calculateContentHash(afterDto);
            verify(quizHashCalculator).calculatePresentationHash(afterDto);
            // Verify status was changed to PENDING_REVIEW
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PENDING_REVIEW);
            // Verify review fields were cleared
            assertThat(quiz.getReviewedAt()).isNull();
            assertThat(quiz.getReviewedBy()).isNull();
            assertThat(quiz.getRejectionReason()).isNull();
        }

    }

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
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest request = 
                mock(uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest.class);

            // Mock calculateTotalChunks to return 0 (no chunks)
            when(aiQuizGenerationService.calculateTotalChunks(eq(documentId), any()))
                .thenReturn(0);

            // When & Then - Line 436: throw when totalChunks <= 0
            assertThatThrownBy(() -> quizService.verifyDocumentChunks(documentId, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Document has no chunks available for quiz generation");
        }

        @Test
        @DisplayName("verifyDocumentChunks (text request) throws when no chunks - Line 450")
        void verifyDocumentChunks_textRequest_noChunks_throwsRuntimeException() {
            // Given
            UUID documentId = UUID.randomUUID();
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest request = 
                mock(uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest.class);

            // Mock calculateTotalChunks to return 0 (no chunks)
            when(aiQuizGenerationService.calculateTotalChunks(eq(documentId), any()))
                .thenReturn(0);

            // When & Then - Line 450: throw when totalChunks <= 0
            assertThatThrownBy(() -> quizService.verifyDocumentChunks(documentId, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Document has no chunks available for quiz generation");
        }

        @Test
        @DisplayName("processDocumentCompletely throws when file.getBytes() fails - Lines 423-424")
        void processDocumentCompletely_ioException_throwsRuntimeException() throws Exception {
            // Given
            org.springframework.web.multipart.MultipartFile mockFile = mock(org.springframework.web.multipart.MultipartFile.class);
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest request = 
                mock(uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest.class);

            // Mock file.getBytes() to throw IOException (line 415)
            when(mockFile.getBytes()).thenThrow(new java.io.IOException("Failed to read file"));

            // When & Then - Lines 423-424: catch IOException and wrap in RuntimeException
            assertThatThrownBy(() -> quizService.processDocumentCompletely("testuser", mockFile, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to read file bytes: Failed to read file");
        }

        @Test
        @DisplayName("generateQuizFromUpload generic Exception wrapped - Lines 375-377")
        void generateQuizFromUpload_genericException_wrapsInRuntimeException() throws Exception {
            // Given
            org.springframework.web.multipart.MultipartFile mockFile = mock(org.springframework.web.multipart.MultipartFile.class);
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest request = 
                mock(uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest.class);

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
        @DisplayName("generateQuizFromUpload InsufficientTokensException propagates - Lines 372-374")
        void generateQuizFromUpload_insufficientTokens_propagates() throws Exception {
            // Given - Use spy to mock startQuizGeneration
            QuizServiceImpl spyService = spy(quizService);
            
            org.springframework.web.multipart.MultipartFile mockFile = mock(org.springframework.web.multipart.MultipartFile.class);
            uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest request = 
                mock(uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest.class);
            
            UUID documentId = UUID.randomUUID();
            DocumentDto mockDoc = mock(DocumentDto.class);
            when(mockDoc.getId()).thenReturn(documentId);
            
            // Mock processDocumentCompletely to succeed
            doReturn(mockDoc).when(spyService).processDocumentCompletely(eq("testuser"), eq(mockFile), eq(request));
            // Mock verifyDocumentChunks to succeed
            doNothing().when(spyService).verifyDocumentChunks(eq(documentId), eq(request));

            // Mock startQuizGeneration to throw InsufficientTokensException
            uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException expectedException = 
                new uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException(
                    "Not enough tokens", 100L, 50L, 50L, java.time.LocalDateTime.now()
                );
            doThrow(expectedException).when(spyService).startQuizGeneration(eq("testuser"), any());

            // When & Then - Lines 372-374: catch and rethrow InsufficientTokensException
            assertThatThrownBy(() -> spyService.generateQuizFromUpload("testuser", mockFile, request))
                .isSameAs(expectedException);
        }

    }

}












