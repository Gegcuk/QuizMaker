package uk.gegc.quizmaker.service.attempt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptDto;
import uk.gegc.quizmaker.features.attempt.api.dto.StartAttemptResponse;
import uk.gegc.quizmaker.features.attempt.application.ScoringService;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.attempt.infra.mapping.AttemptMapper;
import uk.gegc.quizmaker.features.question.domain.repository.AnswerRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.mapping.AnswerMapper;
import uk.gegc.quizmaker.features.question.infra.mapping.SafeQuestionMapper;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.result.api.dto.LeaderboardEntryDto;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class AttemptServiceImplTest {

    @Mock
    QuizRepository quizRepository;
    @Mock
    AttemptRepository attemptRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    AttemptMapper attemptMapper;
    @Mock
    QuestionRepository questionRepository;
    @Mock
    QuestionHandlerFactory handlerFactory;
    @Mock
    AnswerRepository answerRepository;
    @Mock
    AnswerMapper answerMapper;
    @Mock
    ScoringService scoringService;
    @Mock
    SafeQuestionMapper safeQuestionMapper;
    @Mock
    AppPermissionEvaluator appPermissionEvaluator;

    @InjectMocks
    AttemptServiceImpl service;

    @Test
    @DisplayName("getLeaderboard returns top N ordered by score")
    void getLeaderboard_basic() {
        UUID quizId = UUID.randomUUID();
        Quiz quiz = new Quiz();
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setStatus(QuizStatus.PUBLISHED);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID u3 = UUID.randomUUID();
        List<Object[]> rows = List.of(
                new Object[]{u1, "alice", 90.0},
                new Object[]{u2, "bob", 80.0},
                new Object[]{u3, "carol", 70.0}
        );
        when(attemptRepository.getLeaderboardData(quizId)).thenReturn(rows);

        List<LeaderboardEntryDto> result = service.getQuizLeaderboard(quizId, 2, mock(Authentication.class));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).userId()).isEqualTo(u1);
        assertThat(result.get(0).bestScore()).isEqualTo(90.0);
        assertThat(result.get(1).userId()).isEqualTo(u2);
    }

    @Test
    @DisplayName("getLeaderboard handles ties and insufficient participants")
    void getLeaderboard_tiesAndShortList() {
        UUID quizId = UUID.randomUUID();
        Quiz quiz = new Quiz();
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setStatus(QuizStatus.PUBLISHED);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        List<Object[]> rows = List.of(
                new Object[]{u1, "alice", 50.0},
                new Object[]{u2, "bob", 50.0}
        );
        when(attemptRepository.getLeaderboardData(quizId)).thenReturn(rows);

        List<LeaderboardEntryDto> result = service.getQuizLeaderboard(quizId, 5, mock(Authentication.class));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).bestScore()).isEqualTo(50.0);
        assertThat(result.get(1).bestScore()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("getLeaderboard throws when quiz not found")
    void getLeaderboard_quizMissing() {
        UUID quizId = UUID.randomUUID();
        when(quizRepository.findById(quizId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getQuizLeaderboard(quizId, 3, mock(Authentication.class)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getAttempts returns current user's attempts when no userId provided")
    void getAttempts_defaultsToAuthenticatedUser() {
        String username = "alice";
        UUID currentUserId = UUID.randomUUID();
        User currentUser = new User();
        currentUser.setId(currentUserId);

        Pageable pageable = PageRequest.of(0, 10);

        when(userRepository.findByUsernameWithRolesAndPermissions(username)).thenReturn(Optional.of(currentUser));
        when(attemptRepository.findAllByQuizAndUserEager(null, currentUserId, pageable))
                .thenReturn(Page.empty(pageable));

        Page<AttemptDto> result = service.getAttempts(username, pageable, null, null);

        assertThat(result).isEmpty();
        verify(attemptRepository).findAllByQuizAndUserEager(isNull(), eq(currentUserId), eq(pageable));
    }

    @Test
    @DisplayName("getAttempts forbids filtering by other user without ATTEMPT_READ_ALL")
    void getAttempts_otherUserWithoutPermission() {
        String username = "alice";
        UUID currentUserId = UUID.randomUUID();
        User currentUser = new User();
        currentUser.setId(currentUserId);

        UUID requestedUserId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 5);

        when(userRepository.findByUsernameWithRolesAndPermissions(username)).thenReturn(Optional.of(currentUser));
        when(appPermissionEvaluator.hasPermission(eq(currentUser), eq(PermissionName.ATTEMPT_READ_ALL))).thenReturn(false);
        when(appPermissionEvaluator.hasPermission(eq(currentUser), eq(PermissionName.SYSTEM_ADMIN))).thenReturn(false);

        assertThatThrownBy(() -> service.getAttempts(username, pageable, null, requestedUserId))
                .isInstanceOf(AccessDeniedException.class);

        verify(attemptRepository, never()).findAllByQuizAndUserEager(any(), any(), any());
    }

    @Test
    @DisplayName("getAttempts allows filtering by other user when caller has ATTEMPT_READ_ALL")
    void getAttempts_otherUserWithPermission() {
        String username = "moderator";
        UUID currentUserId = UUID.randomUUID();
        User currentUser = new User();
        currentUser.setId(currentUserId);

        UUID requestedUserId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 5);

        when(userRepository.findByUsernameWithRolesAndPermissions(username)).thenReturn(Optional.of(currentUser));
        when(appPermissionEvaluator.hasPermission(eq(currentUser), eq(PermissionName.ATTEMPT_READ_ALL))).thenReturn(true);
        when(attemptRepository.findAllByQuizAndUserEager(null, requestedUserId, pageable))
                .thenReturn(Page.empty(pageable));

        Page<AttemptDto> result = service.getAttempts(username, pageable, null, requestedUserId);

        assertThat(result).isEmpty();
        verify(attemptRepository).findAllByQuizAndUserEager(isNull(), eq(requestedUserId), eq(pageable));
    }

    @Test
    @DisplayName("startAttempt returns correct metadata without firstQuestion")
    void startAttempt_returnsMetadataWithoutFirstQuestion() {
        // Given
        String username = "testuser";
        UUID quizId = UUID.randomUUID();
        AttemptMode mode = AttemptMode.ONE_BY_ONE;

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);

        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setIsTimerEnabled(true);
        quiz.setTimerDuration(30);
        // no questions collection setup here; totalQuestions derived from size (defaults 0)

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(attemptRepository.saveAndFlush(any(Attempt.class))).thenAnswer(invocation -> {
            Attempt attempt = invocation.getArgument(0);
            attempt.setId(UUID.randomUUID());
            attempt.setStartedAt(java.time.Instant.now());
            return attempt;
        });

        // When
        StartAttemptResponse response = service.startAttempt(username, quizId, mode);

        // Then
        assertThat(response.attemptId()).isNotNull();
        assertThat(response.quizId()).isEqualTo(quizId);
        assertThat(response.mode()).isEqualTo(mode);
        assertThat(response.totalQuestions()).isEqualTo(0);
        assertThat(response.timeLimitMinutes()).isEqualTo(30);
        assertThat(response.startedAt()).isNotNull();
    }
}