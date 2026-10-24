package uk.gegc.quizmaker.features.question.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.question.contract.ContractFixtures.*;
import static uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException.Reason.*;

class QuestionContractRegistryTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" latest", "LATEST", "java.lang.Runtime", "answer/v1", "answer_1", "answer.",
            "answer\n", "answer\"", "ответ", "a--b"})
    void invalidIdentifiersHaveBoundedNonSensitiveErrors(String identifier) {
        assertThatThrownBy(() -> new ContractVersion(identifier, 1))
                .isInstanceOf(ContractResolutionException.class)
                .hasMessage("Question contract rejected: INVALID_REFERENCE");
    }

    @Test
    void validatesReferenceLengthAndPositiveVersionWithoutTruncation() {
        assertThat(new ContractVersion("a".repeat(128), Integer.MAX_VALUE).id()).hasSize(128);
        assertThatThrownBy(() -> new ContractVersion("a".repeat(129), 1)).isInstanceOf(ContractResolutionException.class);
        for (int invalid : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThatThrownBy(() -> new ContractVersion("answer", invalid)).isInstanceOf(ContractResolutionException.class);
        }
        assertThatThrownBy(() -> new QuestionContractBinding(null, binding(1, 1).response(), binding(1, 1).scoring()))
                .isInstanceOf(ContractResolutionException.class);
        assertThatThrownBy(() -> new QuestionContractBinding(id(1), null, binding(1, 1).scoring()))
                .isInstanceOf(ContractResolutionException.class);
        assertThatThrownBy(() -> new QuestionContractBinding(id(1), binding(1, 1).response(), null))
                .isInstanceOf(ContractResolutionException.class);
    }

    @Test
    void canonicalFormatHasAnExplicitGoldenVectorAndSurvivesReconstruction() throws Exception {
        var binding = binding(1, 1);
        assertThat(binding.canonicalJson()).isEqualTo("{\"bindingFormatVersion\":1,\"questionRevisionId\":\"00000000-0000-0000-0000-000000000001\",\"response\":{\"id\":\"fixture.response\",\"version\":1},\"scoring\":{\"id\":\"fixture.scoring\",\"version\":1}}");
        var json = new ObjectMapper().readTree(binding.canonicalJson());
        assertThat(json.path("bindingFormatVersion").asInt()).isEqualTo(1);
        // Fixed independent vector, rather than comparing a hash implementation to itself.
        assertThat(binding.fingerprint()).isEqualTo("dd783efe62ee5b76c17e9d773e11713a0e3cbe001e0abd24c314156121c48337");
        for (int version = 1; version <= 100; version++) {
            var original = binding(1, version);
            var restored = new QuestionContractBinding(original.questionRevisionId(),
                    new ContractVersion(original.response().id(), version), new ContractVersion(original.scoring().id(), version));
            assertThat(restored.canonicalJson()).isEqualTo(original.canonicalJson());
            assertThat(restored.fingerprint()).isEqualTo(original.fingerprint());
            assertThat(restored.fingerprint()).isNotEqualTo(binding(2, version).fingerprint());
        }
        assertThat(binding.fingerprint()).isNotEqualTo(binding(1, 2).fingerprint());
    }

    @Test
    void exactPairResolutionPreservesIndependentResponseAndScoringVersions() {
        var pair = new QuestionContractBinding(id(1), binding(1, 1).response(), binding(1, 2).scoring());
        var resolved = registry().resolve(pair);
        assertThat(resolved.binding()).isEqualTo(pair);
        assertThat(resolved.response().version()).isEqualTo(pair.response());
        assertThat(resolved.scoring().version()).isEqualTo(pair.scoring());
    }

    @Test
    void unknownResponseOrScorerNeverFallsBackToASupportedVersion() {
        var known = binding(1, 1);
        var unknown = new ContractVersion("fixture.response", 99);
        var missingScorer = new ContractVersion("fixture.scoring", 99);
        for (var input : List.of(new QuestionContractBinding(id(1), unknown, known.scoring()),
                new QuestionContractBinding(id(1), known.response(), missingScorer))) {
            assertThatThrownBy(() -> registry().resolve(input)).isInstanceOfSatisfying(ContractResolutionException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(UNSUPPORTED_CONTRACT));
        }
        assertThatThrownBy(() -> registry().resolve(null)).isInstanceOf(ContractResolutionException.class);
    }

    @Test
    void duplicateRegistrationsFailAtStartupInsteadOfChoosingListOrder() {
        assertThatThrownBy(() -> new QuestionContractRegistryImpl(List.of(response(1), response(1)),
                List.of(scoring(1)), new SimpleMeterRegistry())).isInstanceOfSatisfying(ContractResolutionException.class,
                failure -> assertThat(failure.reason()).isEqualTo(DUPLICATE_CONTRACT));
        assertThatThrownBy(() -> new QuestionContractRegistryImpl(List.of(response(1)),
                List.of(scoring(1), scoring(1)), new SimpleMeterRegistry())).isInstanceOf(ContractResolutionException.class);
    }

    @Test
    void registrationCollectionsAreCapturedAndMetricsHaveOnlyFixedOutcomes() {
        var responses = new ArrayList<>(List.of(response(1)));
        var scores = new ArrayList<>(List.of(scoring(1)));
        var metrics = new SimpleMeterRegistry();
        var registry = new QuestionContractRegistryImpl(responses, scores, metrics);
        responses.clear();
        scores.clear();
        assertThat(registry.resolve(binding(1, 1)).response().version()).isEqualTo(binding(1, 1).response());
        assertThatThrownBy(() -> registry.resolve(binding(1, 2))).isInstanceOf(ContractResolutionException.class);
        assertThat(metrics.get("question.contract.resolutions").tag("outcome", "supported").counter().count()).isEqualTo(1);
        assertThat(metrics.get("question.contract.resolutions").tag("outcome", "unsupported").counter().count()).isEqualTo(1);
        assertThat(metrics.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).hasSize(1));
        var empty = new QuestionContractRegistryImpl(List.of(), List.of(), new SimpleMeterRegistry());
        assertThatThrownBy(() -> empty.resolve(binding(1, 1))).isInstanceOf(ContractResolutionException.class);
    }

    @Test
    void exactScoreValuesRejectImpossiblePointsAndPreservePrecision() {
        var score = new ContractScore(new BigDecimal("0.12345678901234567890"), BigDecimal.ONE);
        assertThat(score.earned().toPlainString()).isEqualTo("0.12345678901234567890");
        for (BigDecimal earned : new BigDecimal[]{null, BigDecimal.ONE.negate(), BigDecimal.TEN}) {
            assertThatThrownBy(() -> new ContractScore(earned, BigDecimal.ONE)).isInstanceOf(ContractResolutionException.class);
        }
        assertThatThrownBy(() -> new ContractScore(BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(ContractResolutionException.class);
        assertThatThrownBy(() -> new ContractScore(BigDecimal.ZERO, null)).isInstanceOf(ContractResolutionException.class);
    }
}
