package uk.gegc.quizmaker.features.attempt.activity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract.*;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException;

import static org.assertj.core.api.Assertions.*;

class ActivityContractPurityTest {
    @Test
    void domainHasNoFrameworkIoClockLookupOrApplicationScorerDependency() throws Exception {
        var pending = new ArrayDeque<>(List.of(ActivityContract.class, ActivityContractException.class));
        while (!pending.isEmpty()) {
            var type = pending.remove();
            pending.addAll(List.of(type.getDeclaredClasses()));
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1)).as(type.getName())
                        .doesNotContain("org/springframework/", "jakarta/persistence/", "com/fasterxml/jackson/",
                                "java/sql/", "java/net/", "java/io/", "java/time/Clock", "java/util/Random",
                                "SecurityContext", "/application/", "/infra/", "/api/");
            }
        }
    }

    @Test
    void ungradedVariantsCannotCarryNullableScoresAnswersRatingsOrGradingConfiguration() {
        for (var type : List.of(UngradedPolicy.class, ParticipationRequirements.class, ItemRequirement.class,
                StartFacts.class, Incomplete.class, Completed.class, CompletionEvidence.class)) {
            assertThat(Arrays.stream(type.getRecordComponents()).map(component -> component.getName()))
                    .noneMatch(name -> name.matches("(?i).*(score|correct|pass|denominator|grading|rating|answer|feedback|payload).*"));
        }
        assertThat(Arrays.stream(Completed.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactly("version", "completedAt");
        assertThat(Arrays.stream(Incomplete.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactly("version", "state");
    }
}
