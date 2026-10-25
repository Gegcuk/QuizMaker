package uk.gegc.quizmaker.features.attempt.domain.model.resolution;

import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetReference;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException.Reason.INVALID_INPUT;
import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException.require;

/**
 * Internal immutable evidence, not persisted state or authorization. Canonical bytes cover the
 * preset reference, effective policy and per-leaf sources. #518 owns persisted read verification.
 */
public record PolicyResolution(
        int resolverVersion, int canonicalVersion, String hashAlgorithm, PresetReference preset,
        ResolvedAttemptPolicy policy, Map<String, ValueSource> provenance, String canonicalJson, String hash
) {
    public enum Source { PLATFORM_SAFETY, PRESET, REVISION, AUTHOR, PARTICIPANT }
    public record ValueSource(Source source, int version) {
        public ValueSource { require(source != null && version == 1, INVALID_INPUT); }
    }

    public PolicyResolution {
        require(preset != null && policy != null && provenance != null && canonicalJson != null && hash != null
                && hashAlgorithm != null, INVALID_INPUT);
        require(provenance.entrySet().stream().allMatch(e -> e.getKey() != null && e.getValue() != null), INVALID_INPUT);
        provenance = Collections.unmodifiableMap(new TreeMap<>(provenance));
    }

    /** A fresh array on every call; callers cannot mutate the captured representation. */
    public byte[] canonicalBytes() { return canonicalJson.getBytes(StandardCharsets.UTF_8); }
}
