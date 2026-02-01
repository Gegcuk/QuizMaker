package uk.gegc.quizmaker.features.repetition.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.repetition.domain.model.ContentKey;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionContentType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@DisplayName("QuestionAttemptContentResolver Tests")
class QuestionAttemptContentResolverTest extends BaseUnitTest {

    @Mock private Answer answer;
    @Mock private Question question;

    private QuestionAttemptContentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new QuestionAttemptContentResolver();
    }

    @Test
    @DisplayName("Should return ContentKey(QUESTION, answer.question.id)")
    void shouldReturnContentKey() {
        UUID questionId = UUID.randomUUID();
        when(answer.getQuestion()).thenReturn(question);
        when(question.getId()).thenReturn(questionId);

        ContentKey result = resolver.resolve(answer);

        assertEquals(RepetitionContentType.QUESTION, result.type());
        assertEquals(questionId, result.id());
    }
}