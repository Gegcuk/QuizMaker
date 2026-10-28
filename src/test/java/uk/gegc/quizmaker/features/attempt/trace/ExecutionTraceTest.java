package uk.gegc.quizmaker.features.attempt.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.api.AttemptController;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.trace.TraceFixtures.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException.Reason.*;

class ExecutionTraceTest {
    static Event event(String key) {
        return new Event(1, id(key), Type.ANSWER_SUBMITTED, Actor.PARTICIPANT,
                new Data(id(key + "-command"), null, null, null, ReasonCode.ACCEPTED));
    }

    static void rejected(Runnable action, TraceException.Reason expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TraceException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(expected);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).doesNotContain("private", "SELECT", "token");
        });
    }

    @ParameterizedTest
    @EnumSource(Type.class)
    void everyExplicitV1EventRoundTripsWithoutInferringBusinessEffects(Type type) {
        var event = new Event(1, id("event"), type, Actor.SYSTEM, event("base").data());
        assertThat(ExecutionTrace.parse(1, Type.class, type.name())).isEqualTo(type);
        var stored = new Stored(new Scope(id("attempt"), id("policy"), id("form")), 1, NOW.plusNanos(999), event);
        assertThat(stored.recordedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"private-answer-token", "answer_submitted", " ANSWER_SUBMITTED", "ANSWER_SUBMITTED ", "", "ATTEMPT_CANCELLED"})
    void unknownTypeCannotSmuggleContentThroughAnEvent(String value) {
        rejected(() -> ExecutionTrace.parse(1, Type.class, value), UNKNOWN_VALUE);
    }

    @Test
    void nullUnknownVersionOversizedAndForeignVocabulariesFailClosed() {
        rejected(() -> ExecutionTrace.parse(2, Type.class, "ANSWER_SUBMITTED"), UNSUPPORTED_VERSION);
        rejected(() -> ExecutionTrace.parse(1, Type.class, "private".repeat(100_000)), UNKNOWN_VALUE);
        rejected(() -> ExecutionTrace.parse(1, Type.class, null), UNKNOWN_VALUE);
        rejected(() -> ExecutionTrace.parse(1, null, "ACCEPTED"), INVALID_INPUT);
        rejected(() -> ExecutionTrace.parse(1, java.time.DayOfWeek.class, "MONDAY"), INVALID_INPUT);
        rejected(() -> new Event(2, id("x"), Type.ANSWER_LOCKED, Actor.SYSTEM, event("x").data()), UNSUPPORTED_VERSION);
        rejected(() -> new Event(1, null, Type.ANSWER_LOCKED, Actor.SYSTEM, event("x").data()), INVALID_INPUT);
        rejected(() -> new Event(1, id("x"), null, Actor.SYSTEM, event("x").data()), INVALID_INPUT);
        rejected(() -> new Event(1, id("x"), Type.ANSWER_LOCKED, null, event("x").data()), INVALID_INPUT);
        rejected(() -> new Event(1, id("x"), Type.ANSWER_LOCKED, Actor.SYSTEM, null), INVALID_INPUT);
        rejected(() -> new Scope(null, id("p"), id("f")), INVALID_INPUT);
        rejected(() -> new Scope(id("a"), null, id("f")), INVALID_INPUT);
        rejected(() -> new Scope(id("a"), id("p"), null), INVALID_INPUT);
        rejected(() -> new Data(null, null, null, null, null), INVALID_INPUT);
        var created = new uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State(1,
                uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.Execution.CREATED,
                uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.AnswerCollection.NOT_STARTED);
        rejected(() -> new Data(null, null, created, null, ReasonCode.ACCEPTED), INVALID_INPUT);
        rejected(() -> new Data(null, null, null, created, ReasonCode.ACCEPTED), INVALID_INPUT);
        var scope = new Scope(id("a"), id("p"), id("f"));
        rejected(() -> new Stored(scope, 0, NOW, event("x")), INVALID_INPUT);
        rejected(() -> new Stored(scope, 1, null, event("x")), INVALID_INPUT);
        rejected(() -> new Stored(scope, 1, java.time.Instant.MIN, event("x")), INVALID_INPUT);
        rejected(() -> new Stored(scope, 1, java.time.Instant.MAX, event("x")), INVALID_INPUT);
        rejected(() -> new Appended(null, false), INVALID_INPUT);
    }

    @Test
    void schemaHasNoArbitraryPayloadAndUnknownFieldsAreRejectedAtStrictParsingBoundary() throws Exception {
        assertThat(Arrays.stream(Data.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("commandId", "itemId", "from", "to", "reason");
        assertThat(Arrays.stream(Data.class.getRecordComponents()).map(c -> c.getType()))
                .noneMatch(c -> c == String.class || java.util.Map.class.isAssignableFrom(c));
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(event("json"));
        assertThat(mapper.readValue(json, Event.class)).isEqualTo(event("json"));
        for (String field : Set.of("answer", "correctAnswer", "capability", "prompt", "rationale", "email", "userId")) {
            String malicious = json.substring(0, json.length() - 1) + ",\"" + field + "\":\"private-secret\"}";
            assertThatThrownBy(() -> mapper.readValue(malicious, Event.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        }
        assertThat(event("json").toString()).doesNotContain(id("json").toString());
        assertThat(event("json").data().toString()).isEqualTo("TraceData[redacted]");
    }

    @Test
    void boundedPagesAndDarkLegacyWiringRemainExplicit() {
        ExecutionTrace.page(0, 1); ExecutionTrace.page(Long.MAX_VALUE, 100);
        rejected(() -> ExecutionTrace.page(-1, 1), INVALID_INPUT);
        rejected(() -> ExecutionTrace.page(0, 0), INVALID_INPUT);
        rejected(() -> ExecutionTrace.page(0, 101), INVALID_INPUT);
        for (var type : Set.of(AttemptController.class, AttemptServiceImpl.class)) {
            assertThat(Arrays.stream(type.getDeclaredFields()).map(f -> f.getType().getName()))
                    .noneMatch(name -> name.contains("ExecutionTrace") || name.contains(".trace."));
        }
    }
}
