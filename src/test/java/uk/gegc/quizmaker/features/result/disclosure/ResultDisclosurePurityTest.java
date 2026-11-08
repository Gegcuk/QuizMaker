package uk.gegc.quizmaker.features.result.disclosure;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import static org.assertj.core.api.Assertions.*;

class ResultDisclosurePurityTest {
    @Test void domainDecisionsCannotReadPersistAuthenticateOrCallExternalSystems() throws Exception {
        var pending = new ArrayDeque<Class<?>>(List.of(ResultDisclosure.class));
        while (!pending.isEmpty()) {
            var type = pending.remove(); pending.addAll(List.of(type.getDeclaredClasses()));
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1)).as(type.getName())
                        .doesNotContain("org/springframework/", "jakarta/persistence/", "com/fasterxml/jackson/",
                                "java/sql/", "java/net/", "java/io/", "/application/", "/infra/", "/api/",
                                "java/time/Clock", "java/lang/System", "org/slf4j/");
            }
        }
    }
}
