package uk.gegc.quizmaker.features.question.infra.mapping;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionDto;
import uk.gegc.quizmaker.features.question.domain.model.Answer;


@Component
public class AnswerMapper {
    public AnswerSubmissionDto toDto(Answer answer) {
        return new AnswerSubmissionDto(
                answer.getId(),
                answer.getQuestion().getId(),
                answer.getIsCorrect(),
                answer.getScore(),
                answer.getAnsweredAt(),
                null,      // correctAnswer only populated when explicitly requested via submit endpoint
                null       // no next question context in mapper outputs
        );
    }
}
