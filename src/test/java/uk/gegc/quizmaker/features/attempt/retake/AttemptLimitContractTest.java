package uk.gegc.quizmaker.features.attempt.retake;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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
import uk.gegc.quizmaker.features.attempt.infra.policy.CanonicalPolicyFormat;
import static org.assertj.core.api.Assertions.*;

class AttemptLimitContractTest {
    private final CanonicalPolicyFormat format = new CanonicalPolicyFormat();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AttemptPolicyResolverImpl resolver = new AttemptPolicyResolverImpl(new AttemptPresetServiceImpl(), metrics);
    private final ContentPolicy content = new ContentPolicy(UUID.randomUUID(), 1, FormMode.PRECOMPUTED,
            SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);

    @Test void oldUnlimitedCanonicalShapeAndHashSurviveExtension() {
        var before = resolver.resolve(request(List.of()));
        assertThat(before.canonicalJson()).contains("\"attempts\":{\"mode\":\"UNLIMITED\"}");
        assertThat(before.canonicalJson()).doesNotContain("maximumAttempts", "cooldownSeconds", "cooldownNanos");
        assertThat(format.decode(before.canonicalJson(), before.hash())).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(longs = {0, 1, Long.MAX_VALUE})
    void finiteLimitsAndExactDurationRoundTripWithAuthorProvenance(long maximum) {
        for (var cooldown : List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofSeconds(60), Duration.ofSeconds(Long.MAX_VALUE, 999999999))) {
            var policy = new AttemptLimitPolicy(AttemptLimitMode.FIXED_COUNT, maximum, cooldown);
            var captured = resolver.resolve(request(List.of(source(OverrideSource.AUTHOR, policy, true))));
            assertThat(captured.policy().attempts()).isEqualTo(policy);
            assertThat(format.decode(captured.canonicalJson(), captured.hash())).isEqualTo(captured);
            assertThat(captured.provenance().get("attempts.maximumAttempts").source()).isEqualTo(PolicyResolution.Source.AUTHOR);
        }
    }

    @Test void participantOrUnapprovedAuthorCannotGrantAdditionalAllowance() {
        var policy = new AttemptLimitPolicy(AttemptLimitMode.FIXED_COUNT, 3L, Duration.ZERO);
        for (var denied : List.of(source(OverrideSource.PARTICIPANT, policy, true), source(OverrideSource.AUTHOR, policy, false)))
            assertThatThrownBy(() -> resolver.resolve(request(List.of(denied))))
                    .isInstanceOfSatisfying(PolicyResolutionException.class,
                            e -> assertThat(e.reason()).isEqualTo(PolicyResolutionException.Reason.FORBIDDEN_OVERRIDE));
        assertThatThrownBy(() -> new AttemptLimitChoice(null)).isInstanceOf(PolicyResolutionException.class);
    }

    @Test void missingNegativeAndContradictoryLimitsNeverBecomeDefaults() {
        assertThatThrownBy(() -> new AttemptLimitPolicy(null)).isInstanceOf(PolicyValidationException.class);
        assertThatThrownBy(() -> new AttemptLimitPolicy(AttemptLimitMode.FIXED_COUNT)).isInstanceOf(PolicyValidationException.class);
        assertThatThrownBy(() -> new AttemptLimitPolicy(AttemptLimitMode.FIXED_COUNT, -1L, Duration.ZERO)).isInstanceOf(PolicyValidationException.class);
        assertThatThrownBy(() -> new AttemptLimitPolicy(AttemptLimitMode.UNLIMITED, 1L, Duration.ZERO)).isInstanceOf(PolicyValidationException.class);
        assertThatThrownBy(() -> new AttemptLimitPolicy(AttemptLimitMode.UNLIMITED, null, null)).isInstanceOf(PolicyValidationException.class);
        assertThatThrownBy(() -> new AttemptLimitPolicy(AttemptLimitMode.UNLIMITED, null, Duration.ofNanos(-1))).isInstanceOf(PolicyValidationException.class);
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"mode\":\"UNKNOWN\"}", "{\"mode\":\"FIXED_COUNT\"}",
            "{\"mode\":\"FIXED_COUNT\",\"maximumAttempts\":1.5}",
            "{\"mode\":\"FIXED_COUNT\",\"maximumAttempts\":\"1\"}",
            "{\"mode\":\"UNLIMITED\",\"maximumAttempts\":null}",
            "{\"mode\":\"UNLIMITED\",\"cooldownNanos\":1}",
            "{\"mode\":\"UNLIMITED\",\"cooldownSeconds\":-1,\"cooldownNanos\":0}",
            "{\"mode\":\"UNLIMITED\",\"cooldownSeconds\":0,\"cooldownNanos\":1000000000}",
            "{\"mode\":\"UNLIMITED\",\"cooldownSeconds\":0,\"cooldownNanos\":0}",
            "null"})
    void malformedSavedLimitsFailClosed(String json) throws Exception {
        var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(uk.gegc.quizmaker.features.attempt.infra.policy.AttemptLimitPolicyJson.module()).build();
        assertThatThrownBy(() -> mapper.readValue(json, AttemptLimitPolicy.class)).isInstanceOf(Exception.class);
    }

    private SourceOverrides source(OverrideSource source, AttemptLimitPolicy value, boolean granted) {
        return new SourceOverrides(source, 1, new AllowedChoices(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                granted ? Set.of(value) : Set.of()), List.of(new AttemptLimitChoice(value)));
    }
    private PolicyResolutionRequest request(List<SourceOverrides> overrides) {
        return new PolicyResolutionRequest(1, 1, "SHA-256", BuiltInPracticePreset.REFERENCE, content, overrides);
    }
}
