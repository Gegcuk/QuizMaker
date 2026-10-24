package uk.gegc.quizmaker.features.attempt.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PolicyPurityTest {
    @Test
    @DisplayName("policy boundary: when the model is inspected then it has no transport persistence or mode-name dependency")
    void compiledPolicyHasNoFrameworkPersistenceOrExternalSystemDependency() throws Exception {
        var pending = new ArrayDeque<>(List.of(ResolvedAttemptPolicy.class, PolicyValidationException.class,
                Class.forName(ResolvedAttemptPolicy.class.getPackageName() + ".PolicyValues")));
        var inspected = new ArrayList<Class<?>>();
        while (!pending.isEmpty()) {
            var type = pending.remove();
            inspected.add(type);
            pending.addAll(List.of(type.getDeclaredClasses()));
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                // Class-file constants catch method-body dependencies as well as field/annotation types.
                var constants = new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertThat(constants).as(type.getName()).doesNotContain("org/springframework/", "jakarta/persistence/",
                        "javax/persistence/", "com/fasterxml/jackson/", "java/sql/", "java/net/", "java/io/",
                        "AttemptMode", "Preset", "/application/", "/infra/", "/api/");
            }
            if (type.isRecord()) {
                for (var component : type.getRecordComponents()) {
                    var dependency = component.getType();
                    assertThat(dependency.isPrimitive() || dependency.getPackageName().startsWith("java.")
                            || dependency.getPackageName().equals(ResolvedAttemptPolicy.class.getPackageName()))
                            .as(type.getSimpleName() + "." + component.getName()).isTrue();
                }
            }
        }
        assertThat(inspected).contains(ResolvedAttemptPolicy.ContentPolicy.class,
                ResolvedAttemptPolicy.IntegrityPolicy.class, ResolvedAttemptPolicy.DisclosureRule.class);
    }
}
