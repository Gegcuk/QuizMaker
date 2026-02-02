package uk.gegc.quizmaker.features.repetition.application;

import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.repetition.domain.model.ContentKey;

public interface AttemptContentResolver {
    ContentKey resolve(Answer answer);
}
