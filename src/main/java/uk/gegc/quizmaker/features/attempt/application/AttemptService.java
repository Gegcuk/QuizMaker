package uk.gegc.quizmaker.features.attempt.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.result.api.dto.LeaderboardEntryDto;
import uk.gegc.quizmaker.features.result.api.dto.QuizResultSummaryDto;
import uk.gegc.quizmaker.shared.exception.AttemptNotCompletedException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

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

    QuizResultSummaryDto getQuizResultSummary(UUID quizId, Authentication authentication);

    List<LeaderboardEntryDto> getQuizLeaderboard(UUID quizId, int top, Authentication authentication);

    // 🔒 Security & Safety Methods
    List<QuestionForAttemptDto> getShuffledQuestions(UUID quizId, Authentication authentication);

    // 📊 Enhanced Analytics
    AttemptStatsDto getAttemptStats(UUID attemptId, String username);

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

    /**
     * Get a comprehensive review of a completed attempt with user answers and correct answers.
     * Only available to the attempt owner and only for completed attempts.
     *
     * @param username              the username of the authenticated user
     * @param attemptId             the UUID of the attempt to review
     * @param includeUserAnswers    whether to include user's submitted responses
     * @param includeCorrectAnswers whether to include correct answer payloads
     * @param includeQuestionContext whether to include safe question context for rendering
     * @return AttemptReviewDto with detailed answer review data
     * @throws ResourceNotFoundException if the attempt is not found
     * @throws AccessDeniedException if the user doesn't own the attempt
     * @throws AttemptNotCompletedException if the attempt is not completed
     */
    AttemptReviewDto getAttemptReview(String username, UUID attemptId,
                                      boolean includeUserAnswers,
                                      boolean includeCorrectAnswers,
                                      boolean includeQuestionContext);

    /**
     * Get an answer key for a completed attempt (correct answers only, no user responses).
     * This is a convenience method that calls getAttemptReview with fixed flags:
     * includeUserAnswers=false, includeCorrectAnswers=true, includeQuestionContext=true.
     *
     * @param username  the username of the authenticated user
     * @param attemptId the UUID of the attempt
     * @return AttemptReviewDto with correct answers only
     * @throws ResourceNotFoundException if the attempt is not found
     * @throws AccessDeniedException if the user doesn't have permission
     * @throws AttemptNotCompletedException if the attempt is not completed
     */
    AttemptReviewDto getAttemptAnswerKey(String username, UUID attemptId);
}
