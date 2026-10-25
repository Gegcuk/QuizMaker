package uk.gegc.quizmaker.features.attempt.preset;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.attempt.application.AttemptPresetService;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPresetServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PresetBoundaryTest {
    @Test
    void domainAndApplicationContractCannotCallWritersClocksRepositoriesOrExternalProviders() throws Exception {
        var pending = new ArrayDeque<>(List.of(PresetReference.class, PresetResolutionException.class,
                BuiltInPracticePreset.class, AuthorizedPresetPolicy.class, LegacyPracticeRequest.class,
                LegacyPracticeMapping.class, AttemptPresetService.class));
        while (!pending.isEmpty()) {
            var type = pending.remove();
            pending.addAll(List.of(type.getDeclaredClasses()));
            assertThat(constants(type)).as(type.getName()).doesNotContain("org/springframework/",
                    "jakarta/persistence/", "com/fasterxml/jackson/", "java/sql/", "java/net/", "java/io/",
                    "java/time/", "/repository/", "/infra/", "/api/", "AttemptService", "AttemptMode");
        }
        assertThat(constants(AttemptPresetServiceImpl.class)).doesNotContain("/repository/", "/infra/", "/api/",
                "AttemptService", "AttemptMode", "java/time/", "java/sql/", "java/net/", "java/io/",
                "jakarta/persistence/", "transaction/", "EventPublisher", "MeterRegistry");
    }

    @Test
    void compatibilityNamesStayAtTheMappingBoundaryAndNoLegacyRuntimeConsumerIsWired() throws Exception {
        assertThat(constants(BuiltInPracticePreset.class)).doesNotContain("ONE_BY_ONE", "TIMED");
        for (String runtime : List.of(
                "uk.gegc.quizmaker.features.attempt.application.impl.AttemptServiceImpl",
                "uk.gegc.quizmaker.features.attempt.api.AttemptController")) {
            assertThat(constants(Class.forName(runtime))).doesNotContain("AttemptPresetService", "/preset/");
        }
    }

    private static String constants(Class<?> type) throws Exception {
        try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertThat(bytes).isNotNull();
            return new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
