package uk.gegc.quizmaker.features.repetition.application.impl;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.repetition.application.AttemptContentResolver;
import uk.gegc.quizmaker.features.repetition.domain.model.ContentKey;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionContentType;

@Component
public class QuestionAttemptContentResolver implements AttemptContentResolver {
    @Override
    public ContentKey resolve(Answer answer) {
        return new ContentKey(RepetitionContentType.QUESTION, answer.getQuestion().getId());
    }
}
