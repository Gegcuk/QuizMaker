package uk.gegc.quizmaker.service.attempt;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.attempt.*;
import uk.gegc.quizmaker.dto.question.QuestionForAttemptDto;
import uk.gegc.quizmaker.dto.result.LeaderboardEntryDto;
import uk.gegc.quizmaker.dto.result.QuizResultSummaryDto;
import uk.gegc.quizmaker.model.attempt.AttemptMode;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AttemptService {
    StartAttemptResponse startAttempt(String username, UUID quizId, AttemptMode mode);

    Page<AttemptDto> getAttempts(String username, Pageable pageable, UUID quizId, UUID userId);

    AttemptDetailsDto getAttemptDetail(String username, UUID attemptId);

    AnswerSubmissionDto submitAnswer(String username, UUID attemptId, AnswerSubmissionRequest request);

    List<AnswerSubmissionDto> submitBatch(String username, UUID attemptId, BatchAnswerSubmissionRequest request);

    AttemptResultDto completeAttempt(String username, UUID attemptId);

    QuizResultSummaryDto getQuizResultSummary(UUID quizId);

    List<LeaderboardEntryDto> getQuizLeaderboard(UUID quizId, int top);

    // 🔒 Security & Safety Methods
    List<QuestionForAttemptDto> getShuffledQuestions(UUID quizId, String username);

    // 📊 Enhanced Analytics
    AttemptStatsDto getAttemptStats(UUID attemptId);

    // 🔧 Attempt Management
    AttemptDto pauseAttempt(String username, UUID attemptId);
    AttemptDto resumeAttempt(String username, UUID attemptId);

    // 👨‍💼 Admin Functions
    List<AttemptDto> getAttemptsByDateRange(LocalDate start, LocalDate end);
    void flagSuspiciousActivity(UUID attemptId, String reason);
}
