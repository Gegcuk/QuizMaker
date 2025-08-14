package uk.gegc.quizmaker.features.attempt.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.attempt.*;
import uk.gegc.quizmaker.features.attempt.api.dto.QuestionForAttemptDto;
import uk.gegc.quizmaker.dto.result.LeaderboardEntryDto;
import uk.gegc.quizmaker.dto.result.QuestionStatsDto;
import uk.gegc.quizmaker.dto.result.QuizResultSummaryDto;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.ShareLinkRepository;
import uk.gegc.quizmaker.features.question.infra.mapping.AnswerMapper;
import uk.gegc.quizmaker.features.attempt.infra.mapping.AttemptMapper;
import uk.gegc.quizmaker.features.question.infra.mapping.SafeQuestionMapper;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.ShareLink;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.question.domain.repository.AnswerRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.service.scoring.ScoringService;

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

    @Override
    public StartAttemptResponse startAttempt(String username, UUID quizId, AttemptMode mode) {
        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        Attempt attempt = new Attempt();
        attempt.setUser(user);
        attempt.setQuiz(quiz);
        attempt.setMode(mode);
        attempt.setStatus(AttemptStatus.IN_PROGRESS);

        Attempt saved = attemptRepository.saveAndFlush(attempt);

        int totalQuestions = quiz.getQuestions().size();
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

        int totalQuestions = quiz.getQuestions().size();
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
        UUID filterUserId = (userId != null)
                ? userId
                : userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"))
                .getId();

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

        // Get all questions for the quiz and convert to sorted list for consistent ordering
        List<Question> allQuestions = attempt.getQuiz().getQuestions().stream()
                .sorted(Comparator.comparing(Question::getId))
                .collect(Collectors.toList());
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

        // ensure question belongs to quiz
        boolean belongs = attempt.getQuiz().getQuestions().stream()
                .map(Question::getId)
                .anyMatch(id -> id.equals(question.getId()));
        if (!belongs) {
            throw new ResourceNotFoundException(
                    "Question " + question.getId() + " is not part of Quiz " +
                            attempt.getQuiz().getId());
        }

        // For ONE_BY_ONE mode, enforce sequential question submission
        if (attempt.getMode() == AttemptMode.ONE_BY_ONE) {
            // Get all questions for the quiz and convert to sorted list for consistent ordering
            List<Question> allQuestions = attempt.getQuiz().getQuestions().stream()
                    .sorted(Comparator.comparing(Question::getId))
                    .collect(Collectors.toList());
            
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
            // Get all questions for the quiz and convert to sorted list for consistent ordering
            List<Question> allQuestions = attempt.getQuiz().getQuestions().stream()
                    .sorted(Comparator.comparing(Question::getId))
                    .collect(Collectors.toList());
            
            // Count answers using the same approach as getCurrentQuestion to ensure consistency
            long answeredCount = answerRepository.countByAttemptId(attemptId);
            
            // Check if there are more questions to answer
            if (answeredCount < allQuestions.size()) {
                Question nextQ = allQuestions.get((int) answeredCount);
                nextQuestion = safeQuestionMapper.toSafeDto(nextQ);
            }
        }

        return new AnswerSubmissionDto(
                baseDto.answerId(),
                baseDto.questionId(),
                baseDto.isCorrect(),
                baseDto.score(),
                baseDto.answeredAt(),
                nextQuestion
        );
    }

    @Override
    @Transactional
    public List<AnswerSubmissionDto> submitBatch(String username,
                                                 UUID attemptId,
                                                 BatchAnswerSubmissionRequest request) {
        Attempt attempt = attemptRepository.findByIdWithAllRelations(attemptId)
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
        Attempt attempt = attemptRepository.findByIdWithAllRelations(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        enforceOwnership(attempt, username);

        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot complete attempt with status " + attempt.getStatus());
        }

        double totalScore = scoringService.computeAndPersistScore(attempt);
        long correctCount = scoringService.countCorrect(attempt);
        int totalQ = attempt.getQuiz().getQuestions().size();

        attempt.setStatus(AttemptStatus.COMPLETED);
        attempt.setCompletedAt(Instant.now());

        return attemptMapper.toResultDto(attempt, correctCount, totalQ);
    }

    @Override
    @Transactional(readOnly = true)
    public QuizResultSummaryDto getQuizResultSummary(UUID quizId) {

        Quiz quiz = quizRepository.findByIdWithQuestions(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        List<Object[]> rows = attemptRepository.getAttemptAggregateData(quizId);
        Object[] agg = rows.isEmpty()
                ? new Object[]{0L, null, null, null}
                : rows.get(0);

        long attemptsCount = ((Number) agg[0]).longValue();
        double averageScore = agg[1] != null ? ((Number) agg[1]).doubleValue() : 0.0;
        double bestScore = agg[2] != null ? ((Number) agg[2]).doubleValue() : 0.0;
        double worstScore = agg[3] != null ? ((Number) agg[3]).doubleValue() : 0.0;

        List<Attempt> completed = attemptRepository.findByQuiz_Id(quizId).stream()
                .filter(a -> a.getStatus() == AttemptStatus.COMPLETED)
                .toList();

        long passing = completed.stream()
                .filter(a -> {
                    long correct = a.getAnswers().stream()
                            .filter(ans -> Boolean.TRUE.equals(ans.getIsCorrect()))
                            .count();
                    int totalQ = quiz.getQuestions().size();
                    return totalQ > 0 && ((double) correct / totalQ) >= 0.5;
                })
                .count();
        double passRate = attemptsCount > 0
                ? ((double) passing / attemptsCount) * 100.0
                : 0.0;

        List<QuestionStatsDto> questionStats = quiz.getQuestions().stream()
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
    public List<LeaderboardEntryDto> getQuizLeaderboard(UUID quizId, int top) {
        if (top <= 0) {
            return List.of();
        }

        quizRepository.findById(quizId).orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

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
    public List<QuestionForAttemptDto> getShuffledQuestions(UUID quizId, String username) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        List<Question> questions = new ArrayList<>(quiz.getQuestions());
        Collections.shuffle(questions);

        return safeQuestionMapper.toSafeDtoList(questions);
    }

    @Override
    @Transactional(readOnly = true)
    public AttemptStatsDto getAttemptStats(UUID attemptId) {
        Attempt attempt = attemptRepository.findByIdWithAnswersAndQuestion(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));

        Duration totalTime = attempt.getCompletedAt() != null && attempt.getStartedAt() != null
                ? Duration.between(attempt.getStartedAt(), attempt.getCompletedAt())
                : Duration.ZERO;

        int questionsAnswered = attempt.getAnswers().size();
        int totalQuestions = attempt.getQuiz().getQuestions().size();
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

        return getAttemptStats(attemptId);
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

    private void enforceOwnership(Attempt attempt, String username) {
        if (!attempt.getUser().getUsername().equals(username)) {
            throw new AccessDeniedException("You do not have access to attempt " + attempt.getId());
        }
    }

}
