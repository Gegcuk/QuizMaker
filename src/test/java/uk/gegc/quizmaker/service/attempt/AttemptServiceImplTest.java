package uk.gegc.quizmaker.service.attempt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.dto.result.LeaderboardEntryDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.repository.attempt.AttemptRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.service.attempt.impl.AttemptServiceImpl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class AttemptServiceImplTest {

    @Mock
    QuizRepository quizRepository;
    @Mock
    AttemptRepository attemptRepository;

    @InjectMocks
    AttemptServiceImpl service;

    @Test
    @DisplayName("getLeaderboard returns top N ordered by score")
    void getLeaderboard_basic() {
        UUID quizId = UUID.randomUUID();
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(new Quiz()));
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID u3 = UUID.randomUUID();
        List<Object[]> rows = List.of(
                new Object[]{u1, "alice", 90.0},
                new Object[]{u2, "bob", 80.0},
                new Object[]{u3, "carol", 70.0}
        );
        when(attemptRepository.getLeaderboardData(quizId)).thenReturn(rows);

        List<LeaderboardEntryDto> result = service.getQuizLeaderboard(quizId, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).userId()).isEqualTo(u1);
        assertThat(result.get(0).bestScore()).isEqualTo(90.0);
        assertThat(result.get(1).userId()).isEqualTo(u2);
    }

    @Test
    @DisplayName("getLeaderboard handles ties and insufficient participants")
    void getLeaderboard_tiesAndShortList() {
        UUID quizId = UUID.randomUUID();
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(new Quiz()));
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        List<Object[]> rows = List.of(
                new Object[]{u1, "alice", 50.0},
                new Object[]{u2, "bob", 50.0}
        );
        when(attemptRepository.getLeaderboardData(quizId)).thenReturn(rows);

        List<LeaderboardEntryDto> result = service.getQuizLeaderboard(quizId, 5);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).bestScore()).isEqualTo(50.0);
        assertThat(result.get(1).bestScore()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("getLeaderboard throws when quiz not found")
    void getLeaderboard_quizMissing() {
        UUID quizId = UUID.randomUUID();
        when(quizRepository.findById(quizId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getQuizLeaderboard(quizId, 3))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}