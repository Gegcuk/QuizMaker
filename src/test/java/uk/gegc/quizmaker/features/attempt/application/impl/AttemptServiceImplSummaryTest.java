package uk.gegc.quizmaker.features.attempt.application.impl;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptSummaryDto;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.attempt.infra.mapping.AttemptMapper;
import uk.gegc.quizmaker.features.question.application.CorrectAnswerExtractor;
import uk.gegc.quizmaker.features.question.application.SafeQuestionContentBuilder;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.repository.AnswerRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.mapping.AnswerMapper;
import uk.gegc.quizmaker.features.question.infra.mapping.SafeQuestionMapper;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.ShareLinkRepository;
import uk.gegc.quizmaker.features.result.application.QuizAnalyticsService;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gegc.quizmaker.features.attempt.application.ScoringService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("AttemptServiceImpl Summary Endpoint Unit Tests")
class AttemptServiceImplSummaryTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private QuizRepository quizRepository;
    @Mock
    private AttemptRepository attemptRepository;
    @Mock
    private AttemptMapper attemptMapper;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private QuestionHandlerFactory handlerFactory;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private AnswerMapper answerMapper;
    @Mock
    private ScoringService scoringService;
    @Mock
    private SafeQuestionMapper safeQuestionMapper;
    @Mock
    private ShareLinkRepository shareLinkRepository;
    @Mock
    private AppPermissionEvaluator appPermissionEvaluator;
    @Mock
    private CorrectAnswerExtractor correctAnswerExtractor;
    @Mock
    private SafeQuestionContentBuilder safeQuestionContentBuilder;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private QuizAnalyticsService quizAnalyticsService;

    @InjectMocks
    private AttemptServiceImpl service;

    private User testUser;
    private User adminUser;
    private Quiz testQuiz;
    private Category testCategory;
    private Attempt testAttempt;
    private Pageable pageable;

    @BeforeEach
    void setUp() {
        pageable = PageRequest.of(0, 20);

        // Create test category
        testCategory = new Category();
        testCategory.setId(UUID.randomUUID());
        testCategory.setName("Test Category");

        // Create test user
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");

        // Create admin user
        adminUser = new User();
        adminUser.setId(UUID.randomUUID());
        adminUser.setUsername("adminuser");

        // Create test quiz
        testQuiz = new Quiz();
        testQuiz.setId(UUID.randomUUID());
        testQuiz.setTitle("Test Quiz");
        testQuiz.setCategory(testCategory);
        testQuiz.setVisibility(Visibility.PUBLIC);

        // Create test attempt
        testAttempt = new Attempt();
        testAttempt.setId(UUID.randomUUID());
        testAttempt.setUser(testUser);
        testAttempt.setQuiz(testQuiz);
        testAttempt.setStatus(AttemptStatus.COMPLETED);
        testAttempt.setMode(AttemptMode.ALL_AT_ONCE);
        testAttempt.setStartedAt(Instant.now().minusSeconds(300));
        testAttempt.setCompletedAt(Instant.now());
        testAttempt.setTotalScore(8.0);
        testAttempt.setAnswers(new ArrayList<>());
    }

    @Test
    @DisplayName("getAttemptsSummary: when current user then returns attempts")
    void getAttemptsSummary_currentUser_returnsAttempts() {
        // Given
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser"))
                .thenReturn(Optional.of(testUser));
        
        Page<Attempt> attemptPage = new PageImpl<>(List.of(testAttempt));
        when(attemptRepository.findAllWithQuizAndAnswersEager(null, testUser.getId(), null, pageable))
                .thenReturn(attemptPage);
        
        // Mock batch question count query
        List<Object[]> countResults = new ArrayList<>();
        countResults.add(new Object[]{testQuiz.getId(), 10L});
        when(questionRepository.countQuestionsForQuizzes(List.of(testQuiz.getId())))
                .thenReturn(countResults);
        when(attemptMapper.toQuizSummaryDto(any(), anyInt())).thenCallRealMethod();
        when(attemptMapper.toSummaryDto(any(), any(), any())).thenCallRealMethod();

        // When
        Page<AttemptSummaryDto> result = service.getAttemptsSummary(
                "testuser",
                pageable,
                null,
                null,
                null
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        AttemptSummaryDto summary = result.getContent().get(0);
        assertThat(summary.attemptId()).isEqualTo(testAttempt.getId());
        assertThat(summary.quiz()).isNotNull();
        assertThat(summary.quiz().id()).isEqualTo(testQuiz.getId());
        assertThat(summary.quiz().title()).isEqualTo("Test Quiz");
        assertThat(summary.quiz().questionCount()).isEqualTo(10);
        assertThat(summary.stats()).isNotNull();  // Completed attempt has stats
    }

    @Test
    @DisplayName("getAttemptsSummary: when other user without permission then throws AccessDeniedException")
    void getAttemptsSummary_otherUserNoPermission_throwsAccessDenied() {
        // Given
        UUID otherUserId = UUID.randomUUID();
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser"))
                .thenReturn(Optional.of(testUser));
        when(appPermissionEvaluator.hasPermission(testUser, PermissionName.ATTEMPT_READ_ALL))
                .thenReturn(false);
        when(appPermissionEvaluator.hasPermission(testUser, PermissionName.SYSTEM_ADMIN))
                .thenReturn(false);

        // When & Then
        assertThatThrownBy(() -> service.getAttemptsSummary(
                "testuser",
                pageable,
                null,
                otherUserId,
                null
        ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("You do not have permission to view other users' attempts");
    }

    @Test
    @DisplayName("getAttemptsSummary: when admin views other user then returns attempts")
    void getAttemptsSummary_adminViewsOtherUser_returnsAttempts() {
        // Given
        UUID otherUserId = UUID.randomUUID();
        when(userRepository.findByUsernameWithRolesAndPermissions("adminuser"))
                .thenReturn(Optional.of(adminUser));
        when(appPermissionEvaluator.hasPermission(adminUser, PermissionName.ATTEMPT_READ_ALL))
                .thenReturn(true);

        Page<Attempt> attemptPage = new PageImpl<>(List.of(testAttempt));
        when(attemptRepository.findAllWithQuizAndAnswersEager(null, otherUserId, null, pageable))
                .thenReturn(attemptPage);
        
        // Mock batch question count query
        List<Object[]> countResults = new ArrayList<>();
        countResults.add(new Object[]{testQuiz.getId(), 10L});
        when(questionRepository.countQuestionsForQuizzes(List.of(testQuiz.getId())))
                .thenReturn(countResults);
        when(attemptMapper.toQuizSummaryDto(any(), anyInt())).thenCallRealMethod();
        when(attemptMapper.toSummaryDto(any(), any(), any())).thenCallRealMethod();

        // When
        Page<AttemptSummaryDto> result = service.getAttemptsSummary(
                "adminuser",
                pageable,
                null,
                otherUserId,
                null
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("getAttemptsSummary: when in-progress attempt then stats is null")
    void getAttemptsSummary_inProgressAttempt_statsNull() {
        // Given
        testAttempt.setStatus(AttemptStatus.IN_PROGRESS);
        testAttempt.setCompletedAt(null);
        
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser"))
                .thenReturn(Optional.of(testUser));
        
        Page<Attempt> attemptPage = new PageImpl<>(List.of(testAttempt));
        when(attemptRepository.findAllWithQuizAndAnswersEager(null, testUser.getId(), null, pageable))
                .thenReturn(attemptPage);
        
        // Mock batch question count query
        List<Object[]> countResults = new ArrayList<>();
        countResults.add(new Object[]{testQuiz.getId(), 10L});
        when(questionRepository.countQuestionsForQuizzes(List.of(testQuiz.getId())))
                .thenReturn(countResults);
        when(attemptMapper.toQuizSummaryDto(any(), anyInt())).thenCallRealMethod();
        when(attemptMapper.toSummaryDto(any(), any(), any())).thenCallRealMethod();

        // When
        Page<AttemptSummaryDto> result = service.getAttemptsSummary(
                "testuser",
                pageable,
                null,
                null,
                null
        );

        // Then
        assertThat(result.getContent()).hasSize(1);
        AttemptSummaryDto summary = result.getContent().get(0);
        assertThat(summary.stats()).isNull();  // In-progress attempts have no stats
        assertThat(summary.quiz()).isNotNull();  // But still have quiz summary
    }

    @Test
    @DisplayName("getAttemptsSummary: when user not found then throws ResourceNotFoundException")
    void getAttemptsSummary_userNotFound_throwsResourceNotFound() {
        // Given
        when(userRepository.findByUsernameWithRolesAndPermissions("unknownuser"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRolesAndPermissions("unknownuser"))
                .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> service.getAttemptsSummary(
                "unknownuser",
                pageable,
                null,
                null,
                null
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User unknownuser not found");
    }

    @Test
    @DisplayName("getAttemptsSummary: with filters then passes filters to repository")
    void getAttemptsSummary_withFilters_passesFilters() {
        // Given
        UUID quizId = UUID.randomUUID();
        AttemptStatus status = AttemptStatus.COMPLETED;
        
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser"))
                .thenReturn(Optional.of(testUser));
        
        Page<Attempt> attemptPage = new PageImpl<>(List.of(testAttempt));
        when(attemptRepository.findAllWithQuizAndAnswersEager(quizId, testUser.getId(), status, pageable))
                .thenReturn(attemptPage);
        
        // Mock batch question count query
        List<Object[]> countResults = new ArrayList<>();
        countResults.add(new Object[]{testQuiz.getId(), 10L});
        when(questionRepository.countQuestionsForQuizzes(List.of(testQuiz.getId())))
                .thenReturn(countResults);
        when(attemptMapper.toQuizSummaryDto(any(), anyInt())).thenCallRealMethod();
        when(attemptMapper.toSummaryDto(any(), any(), any())).thenCallRealMethod();

        // When
        Page<AttemptSummaryDto> result = service.getAttemptsSummary(
                "testuser",
                pageable,
                quizId,
                null,
                status
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("getAttemptsSummary: stats include accuracy and completion percentages")
    void getAttemptsSummary_completedAttempt_includesPercentages() {
        // Given
        Answer answer1 = new Answer();
        answer1.setIsCorrect(true);
        answer1.setAnsweredAt(Instant.now().minusSeconds(10));

        Answer answer2 = new Answer();
        answer2.setIsCorrect(false);
        answer2.setAnsweredAt(Instant.now().minusSeconds(5));

        testAttempt.setAnswers(List.of(answer1, answer2));
        
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser"))
                .thenReturn(Optional.of(testUser));
        
        Page<Attempt> attemptPage = new PageImpl<>(List.of(testAttempt));
        when(attemptRepository.findAllWithQuizAndAnswersEager(null, testUser.getId(), null, pageable))
                .thenReturn(attemptPage);
        
        // Mock batch question count query
        List<Object[]> countResults = new ArrayList<>();
        countResults.add(new Object[]{testQuiz.getId(), 10L});
        when(questionRepository.countQuestionsForQuizzes(List.of(testQuiz.getId())))
                .thenReturn(countResults);
        when(attemptMapper.toQuizSummaryDto(any(), anyInt())).thenCallRealMethod();
        when(attemptMapper.toSummaryDto(any(), any(), any())).thenCallRealMethod();

        // When
        Page<AttemptSummaryDto> result = service.getAttemptsSummary(
                "testuser",
                pageable,
                null,
                null,
                null
        );

        // Then
        AttemptSummaryDto summary = result.getContent().get(0);
        assertThat(summary.stats()).isNotNull();
        assertThat(summary.stats().questionsAnswered()).isEqualTo(2);
        assertThat(summary.stats().correctAnswers()).isEqualTo(1);
        assertThat(summary.stats().accuracyPercentage()).isEqualTo(50.0);  // 1/2 = 50%
        assertThat(summary.stats().completionPercentage()).isEqualTo(20.0);  // 2/10 = 20%
    }

    @Test
    @DisplayName("getAttemptsSummary: batch fetches answers to avoid N+1 queries")
    void getAttemptsSummary_batchFetchesAnswers_avoidsN1() {
        // Given: Multiple completed attempts in the result
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser"))
                .thenReturn(Optional.of(testUser));

        Attempt attempt1 = new Attempt();
        attempt1.setId(UUID.randomUUID());
        attempt1.setUser(testUser);
        attempt1.setQuiz(testQuiz);
        attempt1.setStatus(AttemptStatus.COMPLETED);
        attempt1.setStartedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        attempt1.setCompletedAt(Instant.now());

        Attempt attempt2 = new Attempt();
        attempt2.setId(UUID.randomUUID());
        attempt2.setUser(testUser);
        attempt2.setQuiz(testQuiz);
        attempt2.setStatus(AttemptStatus.COMPLETED);
        attempt2.setStartedAt(Instant.now().minus(15, ChronoUnit.MINUTES));
        attempt2.setCompletedAt(Instant.now().minus(5, ChronoUnit.MINUTES));

        List<Attempt> attemptList = List.of(attempt1, attempt2);
        Page<Attempt> attemptPage = new PageImpl<>(attemptList);

        when(attemptRepository.findAllWithQuizAndAnswersEager(null, testUser.getId(), null, pageable))
                .thenReturn(attemptPage);

        // Mock the batch fetch call (this is the N+1 fix verification)
        when(attemptRepository.batchFetchAnswersForAttempts(
                argThat(ids -> ids.containsAll(List.of(attempt1.getId(), attempt2.getId())))
        )).thenReturn(attemptList);

        List<Object[]> countResults = new ArrayList<>();
        countResults.add(new Object[]{testQuiz.getId(), 10L});
        when(questionRepository.countQuestionsForQuizzes(List.of(testQuiz.getId())))
                .thenReturn(countResults);

        when(attemptMapper.toQuizSummaryDto(any(), anyInt())).thenCallRealMethod();
        when(attemptMapper.toSummaryDto(any(), any(), any())).thenCallRealMethod();

        // When
        Page<AttemptSummaryDto> result = service.getAttemptsSummary(
                "testuser",
                pageable,
                null,
                null,
                null
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(2);

        // Verify batch fetch was called once with all attempt IDs
        // This proves we're avoiding N+1 queries for answers
        verify(attemptRepository).batchFetchAnswersForAttempts(
                argThat(ids -> ids.size() == 2 && 
                        ids.contains(attempt1.getId()) && 
                        ids.contains(attempt2.getId()))
        );
    }
}

