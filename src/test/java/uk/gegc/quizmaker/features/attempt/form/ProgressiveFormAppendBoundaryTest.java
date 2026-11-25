package uk.gegc.quizmaker.features.attempt.form;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.attempt.application.ProgressiveFormAppendPort;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppend;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppendException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppendPlanner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ProgressiveFormAppendBoundaryTest {
    @Test
    void theContractAndPlannerCannotAccessStorageContentProvidersOrDelivery() throws Exception {
        var pending = new ArrayDeque<>(List.of(ProgressiveFormAppend.class, ProgressiveFormAppendPlanner.class,
                ProgressiveFormAppendException.class, ProgressiveFormAppendPort.class));
        while (!pending.isEmpty()) {
            var type = pending.remove();
            pending.addAll(List.of(type.getDeclaredClasses()));
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                var constants = new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertThat(constants).as(type.getName()).doesNotContain(
                        "org/springframework/", "jakarta/persistence/", "com/fasterxml/jackson/", "java/sql/", "java/net/",
                        "java/time/Clock", "java/util/Random", "SecurityContext", "/repository/", "/infra/", "/api/",
                        "/features/document", "/features/question", "/features/quiz", "/features/ai", "/features/billing",
                        "ParticipantForm", "FixedFormService");
            }
        }
        assertThat(ProgressiveFormAppendPort.class.isInterface()).isTrue();
        assertThat(ProgressiveFormAppendPort.class.getDeclaredMethods()).singleElement().satisfies(method -> {
            assertThat(method.getReturnType()).isEqualTo(ProgressiveFormAppend.Receipt.class);
            assertThat(method.getParameterTypes()).containsExactly(ProgressiveFormAppend.Request.class);
        });
    }
}
