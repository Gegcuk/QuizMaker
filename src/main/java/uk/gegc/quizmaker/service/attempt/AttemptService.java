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

    /**
     * Delete an attempt and all its associated answers.
     * Users can only delete their own attempts.
     *
     * @param username  the username of the authenticated user
     * @param attemptId the UUID of the attempt to delete
     * @throws ResourceNotFoundException if the attempt is not found
     * @throws AccessDeniedException if the user doesn't own the attempt
     */
    void deleteAttempt(String username, UUID attemptId);

    /**
     * Get the current question for an existing attempt.
     * This is useful when a user wants to resume an attempt and needs to see the current question.
     *
     * @param username  the username of the authenticated user
     * @param attemptId the UUID of the attempt
     * @return CurrentQuestionDto containing the current question and progress information
     * @throws ResourceNotFoundException if the attempt is not found
     * @throws AccessDeniedException if the user doesn't own the attempt
     * @throws IllegalStateException if the attempt is not in progress or all questions are answered
     */
    CurrentQuestionDto getCurrentQuestion(String username, UUID attemptId);

    // 👨‍💼 Admin Functions
    List<AttemptDto> getAttemptsByDateRange(LocalDate start, LocalDate end);

    void flagSuspiciousActivity(UUID attemptId, String reason);

    /**
     * Start an anonymous attempt for a quiz accessed via a valid share link.
     * The attempt will be associated with a special anonymous user context and
     * subject to additional rate limits and visibility constraints.
     */
    StartAttemptResponse startAnonymousAttempt(UUID quizId, UUID shareLinkId, AttemptMode mode);

    /**
     * Returns the quizId associated with the given attempt.
     */
    UUID getAttemptQuizId(UUID attemptId);

    UUID getAttemptShareLinkId(UUID attemptId);

    /**
     * Owner-only: list all attempts for a given quiz.
     */
    List<AttemptDto> getAttemptsForQuizOwner(String username, UUID quizId);

    /**
     * Owner-only: get stats for an attempt that belongs to the given quiz.
     * Returns 404 if the attempt does not belong to the quiz to avoid information leakage.
     */
    AttemptStatsDto getAttemptStatsForQuizOwner(String username, UUID quizId, UUID attemptId);
}
