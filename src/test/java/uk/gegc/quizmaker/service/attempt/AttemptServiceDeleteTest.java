package uk.gegc.quizmaker.service.attempt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.attempt.AttemptStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.attempt.AttemptRepository;
import uk.gegc.quizmaker.features.question.domain.repository.AnswerRepository;
import uk.gegc.quizmaker.service.attempt.impl.AttemptServiceImpl;

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
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(testAttempt));
        doNothing().when(answerRepository).deleteByAttemptId(attemptId);
        doNothing().when(attemptRepository).delete(testAttempt);

        // Act
        assertDoesNotThrow(() -> attemptService.deleteAttempt(username, attemptId));

        // Assert
        verify(attemptRepository).findById(attemptId);
        verify(answerRepository).deleteByAttemptId(attemptId);
        verify(attemptRepository).delete(testAttempt);
    }

    @Test
    @DisplayName("deleteAttempt: throws ResourceNotFoundException when attempt not found")
    void deleteAttempt_notFound_throws404() {
        // Arrange
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(ResourceNotFoundException.class, () ->
                attemptService.deleteAttempt(username, attemptId));

        verify(attemptRepository).findById(attemptId);
        verify(answerRepository, never()).deleteByAttemptId(any());
        verify(attemptRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteAttempt: throws AccessDeniedException when user doesn't own the attempt")
    void deleteAttempt_wrongUser_throws403() {
        // Arrange
        User differentUser = new User();
        differentUser.setUsername("differentuser");
        testAttempt.setUser(differentUser);

        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(testAttempt));

        // Act & Assert
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                attemptService.deleteAttempt(username, attemptId));

        verify(attemptRepository).findById(attemptId);
        verify(answerRepository, never()).deleteByAttemptId(any());
        verify(attemptRepository, never()).delete(any());
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
            when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(testAttempt));
            doNothing().when(answerRepository).deleteByAttemptId(attemptId);
            doNothing().when(attemptRepository).delete(testAttempt);

            // Act & Assert
            assertDoesNotThrow(() -> attemptService.deleteAttempt(username, attemptId));

            // Verify
            verify(attemptRepository).findById(attemptId);
            verify(answerRepository).deleteByAttemptId(attemptId);
            verify(attemptRepository).delete(testAttempt);

            // Reset mocks for next iteration
            reset(attemptRepository, answerRepository);
        }
    }
}