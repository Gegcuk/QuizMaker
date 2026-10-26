package uk.gegc.quizmaker.features.attempt.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.api.AttemptController;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.lifecycle.AttemptLifecycleTest.rejected;

class LifecyclePurityTest {
    @Test
    void domainContractHasNoFrameworkTransportPersistenceOrExternalSystemDependency() throws Exception {
        var pending = new ArrayDeque<>(List.of(AttemptLifecycle.class, CompletionFacts.class, LifecycleException.class));
        while (!pending.isEmpty()) {
            var type = pending.remove();
            pending.addAll(List.of(type.getDeclaredClasses()));
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1))
                        .as(type.getName()).doesNotContain("org/springframework/", "jakarta/persistence/",
                                "javax/persistence/", "com/fasterxml/jackson/", "java/sql/", "java/net/", "java/io/",
                                "java/time/", "java/util/Random", "SecurityContext", "AttemptStatus", "AttemptMode",
                                "/application/", "/infra/", "/api/");
            }
        }
        for (var consumer : List.of(AttemptController.class, AttemptServiceImpl.class)) {
            assertThat(Arrays.stream(consumer.getDeclaredFields()).map(field -> field.getType().getPackageName()))
                    .noneMatch(name -> name.contains(".lifecycle"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "submitted", " SUBMITTED", "SUBMITTED ", "2", "PAUSED", "EXPIRED",
            "CANCELLED", "private-response-secret"})
    void unknownExecutionNamesAreRejectedWithoutEchoingInput(String value) {
        rejected(() -> AttemptLifecycle.parseValue(1, Execution.class, value), UNKNOWN_VALUE);
    }

    @Test
    void exactKnownValuesRoundTripAcrossEveryV1Vocabulary() {
        for (var owner : List.of(AttemptLifecycle.class, CompletionFacts.class)) {
            for (var type : owner.getDeclaredClasses()) {
                if (type.isEnum()) roundTrip(type.asSubclass(Enum.class));
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void roundTrip(Class<? extends Enum> type) {
        for (var value : type.getEnumConstants()) {
            assertThat(AttemptLifecycle.parseValue(1, type, value.name())).isSameAs(value);
        }
        rejected(() -> AttemptLifecycle.parseValue(1, type, "private-unknown-value"), UNKNOWN_VALUE);
    }

    @Test
    void parserRejectsNullForeignVocabulariesAndOversizedNamesWithBoundedErrors() {
        rejected(() -> AttemptLifecycle.parseValue(1, Execution.class, null), INVALID_INPUT);
        rejected(() -> AttemptLifecycle.<Execution>parseValue(1, null, "CREATED"), INVALID_INPUT);
        rejected(() -> AttemptLifecycle.parseValue(1, AttemptStatus.class, "IN_PROGRESS"), INVALID_INPUT);
        rejected(() -> AttemptLifecycle.parseValue(1, Event.class, "private-response-".repeat(100_000)), UNKNOWN_VALUE);
        @SuppressWarnings({"rawtypes", "unchecked"}) Class<Execution> invalid = (Class) String.class;
        rejected(() -> AttemptLifecycle.parseValue(1, invalid, "CREATED"), INVALID_INPUT);
    }
}
