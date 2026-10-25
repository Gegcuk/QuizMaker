package uk.gegc.quizmaker.features.attempt.resolution;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.application.AttemptPolicyResolver;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPolicyResolverImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPresetServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetReference;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetResolutionException;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution.Source.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.resolution.PolicyResolutionFixtures.*;

class AttemptPolicyResolverTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AttemptPolicyResolver resolver = new AttemptPolicyResolverImpl(new AttemptPresetServiceImpl(), metrics);

    @Test
    void defaultsRetainTheExactPresetAndAccountForEveryPolicyLeaf() throws Exception {
        var result = resolver.resolve(request());
        assertThat(result.policy()).isEqualTo(BuiltInPracticePreset.V1.bindContent(content(42)));
        assertThat(result.preset()).isEqualTo(BuiltInPracticePreset.REFERENCE);
        assertThat(result.resolverVersion()).isEqualTo(1);
        assertThat(result.canonicalVersion()).isEqualTo(1);
        assertThat(result.hashAlgorithm()).isEqualTo("SHA-256");
        var paths = new HashSet<String>();
        collectPaths(JsonMapper.builder().build().valueToTree(result.policy()), "", paths);
        assertThat(result.provenance().keySet()).containsExactlyInAnyOrderElementsOf(paths);
        result.provenance().forEach((path, source) -> {
            var expected = path.startsWith("content.") ? REVISION
                    : path.equals("schemaVersion") || path.startsWith("integrity.") ? PLATFORM_SAFETY : PRESET;
            assertThat(source).as(path).isEqualTo(new PolicyResolution.ValueSource(expected, 1));
        });
        assertThat(metrics.get("attempt.policy.resolutions").tag("outcome", "success").counter().count()).isEqualTo(1);
        assertThat(metrics.get("attempt.policy.resolution.sources").tag("source", "PRESET").counter().count()).isEqualTo(1);
        assertThat(metrics.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag ->
                assertThat(tag.getValue()).doesNotContain(content(42).quizRevisionId().toString(), "FREE_PRACTICE")));
    }

    @Test
    void explicitParticipantChoicesWinOverAuthorValuesIncludingAnExplicitReturnToPresetDefaults() {
        var author = source(OverrideSource.AUTHOR, new AnswerOrderChoice(AnswerOrder.SHUFFLED_PER_ATTEMPT), new HintChoice(HintMode.DISABLED));
        var participant = source(OverrideSource.PARTICIPANT, new HintChoice(HintMode.ON_REQUEST), new AnswerOrderChoice(AnswerOrder.AUTHORED));
        var result = resolver.resolve(request(participant, author));
        assertThat(result.policy()).isEqualTo(resolver.resolve(request()).policy());
        assertThat(result.provenance().get("answerOrdering.mode")).isEqualTo(new PolicyResolution.ValueSource(PARTICIPANT, 1));
        assertThat(result.provenance().get("hints.mode")).isEqualTo(new PolicyResolution.ValueSource(PARTICIPANT, 1));
        // Same values with a different actual source remain distinguishable audit evidence.
        assertThat(result.hash()).isNotEqualTo(resolver.resolve(request()).hash());
        assertThat(result).isEqualTo(resolver.resolve(request(author, participant)));
    }

    @Test
    void aPartialParticipantChoiceKeepsUnchangedAuthorAndProtectedPresetValues() {
        var result = resolver.resolve(request(
                source(OverrideSource.AUTHOR, new HintChoice(HintMode.DISABLED)),
                source(OverrideSource.PARTICIPANT, new AnswerOrderChoice(AnswerOrder.SHUFFLED_PER_ATTEMPT))));
        assertThat(result.policy().hints().mode()).isEqualTo(HintMode.DISABLED);
        assertThat(result.policy().answerOrdering().mode()).isEqualTo(AnswerOrder.SHUFFLED_PER_ATTEMPT);
        assertThat(result.provenance().get("hints.mode")).isEqualTo(new PolicyResolution.ValueSource(AUTHOR, 1));
        var baseline = BuiltInPracticePreset.V1.bindContent(content(42));
        assertThat(result.policy().access()).isEqualTo(baseline.access());
        assertThat(result.policy().grading()).isEqualTo(baseline.grading());
        assertThat(result.policy().disclosure()).isEqualTo(baseline.disclosure());
        assertThat(result.policy().integrity()).isEqualTo(baseline.integrity());
        assertThat(result.policy().submission()).isEqualTo(baseline.submission());
        assertThat(result.policy().results()).isEqualTo(baseline.results());
    }

    @ParameterizedTest
    @EnumSource(OverrideSource.class)
    void missingGrantsNeverBecomePermissionToApplyEitherChoice(OverrideSource origin) {
        for (Choice choice : List.of(new AnswerOrderChoice(AnswerOrder.SHUFFLED_PER_ATTEMPT), new HintChoice(HintMode.DISABLED))) {
            rejected(() -> resolver.resolve(request(new SourceOverrides(origin, 1, NO_CHOICES, List.of(choice)))), FORBIDDEN_OVERRIDE);
        }
    }

    @Test
    void anAuthorRestrictionNeedsAnExplicitGrantForTheExactParticipantValue() {
        var restriction = new AllowedChoices(Set.of(AnswerOrder.AUTHORED), Set.of(HintMode.DISABLED));
        var author = source(OverrideSource.AUTHOR, new HintChoice(HintMode.DISABLED));
        var allowed = new SourceOverrides(OverrideSource.PARTICIPANT, 1, restriction, List.of(new HintChoice(HintMode.DISABLED)));
        assertThat(resolver.resolve(request(author, allowed)).policy().hints().mode()).isEqualTo(HintMode.DISABLED);
        var denied = new SourceOverrides(OverrideSource.PARTICIPANT, 1, restriction, List.of(new HintChoice(HintMode.ON_REQUEST)));
        rejected(() -> resolver.resolve(request(author, denied)), FORBIDDEN_OVERRIDE);
        assertThat(resolver.resolve(request()).policy()).isEqualTo(BuiltInPracticePreset.V1.bindContent(content(42)));
        assertThat(metrics.get("attempt.policy.resolutions").tag("reason", "FORBIDDEN_OVERRIDE").counter().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(OverrideSource.class)
    void duplicateSourcesAndSettingsAreExplicitErrorsEvenWhenIdentical(OverrideSource origin) {
        var input = source(origin, new HintChoice(HintMode.DISABLED));
        rejected(() -> resolver.resolve(request(input, input)), DUPLICATE_SOURCE);
        rejected(() -> resolver.resolve(request(input, source(origin, new HintChoice(HintMode.ON_REQUEST)))), DUPLICATE_SOURCE);
        rejected(() -> resolver.resolve(request(source(origin, new HintChoice(HintMode.DISABLED), new HintChoice(HintMode.DISABLED)))), DUPLICATE_SETTING);
        rejected(() -> resolver.resolve(request(source(origin, new AnswerOrderChoice(AnswerOrder.AUTHORED),
                new AnswerOrderChoice(AnswerOrder.SHUFFLED_PER_ATTEMPT)))), DUPLICATE_SETTING);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unknownVersionsNeverChooseTheLatestSupportedOne(int version) {
        var input = request();
        rejected(() -> resolver.resolve(new PolicyResolutionRequest(version, 1, "SHA-256", input.preset(), input.content(), List.of())), UNSUPPORTED_RESOLVER_VERSION);
        rejected(() -> resolver.resolve(new PolicyResolutionRequest(1, version, "SHA-256", input.preset(), input.content(), List.of())), UNSUPPORTED_CANONICAL_VERSION);
        rejected(() -> resolver.resolve(request(new SourceOverrides(OverrideSource.AUTHOR, version, ALL_CHOICES, List.of()))), UNSUPPORTED_SOURCE_VERSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHA-512", "sha-256", "SHA256", "", "private-input-must-not-appear"})
    void hashAlgorithmMismatchIsAnExplicitContentFreeError(String algorithm) {
        var input = request();
        rejected(() -> resolver.resolve(new PolicyResolutionRequest(1, 1, algorithm, input.preset(), input.content(), List.of())), UNSUPPORTED_HASH_ALGORITHM);
    }

    @Test
    void unknownPresetReferencesAndNullInputHaveNoFallback() {
        var input = request();
        assertThatThrownBy(() -> resolver.resolve(new PolicyResolutionRequest(1, 1, "SHA-256",
                new PresetReference("FREE_PRACTICE", 2), input.content(), List.of())))
                .isInstanceOfSatisfying(PresetResolutionException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(PresetResolutionException.Reason.UNSUPPORTED_PRESET));
        rejected(() -> resolver.resolve(null), INVALID_INPUT);
    }

    @Test
    void sourceChoiceAndGrantCollectionOrderDoNotChangeTheResult() {
        var expected = resolver.resolve(request(
                source(OverrideSource.AUTHOR, new HintChoice(HintMode.DISABLED), new AnswerOrderChoice(AnswerOrder.AUTHORED)),
                source(OverrideSource.PARTICIPANT, new HintChoice(HintMode.ON_REQUEST), new AnswerOrderChoice(AnswerOrder.SHUFFLED_PER_ATTEMPT))));
        for (int seed = 0; seed < 32; seed++) {
            var choices = new ArrayList<Choice>(List.of(new AnswerOrderChoice(AnswerOrder.SHUFFLED_PER_ATTEMPT), new HintChoice(HintMode.ON_REQUEST)));
            Collections.shuffle(choices, new Random(seed));
            var sources = new ArrayList<>(List.of(source(OverrideSource.AUTHOR, new AnswerOrderChoice(AnswerOrder.AUTHORED), new HintChoice(HintMode.DISABLED)),
                    new SourceOverrides(OverrideSource.PARTICIPANT, 1, ALL_CHOICES, choices)));
            Collections.shuffle(sources, new Random(seed + 1));
            assertThat(resolver.resolve(new PolicyResolutionRequest(1, 1, "SHA-256", expected.preset(), content(42), sources))).isEqualTo(expected);
        }
    }

    @Test
    void inputAndResultCollectionsAndReturnedBytesAreDefensiveCopies() {
        var orders = new HashSet<>(Set.of(AnswerOrder.AUTHORED));
        var hints = new HashSet<>(Set.of(HintMode.DISABLED));
        var allowed = new AllowedChoices(orders, hints);
        var choices = new ArrayList<Choice>(List.of(new HintChoice(HintMode.DISABLED)));
        var source = new SourceOverrides(OverrideSource.PARTICIPANT, 1, allowed, choices);
        var sources = new ArrayList<>(List.of(source));
        var request = new PolicyResolutionRequest(1, 1, "SHA-256", BuiltInPracticePreset.REFERENCE, content(42), sources);
        orders.clear(); hints.clear(); choices.clear(); sources.clear();
        var result = resolver.resolve(request);
        assertThat(result.policy().hints().mode()).isEqualTo(HintMode.DISABLED);
        var bytes = result.canonicalBytes();
        bytes[0] = 0;
        assertThat(result.canonicalBytes()[0]).isEqualTo((byte) '{');
        assertThatThrownBy(() -> result.provenance().clear()).isInstanceOf(UnsupportedOperationException.class);
        var provenance = new HashMap<>(result.provenance());
        var copied = new PolicyResolution(1, 1, "SHA-256", result.preset(), result.policy(), provenance, result.canonicalJson(), result.hash());
        provenance.clear();
        assertThat(copied).isEqualTo(result);
    }

    @Test
    void independentInstancesAndConcurrentCallsHaveIdenticalEvidence() throws Exception {
        var input = request(source(OverrideSource.PARTICIPANT, new HintChoice(HintMode.DISABLED)));
        var expected = resolver.resolve(input);
        var restarted = new AttemptPolicyResolverImpl(new AttemptPresetServiceImpl(), new SimpleMeterRegistry());
        var executor = Executors.newFixedThreadPool(4);
        try {
            var work = new ArrayList<Callable<PolicyResolution>>();
            for (int i = 0; i < 64; i++) work.add(() -> restarted.resolve(input));
            for (var future : executor.invokeAll(work, 10, TimeUnit.SECONDS)) assertThat(future.get()).isEqualTo(expected);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void telemetryFailureDoesNotChangeEitherTheValueOrTheOriginalFailure() {
        var registry = mock(MeterRegistry.class);
        when(registry.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("telemetry unavailable"));
        var degraded = new AttemptPolicyResolverImpl(new AttemptPresetServiceImpl(), registry);
        assertThat(degraded.resolve(request())).isEqualTo(resolver.resolve(request()));
        rejected(() -> degraded.resolve(null), INVALID_INPUT);
    }

    private static void collectPaths(com.fasterxml.jackson.databind.JsonNode node, String path, Set<String> paths) {
        if (node.isObject()) node.fields().forEachRemaining(e -> collectPaths(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), paths));
        else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) collectPaths(node.get(i), path + "[" + i + "]", paths);
        } else paths.add(path);
    }

    static void rejected(Runnable action, PolicyResolutionException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PolicyResolutionException.class, ex -> {
            assertThat(ex.reason()).isEqualTo(reason);
            assertThat(ex).hasMessage("Attempt policy resolution rejected: " + reason).hasNoCause();
        });
    }
}
