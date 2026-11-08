package uk.gegc.quizmaker.features.attempt.navigation;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.application.impl.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.*;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException;
import uk.gegc.quizmaker.features.attempt.infra.policy.CanonicalPolicyFormat;
import static org.assertj.core.api.Assertions.*;

class NavigationPolicyTest {
    private final AttemptPolicyResolverImpl resolver = new AttemptPolicyResolverImpl(new AttemptPresetServiceImpl(), new SimpleMeterRegistry());
    private final ContentPolicy content = new ContentPolicy(UUID.randomUUID(), 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);
    private PolicyResolutionRequest request(NavigationPolicy navigation, OverrideSource source) {
        return new PolicyResolutionRequest(1, 1, "SHA-256", BuiltInPracticePreset.REFERENCE, content,
                List.of(new SourceOverrides(source, 1, new AllowedChoices(Set.of(), Set.of(), Set.of(), Set.of(navigation)),
                        List.of(new NavigationChoice(navigation)))));
    }
    @Test void authorizedIndependentSettingsRoundTripWithExactProvenanceAndDifferentHashes() {
        var hashes = new HashSet<String>();
        for (var mode : NavigationMode.values()) for (boolean skip : List.of(false, true)) for (boolean revisit : List.of(false, true)) {
            if (mode == NavigationMode.FREE && skip) continue;
            var navigation = new NavigationPolicy(mode, new NavigationRules(1, skip, revisit));
            var value = resolver.resolve(request(navigation, OverrideSource.AUTHOR));
            assertThat(value.policy().navigation()).isEqualTo(navigation);
            assertThat(new CanonicalPolicyFormat().decode(value.canonicalJson(), value.hash())).isEqualTo(value);
            assertThat(value.provenance().entrySet().stream().filter(e -> e.getKey().startsWith("navigation.")).map(Map.Entry::getValue))
                    .allMatch(v -> v.source() == PolicyResolution.Source.AUTHOR);
            assertThat(hashes.add(value.hash())).isTrue();
            assertThatThrownBy(() -> resolver.resolve(request(navigation, OverrideSource.PARTICIPANT)))
                    .isInstanceOfSatisfying(PolicyResolutionException.class, e -> assertThat(e.reason()).isEqualTo(PolicyResolutionException.Reason.FORBIDDEN_OVERRIDE));
        }
    }
    @Test void missingOrForgedAuthorProvenanceCannotActivateNavigation() {
        var navigation = new NavigationPolicy(NavigationMode.SEQUENTIAL, new NavigationRules(1, true, false));
        var value = resolver.resolve(request(navigation, OverrideSource.AUTHOR));
        var forged = new TreeMap<>(value.provenance());
        forged.put("navigation.rules.allowSkipAndReturn", new PolicyResolution.ValueSource(PolicyResolution.Source.PRESET, 1));
        var encoded = new CanonicalPolicyFormat().encode(value.preset(), value.policy(), forged);
        assertThatThrownBy(() -> new CanonicalPolicyFormat().decode(encoded.canonicalJson(), encoded.hash())).isInstanceOf(PolicySnapshotException.class);
    }
    @ParameterizedTest @ValueSource(strings = {
            "{}", "null", "{\"mode\":\"SEQUENTIAL\"}", "{\"mode\":\"FREE\",\"rules\":null}",
            "{\"mode\":\"FREE\",\"extra\":true}", "{\"mode\":\"FREE\",\"rules\":{}}",
            "{\"mode\":\"SEQUENTIAL\",\"rules\":{\"version\":1,\"allowSkipAndReturn\":\"false\",\"allowAcceptedRevisit\":true}}",
            "{\"mode\":\"SEQUENTIAL\",\"rules\":{\"version\":2,\"allowSkipAndReturn\":false,\"allowAcceptedRevisit\":true}}",
            "{\"mode\":\"SEQUENTIAL\",\"rules\":{\"version\":1,\"allowSkipAndReturn\":false,\"wrong\":true}}"
    }) void malformedNavigationNeverGetsPermissiveDefaults(String input) throws Exception {
        var node = new JsonMapper().readTree(input);
        var mapper = new JsonMapper().registerModule(uk.gegc.quizmaker.features.attempt.infra.policy.NavigationPolicyJson.module());
        assertThatThrownBy(() -> mapper.treeToValue(node, NavigationPolicy.class)).satisfies(failure -> {
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(PolicyValidationException.class);
        });
    }
    @Test void historicalFreeShapeRetainsItsExactRepresentation() throws Exception {
        var json = new JsonMapper().registerModule(uk.gegc.quizmaker.features.attempt.infra.policy.NavigationPolicyJson.module()); var old = json.readValue("{\"mode\":\"FREE\"}", NavigationPolicy.class);
        assertThat(old.acceptedRevisit()).isTrue(); assertThat(old.skipAndReturn()).isFalse();
        assertThat(json.writeValueAsString(old)).isEqualTo("{\"mode\":\"FREE\"}");
        assertThatThrownBy(() -> new NavigationPolicy(NavigationMode.SEQUENTIAL)).isInstanceOf(PolicyValidationException.class);
        assertThatThrownBy(() -> new NavigationPolicy(NavigationMode.FREE, new NavigationRules(1, true, true)))
                .isInstanceOf(PolicyValidationException.class);
    }

    @Test void unsupportedCapturedNavigationVersionHasATypedUnsupportedOutcome() throws Exception {
        var value = resolver.resolve(request(new NavigationPolicy(NavigationMode.SEQUENTIAL, new NavigationRules(1, true, true)), OverrideSource.AUTHOR));
        var json = new JsonMapper(); var tree = json.readTree(value.canonicalJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/policy/navigation/rules")).put("version", 2);
        assertThatThrownBy(() -> new CanonicalPolicyFormat().decode(json.writeValueAsString(tree), value.hash()))
                .isInstanceOfSatisfying(PolicySnapshotException.class,
                        e -> assertThat(e.reason()).isEqualTo(PolicySnapshotException.Reason.UNSUPPORTED_VERSION));
    }

    @Test void canonicalAllowedActionsUseTheSameNavigationRulesAndTreatProposalsAsUnanswered() {
        var policy = resolver.resolve(request(new NavigationPolicy(NavigationMode.SEQUENTIAL, new NavigationRules(1, false, true)), OverrideSource.AUTHOR)).policy();
        var a = UUID.randomUUID(); var b = UUID.randomUUID(); var form = UUID.randomUUID();
        var nav = new uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Context(policy.navigation(), form,
                List.of(a, b), Set.of(), new uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Cursor(form, a, 1));
        var state = new uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State(1,
                uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.Execution.IN_PROGRESS,
                uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.AnswerCollection.NOT_STARTED);
        var metadata = new uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Metadata(1, UUID.randomUUID(),
                uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.CommandIdentity.FRESH);
        for (var response : List.of(uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts.ResponseState.EMPTY,
                uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts.ResponseState.PENDING_CONFIRMATION)) {
            var c = new uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Context(
                    uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Access.VERIFIED, policy, state, 1,
                    new uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.ItemFacts(true, true, response, b, nav), null);
            var command = new uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Command(1,
                    uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Type.NAVIGATE_TO_ITEM);
            assertThat(uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.evaluate(command, c, metadata).reason())
                    .isEqualTo(uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Reason.ANSWER_REQUIRED);
            assertThat(uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.allowedActions(c).actions())
                    .doesNotContain(uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Type.NAVIGATE_TO_ITEM,
                            uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Type.SUBMIT_ANSWER);
        }
    }
}
