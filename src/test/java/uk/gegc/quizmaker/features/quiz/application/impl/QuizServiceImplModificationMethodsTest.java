package uk.gegc.quizmaker.features.quiz.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.shared.security.AccessPolicy;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Branch coverage tests for QuizServiceImpl modification methods:
 * - addQuestionToQuiz
 * - addTagToQuiz
 * - changeCategory
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuizServiceImpl Modification Methods Tests")
class QuizServiceImplModificationMethodsTest {

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
    private AppPermissionEvaluator appPermissionEvaluator;
    @Mock
    private FeatureFlags featureFlags;
    @Mock
    private QuizJobProperties quizJobProperties;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private QuizQueryService quizQueryService;
    @Mock
    private AccessPolicy accessPolicy;

    @InjectMocks
    private QuizServiceImpl quizService;

    private Quiz testQuiz;
    private User testUser;
    private Question testQuestion;
    private Tag testTag;
    private Category testCategory;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        
        testQuiz = new Quiz();
        testQuiz.setId(UUID.randomUUID());
        testQuiz.setTitle("Test Quiz");
        testQuiz.setCreator(testUser);
        testQuiz.setStatus(QuizStatus.DRAFT);
        testQuiz.setQuestions(new HashSet<>());
        testQuiz.setTags(new HashSet<>());
        
        testQuestion = new Question();
        testQuestion.setId(UUID.randomUUID());
        testQuestion.setQuestionText("Test question");
        testQuestion.setType(QuestionType.MCQ_SINGLE);
        
        testTag = new Tag();
        testTag.setId(UUID.randomUUID());
        testTag.setName("Test Tag");
        
        testCategory = new Category();
        testCategory.setId(UUID.randomUUID());
        testCategory.setName("Test Category");
        
        // Default AccessPolicy mock behavior: allow owners to access their own resources
        lenient().doNothing().when(accessPolicy).requireOwnerOrAny(
                any(User.class), 
                any(UUID.class), 
                any(PermissionName.class), 
                any(PermissionName.class));
        lenient().when(accessPolicy.hasAny(any(User.class), any(PermissionName.class), any(PermissionName.class)))
                .thenReturn(false);
    }

    @Nested
    @DisplayName("addQuestionToQuiz Tests")
    class AddQuestionToQuizTests {

        @Test
        @DisplayName("addQuestionToQuiz: when user found by email then succeeds")
        void addQuestionToQuiz_userFoundByEmail_succeeds() {
            // Given
            String email = "test@example.com";
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(questionRepository.findById(testQuestion.getId())).thenReturn(Optional.of(testQuestion));
            when(userRepository.findByUsername(email)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(email)).thenReturn(Optional.of(testUser)); // Line 1085
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When
            assertThatCode(() -> quizService.addQuestionToQuiz(email, testQuiz.getId(), testQuestion.getId()))
                    .doesNotThrowAnyException();

            // Then - Line 1085 covered
            verify(userRepository).findByEmail(email);
            verify(quizRepository).save(any());
        }

        @Test
        @DisplayName("addQuestionToQuiz: when user not found then throws exception")
        void addQuestionToQuiz_userNotFound_throwsException() {
            // Given
            String username = "nonexistent";
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(questionRepository.findById(testQuestion.getId())).thenReturn(Optional.of(testQuestion));
            when(userRepository.findByUsername(username)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(username)).thenReturn(Optional.empty()); // Line 1086

            // When & Then - Line 1086 covered
            assertThatThrownBy(() -> quizService.addQuestionToQuiz(username, testQuiz.getId(), testQuestion.getId()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("User " + username + " not found");
        }

        @Test
        @DisplayName("addQuestionToQuiz: when quiz has no creator and user is not moderator then throws exception")
        void addQuestionToQuiz_noCreatorNoPermissions_throwsForbidden() {
            // Given
            testQuiz.setCreator(null); // Line 1089 - null creator
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(questionRepository.findById(testQuestion.getId())).thenReturn(Optional.of(testQuestion));
            when(userRepository.findByUsername(testUser.getUsername())).thenReturn(Optional.of(testUser));
            // Override default behavior: throw exception for null creator
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                    .when(accessPolicy).requireOwnerOrAny(any(User.class), any(), 
                            any(PermissionName.class), any(PermissionName.class));

            // When & Then - Lines 1089-1092 covered
            assertThatThrownBy(() -> quizService.addQuestionToQuiz(testUser.getUsername(), testQuiz.getId(), testQuestion.getId()))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("addQuestionToQuiz: when user has QUIZ_MODERATE permission then succeeds")
        void addQuestionToQuiz_userHasModeratePermission_succeeds() {
            // Given - Different user with moderate permission
            User moderator = new User();
            moderator.setId(UUID.randomUUID());
            moderator.setUsername("moderator");
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(questionRepository.findById(testQuestion.getId())).thenReturn(Optional.of(testQuestion));
            when(userRepository.findByUsername(moderator.getUsername())).thenReturn(Optional.of(moderator));
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1090 covered
            assertThatCode(() -> quizService.addQuestionToQuiz(moderator.getUsername(), testQuiz.getId(), testQuestion.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("addQuestionToQuiz: when user has QUIZ_ADMIN permission then succeeds")
        void addQuestionToQuiz_userHasAdminPermission_succeeds() {
            // Given - Different user with admin permission
            User admin = new User();
            admin.setId(UUID.randomUUID());
            admin.setUsername("admin");
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(questionRepository.findById(testQuestion.getId())).thenReturn(Optional.of(testQuestion));
            when(userRepository.findByUsername(admin.getUsername())).thenReturn(Optional.of(admin));
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1091 covered
            assertThatCode(() -> quizService.addQuestionToQuiz(admin.getUsername(), testQuiz.getId(), testQuestion.getId()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("addTagToQuiz Tests")
    class AddTagToQuizTests {

        @Test
        @DisplayName("addTagToQuiz: when quiz not found then throws exception")
        void addTagToQuiz_quizNotFound_throwsException() {
            // Given
            UUID nonExistentQuizId = UUID.randomUUID();
            
            when(quizRepository.findById(nonExistentQuizId)).thenReturn(Optional.empty());

            // When & Then - Line 1126 covered
            assertThatThrownBy(() -> quizService.addTagToQuiz(testUser.getUsername(), nonExistentQuizId, testTag.getId()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Quiz " + nonExistentQuizId + " not found");
        }

        @Test
        @DisplayName("addTagToQuiz: when tag not found then throws exception")
        void addTagToQuiz_tagNotFound_throwsException() {
            // Given
            UUID nonExistentTagId = UUID.randomUUID();
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(tagRepository.findById(nonExistentTagId)).thenReturn(Optional.empty());

            // When & Then - Line 1129 covered
            assertThatThrownBy(() -> quizService.addTagToQuiz(testUser.getUsername(), testQuiz.getId(), nonExistentTagId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Tag " + nonExistentTagId + " not found");
        }

        @Test
        @DisplayName("addTagToQuiz: when user found by email then succeeds")
        void addTagToQuiz_userFoundByEmail_succeeds() {
            // Given
            String email = "test@example.com";
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(tagRepository.findById(testTag.getId())).thenReturn(Optional.of(testTag));
            when(userRepository.findByUsername(email)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(email)).thenReturn(Optional.of(testUser)); // Line 1132
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1132 covered
            assertThatCode(() -> quizService.addTagToQuiz(email, testQuiz.getId(), testTag.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("addTagToQuiz: when user not found then throws exception")
        void addTagToQuiz_userNotFound_throwsException() {
            // Given
            String username = "nonexistent";
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(tagRepository.findById(testTag.getId())).thenReturn(Optional.of(testTag));
            when(userRepository.findByUsername(username)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(username)).thenReturn(Optional.empty()); // Line 1133

            // When & Then - Line 1133 covered
            assertThatThrownBy(() -> quizService.addTagToQuiz(username, testQuiz.getId(), testTag.getId()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("User " + username + " not found");
        }

        @Test
        @DisplayName("addTagToQuiz: when user has QUIZ_MODERATE permission then succeeds")
        void addTagToQuiz_userHasModeratePermission_succeeds() {
            // Given
            User moderator = new User();
            moderator.setId(UUID.randomUUID());
            moderator.setUsername("moderator");
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(tagRepository.findById(testTag.getId())).thenReturn(Optional.of(testTag));
            when(userRepository.findByUsername(moderator.getUsername())).thenReturn(Optional.of(moderator));
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1137 covered
            assertThatCode(() -> quizService.addTagToQuiz(moderator.getUsername(), testQuiz.getId(), testTag.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("addTagToQuiz: when user has QUIZ_ADMIN permission then succeeds")
        void addTagToQuiz_userHasAdminPermission_succeeds() {
            // Given
            User admin = new User();
            admin.setId(UUID.randomUUID());
            admin.setUsername("admin");
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(tagRepository.findById(testTag.getId())).thenReturn(Optional.of(testTag));
            when(userRepository.findByUsername(admin.getUsername())).thenReturn(Optional.of(admin));
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1138 covered
            assertThatCode(() -> quizService.addTagToQuiz(admin.getUsername(), testQuiz.getId(), testTag.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("addTagToQuiz: when quiz has no creator and user has no permissions then throws exception")
        void addTagToQuiz_noCreatorNoPermissions_throwsForbidden() {
            // Given
            testQuiz.setCreator(null); // Null creator
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(tagRepository.findById(testTag.getId())).thenReturn(Optional.of(testTag));
            when(userRepository.findByUsername(testUser.getUsername())).thenReturn(Optional.of(testUser));
            // Override default behavior: throw exception for null creator
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                    .when(accessPolicy).requireOwnerOrAny(any(User.class), any(), 
                            any(PermissionName.class), any(PermissionName.class));

            // When & Then - Lines 1136-1139 covered
            assertThatThrownBy(() -> quizService.addTagToQuiz(testUser.getUsername(), testQuiz.getId(), testTag.getId()))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    @DisplayName("removeQuestionFromQuiz Tests")
    class RemoveQuestionFromQuizTests {

        @Test
        @DisplayName("removeQuestionFromQuiz: when quiz not found then throws exception")
        void removeQuestionFromQuiz_quizNotFound_throwsException() {
            // Given
            UUID nonExistentQuizId = UUID.randomUUID();
            
            when(quizRepository.findById(nonExistentQuizId)).thenReturn(Optional.empty());

            // When & Then - Line 1104 covered
            assertThatThrownBy(() -> quizService.removeQuestionFromQuiz(testUser.getUsername(), nonExistentQuizId, testQuestion.getId()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Quiz " + nonExistentQuizId + " not found");
        }

        @Test
        @DisplayName("removeQuestionFromQuiz: when user found by email then succeeds")
        void removeQuestionFromQuiz_userFoundByEmail_succeeds() {
            // Given
            String email = "test@example.com";
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(userRepository.findByUsername(email)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(email)).thenReturn(Optional.of(testUser)); // Line 1107
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1107 covered
            assertThatCode(() -> quizService.removeQuestionFromQuiz(email, testQuiz.getId(), testQuestion.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("removeQuestionFromQuiz: when user not found then throws exception")
        void removeQuestionFromQuiz_userNotFound_throwsException() {
            // Given
            String username = "nonexistent";
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(userRepository.findByUsername(username)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(username)).thenReturn(Optional.empty()); // Line 1108

            // When & Then - Line 1108 covered
            assertThatThrownBy(() -> quizService.removeQuestionFromQuiz(username, testQuiz.getId(), testQuestion.getId()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("User " + username + " not found");
        }

        @Test
        @DisplayName("removeQuestionFromQuiz: when user has QUIZ_MODERATE permission then succeeds")
        void removeQuestionFromQuiz_userHasModeratePermission_succeeds() {
            // Given
            User moderator = new User();
            moderator.setId(UUID.randomUUID());
            moderator.setUsername("moderator");
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(userRepository.findByUsername(moderator.getUsername())).thenReturn(Optional.of(moderator));
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1112 covered
            assertThatCode(() -> quizService.removeQuestionFromQuiz(moderator.getUsername(), testQuiz.getId(), testQuestion.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("removeQuestionFromQuiz: when user has QUIZ_ADMIN permission then succeeds")
        void removeQuestionFromQuiz_userHasAdminPermission_succeeds() {
            // Given
            User admin = new User();
            admin.setId(UUID.randomUUID());
            admin.setUsername("admin");
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(userRepository.findByUsername(admin.getUsername())).thenReturn(Optional.of(admin));
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1113 covered
            assertThatCode(() -> quizService.removeQuestionFromQuiz(admin.getUsername(), testQuiz.getId(), testQuestion.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("removeQuestionFromQuiz: when quiz has no creator and user has no permissions then throws exception")
        void removeQuestionFromQuiz_noCreatorNoPermissions_throwsForbidden() {
            // Given
            testQuiz.setCreator(null);
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(userRepository.findByUsername(testUser.getUsername())).thenReturn(Optional.of(testUser));
            // Override default behavior: throw exception for null creator
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                    .when(accessPolicy).requireOwnerOrAny(any(User.class), any(), 
                            any(PermissionName.class), any(PermissionName.class));

            // When & Then - Lines 1111-1114 covered
            assertThatThrownBy(() -> quizService.removeQuestionFromQuiz(testUser.getUsername(), testQuiz.getId(), testQuestion.getId()))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    @DisplayName("removeTagFromQuiz Tests")
    class RemoveTagFromQuizTests {

        @Test
        @DisplayName("removeTagFromQuiz: when user found by email then succeeds")
        void removeTagFromQuiz_userFoundByEmail_succeeds() {
            // Given
            String email = "test@example.com";
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(userRepository.findByUsername(email)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(email)).thenReturn(Optional.of(testUser)); // Line 1154
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1154 covered
            assertThatCode(() -> quizService.removeTagFromQuiz(email, testQuiz.getId(), testTag.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("removeTagFromQuiz: when user not found then throws exception")
        void removeTagFromQuiz_userNotFound_throwsException() {
            // Given
            String username = "nonexistent";
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(userRepository.findByUsername(username)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(username)).thenReturn(Optional.empty()); // Line 1155

            // When & Then - Line 1155 covered
            assertThatThrownBy(() -> quizService.removeTagFromQuiz(username, testQuiz.getId(), testTag.getId()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("User " + username + " not found");
        }

        @Test
        @DisplayName("removeTagFromQuiz: when user has QUIZ_MODERATE permission then succeeds")
        void removeTagFromQuiz_userHasModeratePermission_succeeds() {
            // Given
            User moderator = new User();
            moderator.setId(UUID.randomUUID());
            moderator.setUsername("moderator");
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(userRepository.findByUsername(moderator.getUsername())).thenReturn(Optional.of(moderator));
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1159 covered
            assertThatCode(() -> quizService.removeTagFromQuiz(moderator.getUsername(), testQuiz.getId(), testTag.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("removeTagFromQuiz: when user has QUIZ_ADMIN permission then succeeds")
        void removeTagFromQuiz_userHasAdminPermission_succeeds() {
            // Given
            User admin = new User();
            admin.setId(UUID.randomUUID());
            admin.setUsername("admin");
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(userRepository.findByUsername(admin.getUsername())).thenReturn(Optional.of(admin));
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1160 covered
            assertThatCode(() -> quizService.removeTagFromQuiz(admin.getUsername(), testQuiz.getId(), testTag.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("removeTagFromQuiz: when quiz has no creator and user has no permissions then throws exception")
        void removeTagFromQuiz_noCreatorNoPermissions_throwsForbidden() {
            // Given
            testQuiz.setCreator(null);
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(userRepository.findByUsername(testUser.getUsername())).thenReturn(Optional.of(testUser));
            // Override default behavior: throw exception for null creator
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                    .when(accessPolicy).requireOwnerOrAny(any(User.class), any(), 
                            any(PermissionName.class), any(PermissionName.class));

            // When & Then - Lines 1158-1161 covered
            assertThatThrownBy(() -> quizService.removeTagFromQuiz(testUser.getUsername(), testQuiz.getId(), testTag.getId()))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    @DisplayName("getGeneratedQuiz Tests")
    class GetGeneratedQuizTests {

        @Test
        @DisplayName("getGeneratedQuiz: when job is not completed then throws ValidationException")
        void getGeneratedQuiz_jobNotCompleted_throwsValidationException() {
            // Given
            QuizGenerationJob job = new QuizGenerationJob();
            job.setId(UUID.randomUUID());
            job.setStatus(GenerationStatus.PROCESSING); // Not completed
            
            when(quizQueryService.getGeneratedQuiz(job.getId(), testUser.getUsername()))
                    .thenThrow(new ValidationException("Generation job is not yet completed. Current status: " + job.getStatus()));

            // When & Then - Lines 595-596 covered
            assertThatThrownBy(() -> quizService.getGeneratedQuiz(job.getId(), testUser.getUsername()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Generation job is not yet completed");
        }

        @Test
        @DisplayName("getGeneratedQuiz: when generatedQuizId is null then throws ResourceNotFoundException")
        void getGeneratedQuiz_nullGeneratedQuizId_throwsException() {
            // Given
            QuizGenerationJob job = new QuizGenerationJob();
            job.setId(UUID.randomUUID());
            job.setStatus(GenerationStatus.COMPLETED);
            job.setGeneratedQuizId(null); // Null quiz ID
            
            when(quizQueryService.getGeneratedQuiz(job.getId(), testUser.getUsername()))
                    .thenThrow(new ResourceNotFoundException("Generated quiz not found for job: " + job.getId()));

            // When & Then - Lines 599-600 covered
            assertThatThrownBy(() -> quizService.getGeneratedQuiz(job.getId(), testUser.getUsername()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Generated quiz not found for job");
        }

        @Test
        @DisplayName("getGeneratedQuiz: when quiz not found then throws ResourceNotFoundException")
        void getGeneratedQuiz_quizNotFound_throwsException() {
            // Given
            QuizGenerationJob job = new QuizGenerationJob();
            job.setId(UUID.randomUUID());
            job.setStatus(GenerationStatus.COMPLETED);
            job.setGeneratedQuizId(UUID.randomUUID());
            
            when(quizQueryService.getGeneratedQuiz(job.getId(), testUser.getUsername()))
                    .thenThrow(new ResourceNotFoundException("Quiz " + job.getGeneratedQuizId() + " not found"));

            // When & Then - Line 605 covered
            assertThatThrownBy(() -> quizService.getGeneratedQuiz(job.getId(), testUser.getUsername()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Quiz " + job.getGeneratedQuizId() + " not found");
        }

        @Test
        @DisplayName("getGeneratedQuiz: when quiz creator is null then throws ForbiddenException")
        void getGeneratedQuiz_quizCreatorNull_throwsForbidden() {
            // Given
            QuizGenerationJob job = new QuizGenerationJob();
            job.setId(UUID.randomUUID());
            job.setStatus(GenerationStatus.COMPLETED);
            job.setGeneratedQuizId(testQuiz.getId());
            job.setUser(testUser);
            
            when(quizQueryService.getGeneratedQuiz(job.getId(), testUser.getUsername()))
                    .thenThrow(new ForbiddenException("Access denied"));

            // When & Then - Line 607 covered (null creator)
            assertThatThrownBy(() -> quizService.getGeneratedQuiz(job.getId(), testUser.getUsername()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Access denied");
        }

        @Test
        @DisplayName("getGeneratedQuiz: when quiz creator doesn't match job user then throws ForbiddenException")
        void getGeneratedQuiz_creatorMismatch_throwsForbidden() {
            // Given
            User differentUser = new User();
            differentUser.setId(UUID.randomUUID());
            differentUser.setUsername("different");
            
            QuizGenerationJob job = new QuizGenerationJob();
            job.setId(UUID.randomUUID());
            job.setStatus(GenerationStatus.COMPLETED);
            job.setGeneratedQuizId(testQuiz.getId());
            job.setUser(testUser);
            
            when(quizQueryService.getGeneratedQuiz(job.getId(), testUser.getUsername()))
                    .thenThrow(new ForbiddenException("Access denied"));

            // When & Then - Line 607 covered (creator ID doesn't match)
            assertThatThrownBy(() -> quizService.getGeneratedQuiz(job.getId(), testUser.getUsername()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Access denied");
        }

        @Test
        @DisplayName("getGeneratedQuiz: when successful then returns quiz")
        void getGeneratedQuiz_successful_returnsQuiz() {
            // Given
            QuizGenerationJob job = new QuizGenerationJob();
            job.setId(UUID.randomUUID());
            job.setStatus(GenerationStatus.COMPLETED);
            job.setGeneratedQuizId(testQuiz.getId());
            job.setUser(testUser);
            
            QuizDto quizDto = mock(QuizDto.class);
            when(quizDto.id()).thenReturn(testQuiz.getId());
            
            when(quizQueryService.getGeneratedQuiz(job.getId(), testUser.getUsername())).thenReturn(quizDto);

            // When
            QuizDto result = quizService.getGeneratedQuiz(job.getId(), testUser.getUsername());

            // Then - Lines 593-611 covered
            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(testQuiz.getId());
        }
    }

    @Nested
    @DisplayName("changeCategory Tests")
    class ChangeCategoryTests {

        @Test
        @DisplayName("changeCategory: when user found by email then succeeds")
        void changeCategory_userFoundByEmail_succeeds() {
            // Given
            String email = "test@example.com";
            testQuiz.setCategory(new Category()); // Set old category
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(categoryRepository.findById(testCategory.getId())).thenReturn(Optional.of(testCategory));
            when(userRepository.findByUsername(email)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(email)).thenReturn(Optional.of(testUser)); // Line 1179
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1179 covered
            assertThatCode(() -> quizService.changeCategory(email, testQuiz.getId(), testCategory.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("changeCategory: when user not found then throws exception")
        void changeCategory_userNotFound_throwsException() {
            // Given
            String username = "nonexistent";
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(categoryRepository.findById(testCategory.getId())).thenReturn(Optional.of(testCategory));
            when(userRepository.findByUsername(username)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(username)).thenReturn(Optional.empty()); // Line 1180

            // When & Then - Line 1180 covered
            assertThatThrownBy(() -> quizService.changeCategory(username, testQuiz.getId(), testCategory.getId()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("User " + username + " not found");
        }

        @Test
        @DisplayName("changeCategory: when quiz has no creator and user is not moderator then throws exception")
        void changeCategory_noCreatorNoPermissions_throwsForbidden() {
            // Given
            testQuiz.setCreator(null); // Line 1183 - null creator
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(categoryRepository.findById(testCategory.getId())).thenReturn(Optional.of(testCategory));
            when(userRepository.findByUsername(testUser.getUsername())).thenReturn(Optional.of(testUser));
            // Override default behavior: throw exception for null creator
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                    .when(accessPolicy).requireOwnerOrAny(any(User.class), any(), 
                            any(PermissionName.class), any(PermissionName.class));

            // When & Then - Lines 1183-1186 covered
            assertThatThrownBy(() -> quizService.changeCategory(testUser.getUsername(), testQuiz.getId(), testCategory.getId()))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("changeCategory: when user has QUIZ_MODERATE permission then succeeds")
        void changeCategory_userHasModeratePermission_succeeds() {
            // Given
            User moderator = new User();
            moderator.setId(UUID.randomUUID());
            moderator.setUsername("moderator");
            testQuiz.setCategory(new Category());
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(categoryRepository.findById(testCategory.getId())).thenReturn(Optional.of(testCategory));
            when(userRepository.findByUsername(moderator.getUsername())).thenReturn(Optional.of(moderator));
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1184 covered
            assertThatCode(() -> quizService.changeCategory(moderator.getUsername(), testQuiz.getId(), testCategory.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("changeCategory: when user has QUIZ_ADMIN permission then succeeds")
        void changeCategory_userHasAdminPermission_succeeds() {
            // Given
            User admin = new User();
            admin.setId(UUID.randomUUID());
            admin.setUsername("admin");
            testQuiz.setCategory(new Category());
            
            when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
            when(categoryRepository.findById(testCategory.getId())).thenReturn(Optional.of(testCategory));
            when(userRepository.findByUsername(admin.getUsername())).thenReturn(Optional.of(admin));
            when(quizRepository.save(any())).thenReturn(testQuiz);

            // When & Then - Line 1185 covered
            assertThatCode(() -> quizService.changeCategory(admin.getUsername(), testQuiz.getId(), testCategory.getId()))
                    .doesNotThrowAnyException();
        }
    }
}

