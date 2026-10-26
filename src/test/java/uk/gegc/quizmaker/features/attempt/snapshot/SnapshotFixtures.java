package uk.gegc.quizmaker.features.attempt.snapshot;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPolicyResolverImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPresetServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshot;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SnapshotFixtures {
    static final UUID OWNER = id("snapshot-owner");
    static final UUID OTHER_OWNER = id("foreign-snapshot-owner");

    static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    static PolicySnapshot snapshot(String key, UUID revision, boolean hints) {
        var metrics = new SimpleMeterRegistry();
        try {
            var resolver = new AttemptPolicyResolverImpl(new AttemptPresetServiceImpl(), metrics);
            var sources = List.of(new SourceOverrides(OverrideSource.PARTICIPANT, 1,
                    new AllowedChoices(Set.of(), Set.of(HintMode.values())),
                    List.of(new HintChoice(hints ? HintMode.ON_REQUEST : HintMode.DISABLED))));
            PolicyResolution value = resolver.resolve(new PolicyResolutionRequest(1, 1, "SHA-256", BuiltInPracticePreset.REFERENCE,
                    new ContentPolicy(revision, 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED), sources));
            return new PolicySnapshot(id(key), value);
        } finally { metrics.close(); }
    }

    static void rejected(Runnable action, PolicySnapshotException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PolicySnapshotException.class, failure -> {
            org.assertj.core.api.Assertions.assertThat(failure.reason()).isEqualTo(reason);
            org.assertj.core.api.Assertions.assertThat(failure.getCause()).isNull();
        });
    }
}
