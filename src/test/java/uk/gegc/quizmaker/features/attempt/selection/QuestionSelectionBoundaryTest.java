package uk.gegc.quizmaker.features.attempt.selection;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import uk.gegc.quizmaker.features.attempt.application.QuestionSelectionService;
import uk.gegc.quizmaker.features.attempt.application.impl.QuestionSelectionServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.selection.QuestionSelectionFixtures.*;

class QuestionSelectionBoundaryTest {
    @Test
    void serviceInterface_whenRegistered_resolvesWithoutPersistenceSecurityOrProviderInfrastructure() {
        try (var context = new AnnotationConfigApplicationContext(QuestionSelectionServiceImpl.class)) {
            var service = context.getBean(QuestionSelectionService.class);
            assertThat(service.select(input(QuestionSelection.Configuration.all())).itemIds()).containsExactlyElementsOf(ORDERED);
        }
    }

    @Test
    void compiledSelectionContract_whenInspected_hasNoIoPersistenceTransportOrMutableAuthoringDependency() throws Exception {
        var pending = new ArrayDeque<>(List.of(QuestionSelection.class, QuestionSelectionException.class));
        while (!pending.isEmpty()) {
            var type = pending.remove();
            pending.addAll(List.of(type.getDeclaredClasses()));
            assertNoDependencies(type, "org/springframework/", "jakarta/persistence/", "javax/persistence/",
                    "com/fasterxml/jackson/", "java/sql/", "java/net/", "java/io/", "/application/", "/infra/", "/api/",
                    "features/question/domain/model/Question;", "features/quiz/domain/model/Quiz;");
        }
        assertNoDependencies(QuestionSelectionServiceImpl.class, "java/sql/", "java/net/", "java/io/", "java/time/",
                "/repository/", "/infra/", "/api/", "java/util/Random", "org/springframework/transaction/");
    }

    private static void assertNoDependencies(Class<?> type, String... forbidden) throws Exception {
        try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertThat(bytes).isNotNull();
            assertThat(new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1)).as(type.getName()).doesNotContain(forbidden);
        }
    }
}
