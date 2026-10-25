package uk.gegc.quizmaker.features.attempt.resolution;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.attempt.api.AttemptController;
import uk.gegc.quizmaker.features.attempt.application.AttemptPolicyResolver;
import uk.gegc.quizmaker.features.attempt.application.AttemptPresetService;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPolicyResolverImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException.Reason.INVALID_INPUT;
import static uk.gegc.quizmaker.features.attempt.resolution.AttemptPolicyResolverTest.rejected;
import static uk.gegc.quizmaker.features.attempt.resolution.PolicyResolutionFixtures.*;

class PolicyResolutionBoundaryTest {
    @Test
    void protectedSettingsAndCallerIdentityHaveNoGenericOverrideChannel() {
        assertThat(Choice.class.getPermittedSubclasses()).containsExactlyInAnyOrder(AnswerOrderChoice.class, HintChoice.class);
        assertThat(Setting.values()).containsExactly(Setting.ANSWER_ORDER, Setting.HINTS);
        assertThat(PolicyResolutionRequest.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("resolverVersion", "canonicalVersion", "hashAlgorithm", "preset", "content", "overrides");
        assertThat(AttemptPolicyResolverImpl.class.getConstructors()).hasSize(1);
        assertThat(AttemptPolicyResolverImpl.class.getConstructors()[0].getParameterTypes()).contains(AttemptPresetService.class);
        for (var consumer : List.of(AttemptController.class, AttemptServiceImpl.class)) {
            assertThat(Arrays.stream(consumer.getDeclaredFields()).map(java.lang.reflect.Field::getType))
                    .doesNotContain(AttemptPolicyResolver.class, AttemptPolicyResolverImpl.class);
        }
    }

    @Test
    void resolverAndItsValuesHaveNoPersistenceProviderClockOrDocumentDependencies() throws Exception {
        var pending = new ArrayDeque<>(List.of(AttemptPolicyResolverImpl.class, PolicyResolutionRequest.class,
                PolicyResolution.class, PolicyResolutionException.class));
        while (!pending.isEmpty()) {
            var type = pending.remove();
            pending.addAll(List.of(type.getDeclaredClasses()));
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1))
                        .doesNotContain("jakarta/persistence/", "java/sql/", "java/net/", "java/time/Clock", "java/util/Random",
                                "/repository/", "/features/document", "/features/ai/", "/features/billing/", "SecurityContext");
            }
        }
    }

    @Test
    void missingOrUnboundedInputDoesNotBecomeAnImplicitDefaultOrPermission() {
        var input = request();
        rejected(() -> new PolicyResolutionRequest(1, 1, null, input.preset(), input.content(), List.of()), INVALID_INPUT);
        rejected(() -> new PolicyResolutionRequest(1, 1, "SHA-256", null, input.content(), List.of()), INVALID_INPUT);
        rejected(() -> new PolicyResolutionRequest(1, 1, "SHA-256", input.preset(), null, List.of()), INVALID_INPUT);
        rejected(() -> new PolicyResolutionRequest(1, 1, "SHA-256", input.preset(), input.content(), null), INVALID_INPUT);
        rejected(() -> new AllowedChoices(null, Set.of()), INVALID_INPUT);
        rejected(() -> new AllowedChoices(Set.of(), null), INVALID_INPUT);
        rejected(() -> new HintChoice(null), INVALID_INPUT);
        rejected(() -> new AnswerOrderChoice(null), INVALID_INPUT);
        rejected(() -> new SourceOverrides(null, 1, ALL_CHOICES, List.of()), INVALID_INPUT);
        rejected(() -> new SourceOverrides(OverrideSource.AUTHOR, 1, null, List.of()), INVALID_INPUT);
        var source = source(OverrideSource.AUTHOR);
        rejected(() -> request(source, source, source), INVALID_INPUT);
    }
}
