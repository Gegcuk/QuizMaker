package uk.gegc.quizmaker.features.learning;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.learning.application.LearningSchedulingStrategy;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule;
import uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException;

import static org.assertj.core.api.Assertions.*;

class FsrsBoundaryTest {
    @Test
    void projectContractCannotDependOnLibraryStateDatabaseSecurityContextOrClockLookup() throws Exception {
        var pending = new ArrayDeque<>(List.of(LearningSchedule.class, FsrsManifest.class,
                LearningScheduleException.class, LearningSchedulingStrategy.class));
        while (!pending.isEmpty()) {
            var type = pending.remove();
            pending.addAll(List.of(type.getDeclaredClasses()));
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1)).as(type.getName())
                        .doesNotContain("io/github/openspacedrepetition/", "org/springframework/", "jakarta/persistence/",
                                "com/fasterxml/jackson/", "java/sql/", "java/net/", "java/time/Clock", "SecurityContext",
                                "java/util/Random", "currentTimeMillis", "/infra/", "/application/impl/");
            }
        }
    }

    @Test
    void identitySeparatesScheduleCycleFromSessionAndAssessmentAuthority() {
        assertThat(java.util.Arrays.stream(LearningSchedule.Identity.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("learnerId", "quizId", "quizRevisionId", "questionRevisionId", "cycleId");
        for (var type : List.of(LearningSchedule.State.class, LearningSchedule.Review.class,
                LearningSchedule.Transition.class, FsrsManifest.class)) {
            assertThat(java.util.Arrays.stream(type.getRecordComponents()).map(c -> c.getName()))
                    .noneMatch(name -> name.matches("(?i).*(score|mastery|answer|correctness|sessionId|permission|role).*"));
        }
    }
}
