package uk.gegc.quizmaker.service.attempt.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.attempt.*;
import uk.gegc.quizmaker.dto.question.QuestionDto;
import uk.gegc.quizmaker.dto.result.QuestionStatsDto;
import uk.gegc.quizmaker.dto.result.QuizResultSummaryDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.mapper.AnswerMapper;
import uk.gegc.quizmaker.mapper.AttemptMapper;
import uk.gegc.quizmaker.mapper.QuestionMapper;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.attempt.AttemptMode;
import uk.gegc.quizmaker.model.attempt.AttemptStatus;
import uk.gegc.quizmaker.model.question.Answer;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.attempt.AttemptRepository;
import uk.gegc.quizmaker.repository.question.AnswerRepository;
import uk.gegc.quizmaker.repository.question.QuestionRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.attempt.AttemptService;
import uk.gegc.quizmaker.service.question.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.service.question.handler.QuestionHandler;
import uk.gegc.quizmaker.service.scoring.ScoringService;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    @Override
    public AttemptDto startAttempt(String username, UUID quizId, AttemptMode mode) {
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

        Attempt saved = attemptRepository.save(attempt);
        return attemptMapper.toDto(saved);
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

        // prevent duplicate answers
        boolean already = attempt.getAnswers().stream()
                .map(a -> a.getQuestion().getId())
                .anyMatch(id -> id.equals(question.getId()));
        if (already) {
            throw new IllegalStateException(
                    "Already answered question " + question.getId() + " in this attempt");
        }

        var handler = handlerFactory.getHandler(question.getType());
        Answer answer = handler.handle(attempt, question, request);
        answer = answerRepository.save(answer);

        var baseDto = answerMapper.toDto(answer);
        QuestionDto nextQuestion = null;
        if (attempt.getMode() == AttemptMode.ONE_BY_ONE) {
            Set<UUID> done = attempt.getAnswers().stream()
                    .map(a -> a.getQuestion().getId())
                    .collect(Collectors.toSet());
            nextQuestion = attempt.getQuiz().getQuestions().stream()
                    .filter(q -> !done.contains(q.getId()))
                    .findFirst()
                    .map(QuestionMapper::toDto)
                    .orElse(null);
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

        Object[] agg = attemptRepository.getAttemptAggregateData(quizId);
        long attemptsCount = ((Number) agg[0]).longValue();
        double averageScore = agg[1] != null ? ((Number) agg[1]).doubleValue() : 0.0;
        double bestScore    = agg[2] != null ? ((Number) agg[2]).doubleValue() : 0.0;
        double worstScore   = agg[3] != null ? ((Number) agg[3]).doubleValue() : 0.0;

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

    // -----------------
    // helpers
    // -----------------
    private void enforceOwnership(Attempt attempt, String username) {
        if (!attempt.getUser().getUsername().equals(username)) {
            throw new AccessDeniedException("You do not have access to attempt " + attempt.getId());
        }
    }

}
