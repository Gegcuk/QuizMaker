package uk.gegc.quizmaker.features.attempt.infra.mapping;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.infra.mapping.AnswerMapper;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;

import java.util.Comparator;
import java.util.List;


@Component
@RequiredArgsConstructor
public class AttemptMapper {

    private final AnswerMapper answerMapper;

    public AttemptDto toDto(Attempt attempt) {
        return new AttemptDto(
                attempt.getId(),
                attempt.getQuiz().getId(),
                attempt.getUser().getId(),
                attempt.getStartedAt(),
                attempt.getStatus(),
                attempt.getMode()
        );
    }

    public AttemptDetailsDto toDetailDto(Attempt attempt) {
        List<AnswerSubmissionDto> answers = attempt.getAnswers().stream()
                .sorted((a1, a2) -> a1.getAnsweredAt().compareTo(a2.getAnsweredAt()))
                .map(answerMapper::toDto)
                .toList();

        return new AttemptDetailsDto(
                attempt.getId(),
                attempt.getQuiz().getId(),
                attempt.getUser().getId(),
                attempt.getStartedAt(),
                attempt.getCompletedAt(),
                attempt.getStatus(),
                attempt.getMode(),
                answers
        );
    }

    public AttemptResultDto toResultDto(
            Attempt attempt,
            long correctCount,
            int totalQuestions
    ) {
        List<AnswerSubmissionDto> answers = attempt.getAnswers().stream()
                .sorted(Comparator.comparing(Answer::getAnsweredAt))
                .map(answerMapper::toDto)
                .toList();

        return new AttemptResultDto(
                attempt.getId(),
                attempt.getQuiz().getId(),
                attempt.getUser().getId(),
                attempt.getStartedAt(),
                attempt.getCompletedAt(),
                attempt.getTotalScore(),
                Math.toIntExact(correctCount),
                totalQuestions,
                answers
        );
    }

    /**
     * Map Quiz to QuizSummaryDto for embedded display
     */
    public QuizSummaryDto toQuizSummaryDto(Quiz quiz, int questionCount) {
        return new QuizSummaryDto(
                quiz.getId(),
                quiz.getTitle(),
                questionCount,
                quiz.getCategory() != null ? quiz.getCategory().getId() : null,
                quiz.getVisibility() == Visibility.PUBLIC
        );
    }

    /**
     * Map Attempt to AttemptSummaryDto with embedded quiz and stats
     */
    public AttemptSummaryDto toSummaryDto(
            Attempt attempt,
            QuizSummaryDto quizSummary,
            AttemptStatsDto stats
    ) {
        return new AttemptSummaryDto(
                attempt.getId(),
                attempt.getQuiz().getId(),
                attempt.getUser().getId(),
                attempt.getStartedAt(),
                attempt.getCompletedAt(),
                attempt.getStatus(),
                attempt.getMode(),
                attempt.getTotalScore(),
                quizSummary,
                stats
        );
    }
}

