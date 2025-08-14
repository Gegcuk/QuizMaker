package uk.gegc.quizmaker.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.attempt.AnswerSubmissionDto;
import uk.gegc.quizmaker.dto.attempt.AttemptDetailsDto;
import uk.gegc.quizmaker.dto.attempt.AttemptDto;
import uk.gegc.quizmaker.dto.attempt.AttemptResultDto;
import uk.gegc.quizmaker.features.question.infra.mapping.AnswerMapper;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.features.question.domain.model.Answer;

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
}

