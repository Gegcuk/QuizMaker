package uk.gegc.quizmaker.mapper;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.attempt.AnswerSubmissionDto;
import uk.gegc.quizmaker.model.question.Answer;


@Component
public class AnswerMapper {
    public AnswerSubmissionDto toDto(Answer answer) {
        return new AnswerSubmissionDto(
                answer.getId(),
                answer.getQuestion().getId(),
                answer.getIsCorrect(),
                answer.getScore(),
                answer.getAnsweredAt(),
                null
        );
    }
}
