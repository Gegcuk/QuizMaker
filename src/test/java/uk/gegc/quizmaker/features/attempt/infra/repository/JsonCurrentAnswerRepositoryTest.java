package uk.gegc.quizmaker.features.attempt.infra.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.AcceptedAnswerEvidenceException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JsonCurrentAnswerRepositoryTest {
    private final JsonCurrentAnswerRepository reader = new JsonCurrentAnswerRepository(new ObjectMapper());
    private final UUID attempt = UUID.randomUUID();
    private static final String ANSWER = "10000000-0000-4000-8000-000000000001";
    private static final String QUESTION = "20000000-0000-4000-8000-000000000001";

    @Test void emptyIsInitialized() {
        var result = reader.readCurrent(attempt, "{\"version\":1,\"answers\":{}}");
        assertThat(result).isEmpty();
    }

    @Test void readsStoredNullableScoreAndExactFractionalTimeWithoutEntities() {
        var result = reader.readCurrent(attempt, projection(fact("null", "1767323045.123456789")));
        assertThat(result).singleElement().satisfies(answer -> {
            assertThat(answer.id()).isEqualTo(UUID.fromString(ANSWER));
            assertThat(answer.questionId()).isEqualTo(UUID.fromString(QUESTION));
            assertThat(answer.isCorrect()).isTrue(); assertThat(answer.score()).isNull();
            assertThat(answer.answeredAt()).isEqualTo(Instant.ofEpochSecond(1767323045, 123456789));
        });
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"null", "[]", "{}", "{\"version\":2,\"answers\":{}}",
            "{\"version\":1.0,\"answers\":{}}", "{\"version\":1,\"answers\":null}",
            "{\"version\":1,\"answers\":{},\"extra\":true}",
            "{\"version\":1,\"version\":1,\"answers\":{}}", "{\"version\":1,\"answers\":{}} {}"})
    void incompleteUnknownOrAmbiguousProjectionFailsClosed(String value) {
        assertSafeFailure(value);
    }

    @Test void invalidIdentityMissingFactFieldsDuplicateQuestionsAndNonFiniteScoreFailClosed() {
        assertSafeFailure(projection(fact("1", "0")).replace(ANSWER, "invalid"));
        assertSafeFailure(projection(fact("1", "0")).replace(QUESTION, "ABCD0000" + QUESTION.substring(8)));
        assertSafeFailure(projection(fact("1", "0")).replace("\"isCorrect\":true,", ""));
        assertSafeFailure(projection(fact("1", "0")).replace("\"isCorrect\":true", "\"isCorrect\":1"));
        assertSafeFailure(projection(fact("1e999", "0")));
        assertSafeFailure(projection(fact("1", "0.0000000001")));
        assertSafeFailure(projection(fact("1", "1e999")));
        String sameQuestion = "{\"version\":1,\"answers\":{\""+ANSWER+"\":"+fact("1", "0")
                +",\"10000000-0000-4000-8000-000000000002\":"+fact("1", "0")+"}}";
        assertSafeFailure(sameQuestion);
    }

    @Test void parseErrorsNeverKeepSensitiveCauseOrSuppressedDiagnostics() {
        assertSafeFailure("{\"version\":1,\"answers\":projection-sensitive-sentinel}");
        assertThatThrownBy(() -> reader.readCurrent(null, "{\"version\":1,\"answers\":{}}"))
                .isInstanceOf(AcceptedAnswerEvidenceException.class).hasNoCause();
    }

    private void assertSafeFailure(String value) {
        assertThatThrownBy(() -> reader.readCurrent(attempt, value))
                .isInstanceOfSatisfying(AcceptedAnswerEvidenceException.class, failure -> {
                    assertThat(failure).hasMessage("Accepted answer evidence is unavailable").hasNoCause();
                    assertThat(failure.getSuppressed()).isEmpty();
                });
    }
    private static String projection(String fact) { return "{\"version\":1,\"answers\":{\""+ANSWER+"\":"+fact+"}}"; }
    private static String fact(String score, String epoch) { return "{\"questionId\":\""+QUESTION+"\",\"isCorrect\":true,\"score\":"+score+",\"answeredAtEpochSeconds\":"+epoch+"}"; }
}
