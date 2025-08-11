package uk.gegc.quizmaker.service.quiz.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.QuizStatus;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class ModerationServiceImplTest {

    @Mock
    private QuizRepository quizRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ModerationServiceImpl moderationService;

    private UUID quizId;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        quizId = UUID.randomUUID();
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setUsername("moderator");
        user.setEmail("m@example.com");
    }

    @Test
    @DisplayName("submitForReview: DRAFT → PENDING_REVIEW succeeds and clears review fields")
    void submitFromDraft_success() {
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setReviewedAt(Instant.now());
        quiz.setReviewedBy(new User());
        quiz.setRejectionReason("Because");

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(quizRepository.save(any(Quiz.class))).thenAnswer(inv -> inv.getArgument(0));

        moderationService.submitForReview(quizId, userId);

        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PENDING_REVIEW);
        assertThat(quiz.getReviewedAt()).isNull();
        assertThat(quiz.getReviewedBy()).isNull();
        assertThat(quiz.getRejectionReason()).isNull();
        verify(quizRepository).save(quiz);
    }

    @Test
    @DisplayName("submitForReview: REJECTED → PENDING_REVIEW succeeds")
    void submitFromRejected_success() {
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.REJECTED);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        moderationService.submitForReview(quizId, userId);

        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PENDING_REVIEW);
        verify(quizRepository).save(quiz);
    }

    @Test
    @DisplayName("submitForReview: PUBLISHED → PENDING_REVIEW succeeds (allowed by state machine)")
    void submitFromPublished_success() {
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.PUBLISHED);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        moderationService.submitForReview(quizId, userId);

        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PENDING_REVIEW);
        verify(quizRepository).save(quiz);
    }

    @Test
    @DisplayName("submitForReview: ARCHIVED → PENDING_REVIEW is not allowed")
    void submitFromArchived_throwsValidation() {
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.ARCHIVED);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> moderationService.submitForReview(quizId, userId))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot transition to PENDING_REVIEW");
        verify(quizRepository, never()).save(any());
    }

    @Test
    @DisplayName("submitForReview: Quiz not found")
    void submit_quizNotFound() {
        when(quizRepository.findById(quizId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> moderationService.submitForReview(quizId, userId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Quiz " + quizId + " not found");
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("submitForReview: User not found")
    void submit_userNotFound() {
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.DRAFT);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moderationService.submitForReview(quizId, userId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User " + userId + " not found");
        verify(quizRepository, never()).save(any());
    }
}


