package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.attempt.application.ScoringService;
import uk.gegc.quizmaker.features.attempt.domain.event.AttemptCompletedEvent;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.attempt.infra.mapping.AttemptMapper;
import uk.gegc.quizmaker.features.question.application.CorrectAnswerExtractor;
import uk.gegc.quizmaker.features.question.application.SafeQuestionContentBuilder;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.repository.AnswerRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.mapping.AnswerMapper;
import uk.gegc.quizmaker.features.question.infra.mapping.SafeQuestionMapper;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.ShareLink;
import uk.gegc.quizmaker.features.quiz.domain.model.ShareLinkScope;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.ShareLinkRepository;
import uk.gegc.quizmaker.features.result.api.dto.LeaderboardEntryDto;
import uk.gegc.quizmaker.features.result.api.dto.QuestionStatsDto;
import uk.gegc.quizmaker.features.result.api.dto.QuizResultSummaryDto;
import uk.gegc.quizmaker.features.result.application.QuizAnalyticsService;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.AttemptNotCompletedException;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class AttemptServiceImpl implements AttemptService {


    private final UserRepository userRepository;
    private final QuizRepository quizRepository;
    private final AttemptRepository attemptRepository;
    private final AttemptMapper attemptMapper;
    private final QuestionRepository questionRepository;
    private final QuestionHandlerFactory handlerFactory;
    private final AnswerRepository answerRepository;
    private final AnswerMapper answerMapper;
    private final ScoringService scoringService;
    private final SafeQuestionMapper safeQuestionMapper;
    private final ShareLinkRepository shareLinkRepository;
    private final AppPermissionEvaluator appPermissionEvaluator;
    private final CorrectAnswerExtractor correctAnswerExtractor;
    private final SafeQuestionContentBuilder safeQuestionContentBuilder;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final QuizAnalyticsService quizAnalyticsService;

    @Override
    public StartAttemptResponse startAttempt(String username, UUID quizId, AttemptMode mode) {
        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        // Authorization check: quiz must be published and public, or user must be owner/moderator
        boolean canStartAttempt = (quiz.getVisibility() == Visibility.PUBLIC && quiz.getStatus() == QuizStatus.PUBLISHED)
                || (quiz.getCreator() != null && user.getId().equals(quiz.getCreator().getId()))
                || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE)
                || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN);

        if (!canStartAttempt) {
            throw new AccessDeniedException("You do not have permission to start an attempt on this quiz");
        }

        Attempt attempt = new Attempt();
        attempt.setUser(user);
        attempt.setQuiz(quiz);
        attempt.setMode(mode);
        attempt.setStatus(AttemptStatus.IN_PROGRESS);

        Attempt saved = attemptRepository.saveAndFlush(attempt);

        int totalQuestions = (int) questionRepository.countByQuizId_Id(quiz.getId());
        Integer timeLimitMinutes = Boolean.TRUE.equals(quiz.getIsTimerEnabled())
                ? quiz.getTimerDuration()
                : null;

        return new StartAttemptResponse(
                saved.getId(),
                quiz.getId(),
                mode,
                totalQuestions,
                timeLimitMinutes,
                saved.getStartedAt()
        );
    }

    @Override
    public StartAttemptResponse startAnonymousAttempt(UUID quizId, UUID shareLinkId, AttemptMode mode) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        ShareLink shareLink = shareLinkRepository.findById(shareLinkId)
                .orElseThrow(() -> new ResourceNotFoundException("ShareLink " + shareLinkId + " not found"));

        // Validate share link ownership and scope
        if (!shareLink.getQuiz().getId().equals(quizId)) {
            throw new ResourceNotFoundException("ShareLink does not belong to quiz " + quizId);
        }

        // Check if share link has permission to start attempts
        // Both QUIZ_VIEW and QUIZ_ATTEMPT_START scopes allow starting attempts
        if (shareLink.getScope() != ShareLinkScope.QUIZ_VIEW && shareLink.getScope() != ShareLinkScope.QUIZ_ATTEMPT_START) {
            throw new AccessDeniedException("ShareLink does not have permission to start attempts");
        }

        // Check if share link is expired
        if (shareLink.getExpiresAt() != null && shareLink.getExpiresAt().isBefore(Instant.now())) {
            throw new AccessDeniedException("ShareLink has expired");
        }

        // Check if share link is revoked
        if (shareLink.getRevokedAt() != null) {
            throw new AccessDeniedException("ShareLink has been revoked");
        }

        // Note: Share links can work on private/draft quizzes as long as the user has the valid share link
        // This allows quiz creators to share private quizzes with specific people

        // Use a sentinel anonymous user (by username), or create one if missing
        User user = userRepository.findByUsername("anonymous")
                .orElseGet(() -> {
                    User anon = new User();
                    anon.setUsername("anonymous");
                    anon.setEmail("anonymous@local");
                    anon.setHashedPassword("");
                    anon.setActive(true);
                    anon.setDeleted(false);
                    return userRepository.save(anon);
                });

        Attempt attempt = new Attempt();
        attempt.setUser(user);
        attempt.setQuiz(quiz);
        attempt.setShareLink(shareLink);
        attempt.setMode(mode);
        attempt.setStatus(AttemptStatus.IN_PROGRESS);

        Attempt saved = attemptRepository.saveAndFlush(attempt);

        int totalQuestions = (int) questionRepository.countByQuizId_Id(quiz.getId());
        Integer timeLimitMinutes = Boolean.TRUE.equals(quiz.getIsTimerEnabled())
                ? quiz.getTimerDuration()
                : null;

        return new StartAttemptResponse(
                saved.getId(),
                quiz.getId(),
                mode,
                totalQuestions,
                timeLimitMinutes,
                saved.getStartedAt()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public UUID getAttemptQuizId(UUID attemptId) {
        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        return attempt.getQuiz().getId();
    }

    @Override
    @Transactional(readOnly = true)
    public UUID getAttemptShareLinkId(UUID attemptId) {
        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        if (attempt.getShareLink() == null) {
            throw new ResourceNotFoundException("Attempt is not bound to a share link");
        }
        return attempt.getShareLink().getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AttemptDto> getAttempts(String username,
                                        Pageable pageable,
                                        UUID quizId,
                                        UUID userId) {
        User currentUser = userRepository.findByUsernameWithRolesAndPermissions(username)
                .or(() -> userRepository.findByEmailWithRolesAndPermissions(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        UUID filterUserId = currentUser.getId();
        if (userId != null) {
            if (!userId.equals(filterUserId)) {
                boolean canViewAllAttempts = appPermissionEvaluator.hasPermission(currentUser, PermissionName.ATTEMPT_READ_ALL)
                        || appPermissionEvaluator.hasPermission(currentUser, PermissionName.SYSTEM_ADMIN);
                if (!canViewAllAttempts) {
                    throw new AccessDeniedException("You do not have permission to view attempts for user " + userId);
                }
            }
            filterUserId = userId;
        }

        return attemptRepository
                .findAllByQuizAndUserEager(quizId, filterUserId, pageable)
                .map(attemptMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public AttemptDetailsDto getAttemptDetail(String username, UUID attemptId) {
        Attempt attempt = attemptRepository.findByIdWithAnswersAndQuestion(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        enforceOwnership(attempt, username);
        return attemptMapper.toDetailDto(attempt);
    }

    @Override
    @Transactional(readOnly = true)
    public CurrentQuestionDto getCurrentQuestion(String username, UUID attemptId) {
        Attempt attempt = attemptRepository.findFullyLoadedById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        enforceOwnership(attempt, username);

        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("Can only get current question for attempts that are in progress");
        }

        // Get all questions for the quiz from Question side
        List<Question> allQuestions = questionRepository.findAllByQuizId_IdOrderById(attempt.getQuiz().getId());
        int totalQuestions = allQuestions.size();
        
        if (totalQuestions == 0) {
            throw new IllegalStateException("Quiz has no questions");
        }

        // Count answers using a separate query to avoid collection issues
        long answeredCount = answerRepository.countByAttemptId(attemptId);
        
        if (answeredCount >= totalQuestions) {
            throw new IllegalStateException("All questions have already been answered");
        }

        // Get the current question (next unanswered question)
        Question currentQuestion = allQuestions.get((int) answeredCount);
        
        return new CurrentQuestionDto(
                safeQuestionMapper.toSafeDto(currentQuestion),
                (int) answeredCount + 1, // 1-based question number
                totalQuestions,
                attempt.getStatus()
        );
    }

    @Override
    @Transactional
    public AnswerSubmissionDto submitAnswer(String username,
                                            UUID attemptId,
                                            AnswerSubmissionRequest request) {
        Attempt attempt = attemptRepository.findFullyLoadedById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        enforceOwnership(attempt, username);

        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot submit answer to attempt with status " + attempt.getStatus());
        }

        if (attempt.getMode() == AttemptMode.TIMED && attempt.getQuiz().getIsTimerEnabled()) {
            Instant timeout = attempt.getStartedAt()
                    .plusSeconds(attempt.getQuiz().getTimerDuration() * 60L);
            if (Instant.now().isAfter(timeout)) {
                attempt.setStatus(AttemptStatus.ABANDONED);
                attempt.setCompletedAt(Instant.now());
                attemptRepository.save(attempt);
                throw new IllegalStateException("Attempt has timed out");
            }
        }

        Question question = questionRepository.findById(request.questionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Question " + request.questionId() + " not found"));

        // ensure question belongs to quiz - check from Question side
        boolean belongs = questionRepository.existsByIdAndQuizId_Id(question.getId(), attempt.getQuiz().getId());
        if (!belongs) {
            throw new ResourceNotFoundException(
                    "Question " + question.getId() + " is not part of Quiz " +
                            attempt.getQuiz().getId());
        }

        // For ONE_BY_ONE mode, enforce sequential question submission
        if (attempt.getMode() == AttemptMode.ONE_BY_ONE) {
            // Get all questions for the quiz from Question side
            List<Question> allQuestions = questionRepository.findAllByQuizId_IdOrderById(attempt.getQuiz().getId());
            
            // Count answers using the same approach as getCurrentQuestion to ensure consistency
            long answeredCount = answerRepository.countByAttemptId(attemptId);
            
            // Determine which question should be answered next
            if (answeredCount >= allQuestions.size()) {
                throw new IllegalStateException("All questions have already been answered");
            }
            
            Question expectedQuestion = allQuestions.get((int) answeredCount);
            
            // Verify the submitted question is the expected next question
            if (!expectedQuestion.getId().equals(question.getId())) {
                throw new IllegalStateException(
                        "Expected question " + expectedQuestion.getId() + 
                        " but received " + question.getId() + 
                        " (answered count: " + answeredCount + ")");
            }
        } else {
            // For ALL_AT_ONCE mode, just check for duplicate answers
            boolean already = attempt.getAnswers().stream()
                    .map(a -> a.getQuestion().getId())
                    .anyMatch(id -> id.equals(question.getId()));
            if (already) {
                throw new IllegalStateException(
                        "Already answered question " + question.getId() + " in this attempt");
            }
        }

        var handler = handlerFactory.getHandler(question.getType());
        Answer answer = handler.handle(attempt, question, request);
        answer = answerRepository.save(answer);

        var baseDto = answerMapper.toDto(answer);
        QuestionForAttemptDto nextQuestion = null;
        if (attempt.getMode() == AttemptMode.ONE_BY_ONE) {
            // Get all questions for the quiz from Question side
            List<Question> allQuestions = questionRepository.findAllByQuizId_IdOrderById(attempt.getQuiz().getId());
            
            // Count answers using the same approach as getCurrentQuestion to ensure consistency
            long answeredCount = answerRepository.countByAttemptId(attemptId);
            
            // Check if there are more questions to answer
            if (answeredCount < allQuestions.size()) {
                Question nextQ = allQuestions.get((int) answeredCount);
                nextQuestion = safeQuestionMapper.toSafeDto(nextQ);
            }
        }

        Boolean isCorrect = request.includeCorrectness() ? baseDto.isCorrect() : null;
        JsonNode correctAnswer = null;
        if (request.includeCorrectAnswer()) {
            try {
                correctAnswer = correctAnswerExtractor.extractCorrectAnswer(question);
            } catch (IllegalArgumentException e) {
                // Log error but don't fail entire response
                correctAnswer = objectMapper.createObjectNode()
                        .put("error", "Failed to extract correct answer: " + e.getMessage());
            }
        }
        String explanation = request.includeExplanation() ? question.getExplanation() : null;

        return new AnswerSubmissionDto(
                baseDto.answerId(),
                baseDto.questionId(),
                isCorrect,
                baseDto.score(),
                baseDto.answeredAt(),
                correctAnswer,
                explanation,
                nextQuestion
        );
    }

    @Override
    @Transactional
    public List<AnswerSubmissionDto> submitBatch(String username,
                                                 UUID attemptId,
                                                 BatchAnswerSubmissionRequest request) {
        Attempt attempt = attemptRepository.findFullyLoadedById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        enforceOwnership(attempt, username);

        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot submit answers to a non‐in‐progress attempt");
        }
        if (attempt.getMode() != AttemptMode.ALL_AT_ONCE) {
            throw new IllegalStateException("Batch submissions only allowed in ALL_AT_ONCE mode");
        }

        return request.answers().stream()
                .map(req -> submitAnswer(username, attemptId, req))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public AttemptResultDto completeAttempt(String username, UUID attemptId) {
        Attempt attempt = attemptRepository.findFullyLoadedById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        enforceOwnership(attempt, username);

        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot complete attempt with status " + attempt.getStatus());
        }

        scoringService.computeAndPersistScore(attempt);
        long correctCount = scoringService.countCorrect(attempt);
        int totalQ = (int) questionRepository.countByQuizId_Id(attempt.getQuiz().getId());

        attempt.setStatus(AttemptStatus.COMPLETED);
        Instant completedAt = Instant.now();
        attempt.setCompletedAt(completedAt);

        // Publish event for analytics snapshot update (and future subscribers)
        eventPublisher.publishEvent(new AttemptCompletedEvent(
                this,
                attempt.getId(),
                attempt.getQuiz().getId(),
                attempt.getUser().getId(),
                completedAt
        ));

        return attemptMapper.toResultDto(attempt, correctCount, totalQ);
    }

    @Override
    @Transactional(readOnly = true)
    public QuizResultSummaryDto getQuizResultSummary(UUID quizId, Authentication authentication) {
        Quiz quiz = quizRepository.findByIdWithQuestions(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        // Access control: allow access if quiz is public, user is owner, or user has moderation permissions
        checkQuizAccessPermission(quiz, authentication);

        // Use analytics snapshot for summary statistics (fast path)
        var snapshot = quizAnalyticsService.getOrComputeSnapshot(quizId);
        long attemptsCount = snapshot.getAttemptsCount();
        double averageScore = snapshot.getAverageScore();
        double bestScore = snapshot.getBestScore();
        double worstScore = snapshot.getWorstScore();
        double passRate = snapshot.getPassRate();

        // Compute per-question stats from raw data (not cached in snapshot)
        // Use eager loading to avoid N+1 when accessing answers
        List<Attempt> completed = attemptRepository.findCompletedWithAnswersByQuizId(quizId);

        List<QuestionStatsDto> questionStats = questionRepository.findAllByQuizId_IdOrderById(quiz.getId()).stream()
                .map(q -> {
                    UUID qid = q.getId();
                    long asked = completed.stream()
                            .filter(a -> a.getAnswers().stream()
                                    .anyMatch(ans -> ans.getQuestion().getId().equals(qid)))
                            .count();
                    long correct = completed.stream()
                            .flatMap(a -> a.getAnswers().stream())
                            .filter(ans -> ans.getQuestion().getId().equals(qid)
                                    && Boolean.TRUE.equals(ans.getIsCorrect()))
                            .count();
                    double rate = asked > 0 ? ((double) correct / asked) * 100.0 : 0.0;
                    return new QuestionStatsDto(qid, asked, correct, rate);
                })
                .toList();

        return new QuizResultSummaryDto(
                quizId,
                attemptsCount,
                averageScore,
                bestScore,
                worstScore,
                passRate,
                questionStats
        );
    }

    @Override
    @Transactional
    public List<LeaderboardEntryDto> getQuizLeaderboard(UUID quizId, int top, Authentication authentication) {
        if (top <= 0) {
            return List.of();
        }

        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        // Access control: allow access if quiz is public, user is owner, or user has moderation permissions
        checkQuizAccessPermission(quiz, authentication);

        List<Object[]> rows = attemptRepository.getLeaderboardData(quizId);
        return rows.stream()
                .limit(top)
                .map(r -> new LeaderboardEntryDto(
                        (UUID) r[0],
                        (String) r[1],
                        r[2] != null ? ((Number) r[2]).doubleValue() : 0.0
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuestionForAttemptDto> getShuffledQuestions(UUID quizId, Authentication authentication) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        checkQuizAccessPermission(quiz, authentication);

        List<Question> questions = questionRepository.findAllByQuizId_IdOrderById(quizId);
        Collections.shuffle(questions);

        return safeQuestionMapper.toSafeDtoList(questions);
    }

    @Override
    @Transactional(readOnly = true)
    public AttemptStatsDto getAttemptStats(UUID attemptId, String username) {
        Attempt attempt = attemptRepository.findByIdWithAnswersAndQuestion(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));

        // Authorization check: user must own the attempt or have appropriate permissions
        User currentUser = userRepository.findByUsernameWithRolesAndPermissions(username)
                .or(() -> userRepository.findByEmailWithRolesAndPermissions(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        boolean canViewStats = attempt.getUser().getId().equals(currentUser.getId())
                || appPermissionEvaluator.hasPermission(currentUser, PermissionName.ATTEMPT_READ_ALL)
                || appPermissionEvaluator.hasPermission(currentUser, PermissionName.SYSTEM_ADMIN);

        if (!canViewStats) {
            throw new AccessDeniedException("You do not have permission to view stats for attempt " + attemptId);
        }

        Duration totalTime = attempt.getCompletedAt() != null && attempt.getStartedAt() != null
                ? Duration.between(attempt.getStartedAt(), attempt.getCompletedAt())
                : Duration.ZERO;

        int questionsAnswered = attempt.getAnswers().size();
        int totalQuestions = (int) questionRepository.countByQuizId_Id(attempt.getQuiz().getId());
        long correctAnswers = attempt.getAnswers().stream()
                .filter(answer -> Boolean.TRUE.equals(answer.getIsCorrect()))
                .count();

        double accuracyPercentage = questionsAnswered > 0
                ? (double) correctAnswers / questionsAnswered * 100.0
                : 0.0;
        double completionPercentage = totalQuestions > 0
                ? (double) questionsAnswered / totalQuestions * 100.0
                : 0.0;

        Duration averageTimePerQuestion = questionsAnswered > 0
                ? totalTime.dividedBy(questionsAnswered)
                : Duration.ZERO;

        List<QuestionTimingStatsDto> questionTimings = attempt.getAnswers().stream()
                .map(answer -> {
                    Duration questionTime = Duration.between(
                            attempt.getStartedAt(),
                            answer.getAnsweredAt()
                    );
                    return new QuestionTimingStatsDto(
                            answer.getQuestion().getId(),
                            answer.getQuestion().getType(),
                            answer.getQuestion().getDifficulty(),
                            questionTime,
                            answer.getIsCorrect(),
                            attempt.getStartedAt(),
                            answer.getAnsweredAt()
                    );
                })
                .collect(Collectors.toList());

        return new AttemptStatsDto(
                attemptId,
                totalTime,
                averageTimePerQuestion,
                questionsAnswered,
                Math.toIntExact(correctAnswers),
                accuracyPercentage,
                completionPercentage,
                questionTimings,
                attempt.getStartedAt(),
                attempt.getCompletedAt()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttemptDto> getAttemptsForQuizOwner(String username, UUID quizId) {
        // Verify quiz exists and is owned by the current user
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        if (quiz.getCreator() == null || !quiz.getCreator().getId().equals(user.getId())) {
            throw new AccessDeniedException("You do not own quiz " + quizId);
        }

        return attemptRepository.findByQuiz_Id(quizId).stream()
                .map(attemptMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public AttemptStatsDto getAttemptStatsForQuizOwner(String username, UUID quizId, UUID attemptId) {
        // Verify quiz exists and is owned by the current user
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        if (quiz.getCreator() == null || !quiz.getCreator().getId().equals(user.getId())) {
            throw new AccessDeniedException("You do not own quiz " + quizId);
        }

        // Ensure attempt belongs to quiz. If not, return 404 for safety.
        Attempt attempt = attemptRepository.findByIdWithAnswersAndQuestion(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));

        if (!attempt.getQuiz().getId().equals(quizId)) {
            throw new ResourceNotFoundException("Attempt does not belong to quiz " + quizId);
        }

        return getAttemptStats(attemptId, username);
    }

    @Override
    @Transactional
    public AttemptDto pauseAttempt(String username, UUID attemptId) {
        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        enforceOwnership(attempt, username);

        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("Can only pause attempts that are in progress");
        }

        attempt.setStatus(AttemptStatus.PAUSED);
        Attempt saved = attemptRepository.save(attempt);
        return attemptMapper.toDto(saved);
    }

    @Override
    @Transactional
    public AttemptDto resumeAttempt(String username, UUID attemptId) {
        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        enforceOwnership(attempt, username);

        if (attempt.getStatus() != AttemptStatus.PAUSED) {
            throw new IllegalStateException("Can only resume paused attempts");
        }

        attempt.setStatus(AttemptStatus.IN_PROGRESS);
        Attempt saved = attemptRepository.save(attempt);
        return attemptMapper.toDto(saved);
    }

    @Override
    @Transactional
    public void deleteAttempt(String username, UUID attemptId) {
        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        enforceOwnership(attempt, username);

        // Delete all answers associated with this attempt first
        answerRepository.deleteByAttemptId(attemptId);
        
        // Then delete the attempt itself
        attemptRepository.delete(attempt);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttemptDto> getAttemptsByDateRange(LocalDate start, LocalDate end) {
        Instant startInstant = start.atStartOfDay().atZone(java.time.ZoneOffset.UTC).toInstant();
        Instant endInstant = end.plusDays(1).atStartOfDay().atZone(java.time.ZoneOffset.UTC).toInstant();

        return attemptRepository.findByStartedAtBetween(startInstant, endInstant)
                .stream()
                .map(attemptMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void flagSuspiciousActivity(UUID attemptId, String reason) {
        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));

        // Log suspicious activity - in a real implementation, this would write to an audit log
        System.err.println("🚨 SUSPICIOUS ACTIVITY FLAGGED: " +
                "Attempt " + attemptId +
                " by user " + attempt.getUser().getUsername() +
                " - Reason: " + reason);

        // You could also update the attempt status or add a flag
        // attempt.setFlagged(true);
        // attempt.setFlagReason(reason);
        // attemptRepository.save(attempt);
    }

    @Override
    @Transactional(readOnly = true)
    public AttemptReviewDto getAttemptReview(String username, UUID attemptId,
                                             boolean includeUserAnswers,
                                             boolean includeCorrectAnswers,
                                             boolean includeQuestionContext) {
        // Fetch current user
        User currentUser = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        // Fetch attempt with answers and questions
        Attempt attempt = attemptRepository.findByIdWithAnswersAndQuestion(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));

        // Enforce ownership (by userId)
        enforceOwnershipById(attempt, currentUser.getId());

        // Enforce COMPLETED status
        if (attempt.getStatus() != AttemptStatus.COMPLETED) {
            throw new AttemptNotCompletedException(attemptId);
        }

        // Compute totals
        long correctCount = attempt.getAnswers().stream()
                .filter(ans -> Boolean.TRUE.equals(ans.getIsCorrect()))
                .count();
        
        // Calculate totalScore from actual answer scores (not from stored value)
        double totalScore = attempt.getAnswers().stream()
                .mapToDouble(ans -> ans.getScore() != null ? ans.getScore() : 0.0)
                .sum();
        
        int totalQuestions = (int) questionRepository.countByQuizId_Id(attempt.getQuiz().getId());

        // Build answer review DTOs (sorted by answeredAt for stable ordering)
        List<AnswerReviewDto> answerReviews = attempt.getAnswers().stream()
                .sorted(Comparator.comparing(Answer::getAnsweredAt))
                .map(answer -> buildAnswerReviewDto(
                        answer,
                        includeUserAnswers,
                        includeCorrectAnswers,
                        includeQuestionContext
                ))
                .collect(Collectors.toList());

        return new AttemptReviewDto(
                attempt.getId(),
                attempt.getQuiz().getId(),
                attempt.getUser().getId(),
                attempt.getStartedAt(),
                attempt.getCompletedAt(),
                totalScore,
                (int) correctCount,
                totalQuestions,
                answerReviews
        );
    }

    @Override
    @Transactional(readOnly = true)
    public AttemptReviewDto getAttemptAnswerKey(String username, UUID attemptId) {
        // Answer key = correct answers + question context, but no user responses
        return getAttemptReview(username, attemptId, false, true, true);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AttemptSummaryDto> getAttemptsSummary(
            String username,
            Pageable pageable,
            UUID quizId,
            UUID userId,
            AttemptStatus status
    ) {
        // Fetch current user
        User currentUser = userRepository.findByUsernameWithRolesAndPermissions(username)
                .or(() -> userRepository.findByEmailWithRolesAndPermissions(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        // Authorization: if userId is specified and different from current user, check admin permission
        if (userId != null && !userId.equals(currentUser.getId())) {
            boolean canViewOthers = appPermissionEvaluator.hasPermission(currentUser, PermissionName.ATTEMPT_READ_ALL)
                    || appPermissionEvaluator.hasPermission(currentUser, PermissionName.SYSTEM_ADMIN);
            if (!canViewOthers) {
                throw new AccessDeniedException("You do not have permission to view other users' attempts");
            }
        }

        // If userId not specified, default to current user
        UUID effectiveUserId = userId != null ? userId : currentUser.getId();

        // Fetch attempts with quiz eagerly loaded (single query with JOIN FETCH)
        Page<Attempt> attempts = attemptRepository.findAllWithQuizAndAnswersEager(
                quizId,
                effectiveUserId,
                status,
                pageable
        );

        // Batch fetch answers for all attempts in this page (single query, avoids N+1)
        List<UUID> attemptIds = attempts.getContent().stream()
                .map(Attempt::getId)
                .toList();
        
        if (!attemptIds.isEmpty()) {
            // This populates the persistence context with answers,
            // so attempt.getAnswers() won't trigger additional queries
            attemptRepository.batchFetchAnswersForAttempts(attemptIds);
        }

        // Batch fetch question counts for all unique quizzes in this page (avoid N+1)
        List<UUID> quizIds = attempts.getContent().stream()
                .map(attempt -> attempt.getQuiz().getId())
                .distinct()
                .toList();

        Map<UUID, Integer> questionCountsByQuizId = new HashMap<>();
        if (!quizIds.isEmpty()) {
            List<Object[]> countResults = questionRepository.countQuestionsForQuizzes(quizIds);
            for (Object[] row : countResults) {
                UUID qId = (UUID) row[0];
                Long count = (Long) row[1];
                questionCountsByQuizId.put(qId, count.intValue());
            }
        }

        // Map to summary DTOs
        return attempts.map(attempt -> {
            // Build quiz summary
            Quiz quiz = attempt.getQuiz();
            int questionCount = questionCountsByQuizId.getOrDefault(quiz.getId(), 0);
            QuizSummaryDto quizSummary = attemptMapper.toQuizSummaryDto(quiz, questionCount);

            // Build stats (only for completed attempts)
            AttemptStatsDto stats = null;
            if (attempt.getStatus() == AttemptStatus.COMPLETED && attempt.getCompletedAt() != null) {
                stats = buildLightweightStats(attempt, questionCount);
            }

            return attemptMapper.toSummaryDto(attempt, quizSummary, stats);
        });
    }

    /**
     * Build lightweight stats for attempt summary (without detailed question timings)
     */
    private AttemptStatsDto buildLightweightStats(Attempt attempt, int totalQuestions) {
        Duration totalTime = Duration.between(attempt.getStartedAt(), attempt.getCompletedAt());
        
        int questionsAnswered = attempt.getAnswers().size();
        long correctAnswers = attempt.getAnswers().stream()
                .filter(answer -> Boolean.TRUE.equals(answer.getIsCorrect()))
                .count();

        double accuracyPercentage = questionsAnswered > 0
                ? (double) correctAnswers / questionsAnswered * 100.0
                : 0.0;
        double completionPercentage = totalQuestions > 0
                ? (double) questionsAnswered / totalQuestions * 100.0
                : 0.0;

        Duration averageTimePerQuestion = questionsAnswered > 0
                ? totalTime.dividedBy(questionsAnswered)
                : Duration.ZERO;

        // No detailed question timings for summary view (keeps response light)
        return new AttemptStatsDto(
                attempt.getId(),
                totalTime,
                averageTimePerQuestion,
                questionsAnswered,
                Math.toIntExact(correctAnswers),
                accuracyPercentage,
                completionPercentage,
                null,  // questionTimings - omitted for lightweight summary
                attempt.getStartedAt(),
                attempt.getCompletedAt()
        );
    }

    /**
     * Build an AnswerReviewDto from an Answer entity with configurable inclusion flags.
     */
    private AnswerReviewDto buildAnswerReviewDto(Answer answer,
                                                  boolean includeUserAnswers,
                                                  boolean includeCorrectAnswers,
                                                  boolean includeQuestionContext) {
        Question question = answer.getQuestion();

        // Parse user response if requested
        JsonNode userResponse = null;
        if (includeUserAnswers && answer.getResponse() != null) {
            try {
                userResponse = objectMapper.readTree(answer.getResponse());
            } catch (JsonProcessingException e) {
                // Log error but don't fail entire response
                userResponse = objectMapper.createObjectNode()
                        .put("error", "Failed to parse user response");
            }
        }

        // Extract correct answer if requested
        JsonNode correctAnswer = null;
        if (includeCorrectAnswers) {
            try {
                correctAnswer = correctAnswerExtractor.extractCorrectAnswer(question);
            } catch (IllegalArgumentException e) {
                // Log error but don't fail entire response
                correctAnswer = objectMapper.createObjectNode()
                        .put("error", "Failed to extract correct answer: " + e.getMessage());
            }
        }

        // Build safe question content if requested (deterministic for review consistency)
        JsonNode questionSafeContent = null;
        if (includeQuestionContext) {
            try {
                questionSafeContent = safeQuestionContentBuilder.buildSafeContent(
                        question.getType(),
                        question.getContent(),
                        true  // deterministic=true for review (no shuffling, ensures cacheability and consistent UX)
                );
            } catch (Exception e) {
                // Log error but don't fail entire response
                questionSafeContent = objectMapper.createObjectNode()
                        .put("error", "Failed to build safe content: " + e.getMessage());
            }
        }

        return new AnswerReviewDto(
                question.getId(),
                question.getType(),
                includeQuestionContext ? question.getQuestionText() : null,
                includeQuestionContext ? question.getHint() : null,
                includeQuestionContext ? question.getAttachmentUrl() : null,
                null,
                includeCorrectAnswers ? question.getExplanation() : null,
                questionSafeContent,
                userResponse,
                correctAnswer,
                answer.getIsCorrect(),
                answer.getScore(),
                answer.getAnsweredAt()
        );
    }

    /**
     * Enforce ownership by comparing user IDs.
     * Using userId instead of username to handle potential username changes.
     * This is the preferred method for new code.
     */
    private void enforceOwnershipById(Attempt attempt, UUID userId) {
        if (!attempt.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("You do not have access to attempt " + attempt.getId());
        }
    }

    /**
     * @deprecated Use enforceOwnershipById(Attempt, UUID) instead.
     * This method will be removed once all services are migrated to userId checks.
     */
    @Deprecated
    private void enforceOwnership(Attempt attempt, String username) {
        if (!attempt.getUser().getUsername().equals(username)) {
            throw new AccessDeniedException("You do not have access to attempt " + attempt.getId());
        }
    }

    /**
     * Check if the user has permission to access quiz analytics (results/leaderboard).
     * Allows access if:
     * - Quiz is public (PUBLIC visibility and PUBLISHED status)
     * - User is the quiz owner
     * - User has moderation/admin permissions
     */
    private void checkQuizAccessPermission(Quiz quiz, Authentication authentication) {
        // Allow access to public, published quizzes for anyone
        if (quiz.getVisibility() == Visibility.PUBLIC && quiz.getStatus() == QuizStatus.PUBLISHED) {
            return;
        }

        // For non-public quizzes, require authentication
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ForbiddenException("Access denied: quiz is not public and authentication required");
        }

        // Get the authenticated user
        User user = userRepository.findByUsername(authentication.getName())
                .or(() -> userRepository.findByEmail(authentication.getName()))
                .orElse(null);

        if (user == null) {
            throw new ForbiddenException("Access denied: user not found");
        }

        // Check if user is the quiz owner
        boolean isOwner = quiz.getCreator() != null && user.getId().equals(quiz.getCreator().getId());
        if (isOwner) {
            return;
        }

        // Check if user has moderation/admin permissions
        boolean hasModerationPermissions = appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE)
                || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN);
        if (hasModerationPermissions) {
            return;
        }

        // If none of the above conditions are met, deny access
        throw new ForbiddenException("Access denied: insufficient permissions to view quiz analytics");
    }

}
