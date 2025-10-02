package uk.gegc.quizmaker.features.quiz.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.QuestionHandler;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for Quiz Flow Refactor: Owner Freedom, Public Safety
 * 
 * These tests validate that:
 * 1. Owners can publish their own quizzes when visibility is PRIVATE
 * 2. Owners cannot publish PUBLIC quizzes (requires moderation)
 * 3. Owners can unpublish their quizzes (set status to DRAFT)
 * 4. Owners can set visibility to PRIVATE
 * 5. Owners cannot set visibility to PUBLIC (requires moderation)
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Quiz Owner Private Publish Tests")
class QuizOwnerPrivatePublishTest {

    @Mock private QuizRepository quizRepository;
    @Mock private QuestionRepository questionRepository;
    @Mock private TagRepository tagRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private QuizMapper quizMapper;
    @Mock private UserRepository userRepository;
    @Mock private QuestionHandlerFactory questionHandlerFactory;
    @Mock private QuestionHandler questionHandler;
    @Mock private QuizGenerationJobRepository jobRepository;
    @Mock private QuizGenerationJobService jobService;
    @Mock private AiQuizGenerationService aiQuizGenerationService;
    @Mock private DocumentProcessingService documentProcessingService;
    @Mock private QuizHashCalculator quizHashCalculator;
    @Mock private BillingService billingService;
    @Mock private InternalBillingService internalBillingService;
    @Mock private EstimationService estimationService;
    @Mock private FeatureFlags featureFlags;
    @Mock private AppPermissionEvaluator appPermissionEvaluator;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private QuizServiceImpl quizService;

    private User ownerUser;
    private User moderatorUser;
    private Quiz testQuiz;
    private QuizDto testQuizDto;

    @BeforeEach
    void setUp() {
        // Given: Create an owner user
        ownerUser = createOwnerUser();
        moderatorUser = createModeratorUser();
        testQuiz = createValidQuiz(ownerUser);
        testQuizDto = createQuizDto(testQuiz.getId(), QuizStatus.PUBLISHED, Visibility.PRIVATE);
        
        setupUserRepositoryMock();
        setupPermissionEvaluatorMock();
    }

    // =============== setStatus Tests ===============

    @Test
    @DisplayName("setStatus: when owner sets PUBLISHED on PRIVATE quiz then succeeds")
    void setStatus_ownerPublishPrivate_succeeds() {
        // Given
        testQuiz.setVisibility(Visibility.PRIVATE);
        testQuiz.setStatus(QuizStatus.DRAFT);
        
        when(quizRepository.findByIdWithQuestions(testQuiz.getId()))
                .thenReturn(Optional.of(testQuiz));
        when(quizRepository.save(any(Quiz.class))).thenReturn(testQuiz);
        when(quizMapper.toDto(any(Quiz.class))).thenReturn(testQuizDto);
        // Mock question validation
        when(questionHandlerFactory.getHandler(any(QuestionType.class))).thenReturn(questionHandler);

        // When
        QuizDto result = quizService.setStatus(ownerUser.getUsername(), testQuiz.getId(), QuizStatus.PUBLISHED);

        // Then
        assertThat(result).isNotNull();
        assertThat(testQuiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        verify(quizRepository).save(testQuiz);
    }

    @Test
    @DisplayName("setStatus: when owner sets PUBLISHED on PUBLIC quiz then forbidden")
    void setStatus_ownerPublishPublic_forbidden() {
        // Given
        testQuiz.setVisibility(Visibility.PUBLIC);
        testQuiz.setStatus(QuizStatus.DRAFT);
        
        when(quizRepository.findByIdWithQuestions(testQuiz.getId()))
                .thenReturn(Optional.of(testQuiz));

        // When/Then
        assertThatThrownBy(() -> quizService.setStatus(ownerUser.getUsername(), testQuiz.getId(), QuizStatus.PUBLISHED))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only moderators can publish PUBLIC quizzes");
        
        verify(quizRepository, never()).save(any(Quiz.class));
    }

    @Test
    @DisplayName("setStatus: when owner sets DRAFT from PUBLISHED then succeeds (unpublish)")
    void setStatus_ownerUnpublish_succeeds() {
        // Given
        testQuiz.setVisibility(Visibility.PUBLIC);
        testQuiz.setStatus(QuizStatus.PUBLISHED);
        QuizDto draftDto = createQuizDto(testQuiz.getId(), QuizStatus.DRAFT, Visibility.PUBLIC);
        
        when(quizRepository.findByIdWithQuestions(testQuiz.getId()))
                .thenReturn(Optional.of(testQuiz));
        when(quizRepository.save(any(Quiz.class))).thenReturn(testQuiz);
        when(quizMapper.toDto(any(Quiz.class))).thenReturn(draftDto);

        // When
        QuizDto result = quizService.setStatus(ownerUser.getUsername(), testQuiz.getId(), QuizStatus.DRAFT);

        // Then
        assertThat(result).isNotNull();
        assertThat(testQuiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
        verify(quizRepository).save(testQuiz);
    }

    @Test
    @DisplayName("setStatus: when moderator sets PUBLISHED on PUBLIC quiz then succeeds")
    void setStatus_moderatorPublishPublic_succeeds() {
        // Given
        testQuiz.setVisibility(Visibility.PUBLIC);
        testQuiz.setStatus(QuizStatus.DRAFT);
        QuizDto publicDto = createQuizDto(testQuiz.getId(), QuizStatus.PUBLISHED, Visibility.PUBLIC);
        
        when(quizRepository.findByIdWithQuestions(testQuiz.getId()))
                .thenReturn(Optional.of(testQuiz));
        when(quizRepository.save(any(Quiz.class))).thenReturn(testQuiz);
        when(quizMapper.toDto(any(Quiz.class))).thenReturn(publicDto);
        // Mock question validation
        when(questionHandlerFactory.getHandler(any(QuestionType.class))).thenReturn(questionHandler);

        // When
        QuizDto result = quizService.setStatus(moderatorUser.getUsername(), testQuiz.getId(), QuizStatus.PUBLISHED);

        // Then
        assertThat(result).isNotNull();
        assertThat(testQuiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        verify(quizRepository).save(testQuiz);
    }

    // =============== setVisibility Tests ===============

    @Test
    @DisplayName("setVisibility: when owner sets PRIVATE then succeeds")
    void setVisibility_ownerSetPrivate_succeeds() {
        // Given
        testQuiz.setVisibility(Visibility.PUBLIC);
        QuizDto privateDto = createQuizDto(testQuiz.getId(), QuizStatus.PUBLISHED, Visibility.PRIVATE);
        
        when(quizRepository.findById(testQuiz.getId()))
                .thenReturn(Optional.of(testQuiz));
        when(quizRepository.save(any(Quiz.class))).thenReturn(testQuiz);
        when(quizMapper.toDto(any(Quiz.class))).thenReturn(privateDto);

        // When
        QuizDto result = quizService.setVisibility(ownerUser.getUsername(), testQuiz.getId(), Visibility.PRIVATE);

        // Then
        assertThat(result).isNotNull();
        assertThat(testQuiz.getVisibility()).isEqualTo(Visibility.PRIVATE);
        verify(quizRepository).save(testQuiz);
    }

    @Test
    @DisplayName("setVisibility: when owner sets PUBLIC then forbidden")
    void setVisibility_ownerSetPublic_forbidden() {
        // Given
        testQuiz.setVisibility(Visibility.PRIVATE);
        
        when(quizRepository.findById(testQuiz.getId()))
                .thenReturn(Optional.of(testQuiz));

        // When/Then
        assertThatThrownBy(() -> quizService.setVisibility(ownerUser.getUsername(), testQuiz.getId(), Visibility.PUBLIC))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only moderators can set quiz visibility to PUBLIC");
        
        verify(quizRepository, never()).save(any(Quiz.class));
    }

    @Test
    @DisplayName("setVisibility: when moderator sets PUBLIC then succeeds")
    void setVisibility_moderatorSetPublic_succeeds() {
        // Given
        testQuiz.setVisibility(Visibility.PRIVATE);
        QuizDto publicDto = createQuizDto(testQuiz.getId(), QuizStatus.PUBLISHED, Visibility.PUBLIC);
        
        when(quizRepository.findById(testQuiz.getId()))
                .thenReturn(Optional.of(testQuiz));
        when(quizRepository.save(any(Quiz.class))).thenReturn(testQuiz);
        when(quizMapper.toDto(any(Quiz.class))).thenReturn(publicDto);

        // When
        QuizDto result = quizService.setVisibility(moderatorUser.getUsername(), testQuiz.getId(), Visibility.PUBLIC);

        // Then
        assertThat(result).isNotNull();
        assertThat(testQuiz.getVisibility()).isEqualTo(Visibility.PUBLIC);
        verify(quizRepository).save(testQuiz);
    }

    // =============== Helper Methods ===============

    private User createOwnerUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("owner");
        user.setEmail("owner@example.com");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        
        Role role = new Role();
        role.setRoleId(1L);
        role.setRoleName("ROLE_USER");
        role.setDescription("Regular user role");
        
        user.setRoles(new HashSet<>(Set.of(role)));
        return user;
    }

    private User createModeratorUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("moderator");
        user.setEmail("moderator@example.com");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        
        Role role = new Role();
        role.setRoleId(2L);
        role.setRoleName("ROLE_MODERATOR");
        role.setDescription("Moderator role");
        
        user.setRoles(new HashSet<>(Set.of(role)));
        return user;
    }

    private Quiz createValidQuiz(User creator) {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz");
        quiz.setDescription("Test Description");
        quiz.setCreator(creator);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setEstimatedTime(10);
        
        // Add a valid question
        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setQuestionText("What is 2+2?");
        question.setType(QuestionType.MCQ_SINGLE);
        question.setDifficulty(Difficulty.EASY);
        question.setContent("{\"options\":[{\"id\":\"a\",\"text\":\"4\",\"isCorrect\":true}]}");
        
        quiz.setQuestions(new HashSet<>(Set.of(question)));
        return quiz;
    }
    
    private QuizDto createQuizDto(UUID id, QuizStatus status, Visibility visibility) {
        return new QuizDto(
                id,
                UUID.randomUUID(), // creatorId
                UUID.randomUUID(), // categoryId
                "Test Quiz",
                "Test Description",
                visibility,
                Difficulty.MEDIUM,
                status,
                10, // estimatedTime
                false, // isRepetitionEnabled
                false, // timerEnabled
                null, // timerDuration
                java.util.List.of(), // tagIds
                java.time.Instant.now(), // createdAt
                java.time.Instant.now() // updatedAt
        );
    }

    private void setupUserRepositoryMock() {
        when(userRepository.findByUsername(ownerUser.getUsername()))
                .thenReturn(Optional.of(ownerUser));
        when(userRepository.findByEmail(ownerUser.getEmail()))
                .thenReturn(Optional.of(ownerUser));
        
        when(userRepository.findByUsername(moderatorUser.getUsername()))
                .thenReturn(Optional.of(moderatorUser));
        when(userRepository.findByEmail(moderatorUser.getEmail()))
                .thenReturn(Optional.of(moderatorUser));
    }

    private void setupPermissionEvaluatorMock() {
        // Owner does not have moderation permissions
        when(appPermissionEvaluator.hasPermission(eq(ownerUser), eq(PermissionName.QUIZ_MODERATE)))
                .thenReturn(false);
        when(appPermissionEvaluator.hasPermission(eq(ownerUser), eq(PermissionName.QUIZ_ADMIN)))
                .thenReturn(false);
        
        // Moderator has moderation permissions
        when(appPermissionEvaluator.hasPermission(eq(moderatorUser), eq(PermissionName.QUIZ_MODERATE)))
                .thenReturn(true);
        when(appPermissionEvaluator.hasPermission(eq(moderatorUser), eq(PermissionName.QUIZ_ADMIN)))
                .thenReturn(false);
    }
}

