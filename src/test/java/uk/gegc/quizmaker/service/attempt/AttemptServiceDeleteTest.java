package uk.gegc.quizmaker.service.attempt;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptCommandState;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.CurrentAnswerRepository;
import uk.gegc.quizmaker.features.question.domain.repository.AnswerRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AttemptServiceDeleteTest {

    @Mock
    private AttemptRepository attemptRepository;

    @Mock
    private AnswerRepository answerRepository;

    @Mock
    private CurrentAnswerRepository currentAnswers;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private AttemptServiceImpl attemptService;

    private User testUser;
    private Quiz testQuiz;
    private Attempt testAttempt;
    private UUID attemptId;
    private String username;

    @BeforeEach
    void setUp() {
        attemptId = UUID.randomUUID();
        username = "testuser";

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername(username);

        testQuiz = new Quiz();
        testQuiz.setId(UUID.randomUUID());
        testQuiz.setTitle("Test Quiz");

        testAttempt = new Attempt();
        testAttempt.setId(attemptId);
        testAttempt.setUser(testUser);
        testAttempt.setQuiz(testQuiz);
        testAttempt.setStatus(AttemptStatus.IN_PROGRESS);
        testAttempt.setStartedAt(Instant.now());
    }

    @Test
    @DisplayName("deleteAttempt: successfully deletes attempt and its answers")
    void deleteAttempt_success() {
        // Arrange
        when(attemptRepository.findCurrentStateForUpdate(attemptId)).thenReturn(Optional.of(state()));
        when(attemptRepository.deleteLockedById(attemptId)).thenReturn(1);

        // Act
        assertDoesNotThrow(() -> attemptService.deleteAttempt(username, attemptId));

        // Assert
        verify(attemptRepository).findCurrentStateForUpdate(attemptId);
        verify(attemptRepository).deleteLockedById(attemptId);
        verify(entityManager).detach(testAttempt);
        verifyNoInteractions(answerRepository);
    }

    @Test
    @DisplayName("deleteAttempt: throws ResourceNotFoundException when attempt not found")
    void deleteAttempt_notFound_throws404() {
        // Arrange
        when(attemptRepository.findCurrentStateForUpdate(attemptId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(ResourceNotFoundException.class, () ->
                attemptService.deleteAttempt(username, attemptId));

        verify(attemptRepository).findCurrentStateForUpdate(attemptId);
        verify(answerRepository, never()).deleteByAttemptId(any());
        verify(attemptRepository, never()).delete(any());
        verify(attemptRepository, never()).deleteLockedById(any());
        verifyNoInteractions(currentAnswers, entityManager);
    }

    @Test
    @DisplayName("deleteAttempt: throws AccessDeniedException when user doesn't own the attempt")
    void deleteAttempt_wrongUser_throws403() {
        // Arrange
        User differentUser = new User();
        differentUser.setUsername("differentuser");
        testAttempt.setUser(differentUser);

        when(attemptRepository.findCurrentStateForUpdate(attemptId)).thenReturn(Optional.of(state()));

        // Act & Assert
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                attemptService.deleteAttempt(username, attemptId));

        verify(attemptRepository).findCurrentStateForUpdate(attemptId);
        verify(answerRepository, never()).deleteByAttemptId(any());
        verify(attemptRepository, never()).delete(any());
        verify(attemptRepository, never()).deleteLockedById(any());
        verifyNoInteractions(currentAnswers, entityManager);
    }

    @Test
    @DisplayName("deleteAttempt: works with different attempt statuses")
    void deleteAttempt_differentStatuses_success() {
        // Test with different attempt statuses
        AttemptStatus[] statuses = {
                AttemptStatus.IN_PROGRESS,
                AttemptStatus.COMPLETED,
                AttemptStatus.PAUSED,
                AttemptStatus.ABANDONED
        };

        for (AttemptStatus status : statuses) {
            // Arrange
            testAttempt.setStatus(status);
            when(attemptRepository.findCurrentStateForUpdate(attemptId)).thenReturn(Optional.of(state()));
            when(attemptRepository.deleteLockedById(attemptId)).thenReturn(1);

            // Act & Assert
            assertDoesNotThrow(() -> attemptService.deleteAttempt(username, attemptId));

            // Verify
            verify(attemptRepository).findCurrentStateForUpdate(attemptId);
            verify(attemptRepository).deleteLockedById(attemptId);
            verify(entityManager).detach(testAttempt);
            verifyNoInteractions(answerRepository);

            // Reset mocks for next iteration
            reset(attemptRepository, answerRepository, currentAnswers, entityManager);
        }
    }
    private AttemptCommandState state() {
        return new AttemptCommandState(testAttempt, testAttempt.getStatus(), testAttempt.getMode(),
                testAttempt.getStartedAt(), testAttempt.getCompletedAt(), testAttempt.getTotalScore(), "{\"version\":1,\"answers\":{}}");
    }

}
