package uk.gegc.quizmaker.features.attempt.domain.model.resolution;

import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.ContentPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.HintMode;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetReference;

import java.util.List;
import java.util.Set;

import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException.Reason.INVALID_INPUT;
import static uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionException.require;

/**
 * Trusted server facts for an already selected ordinary practice policy, not an HTTP request or
 * proof of access. The caller resolves grants against the selected preset/author and current
 * resource authority. An override cannot grant itself permission. Assignments/accommodations,
 * question selection and per-question option constraints have separate integration owners.
 */
public record PolicyResolutionRequest(
        int resolverVersion, int canonicalVersion, String hashAlgorithm,
        PresetReference preset, ContentPolicy content, List<SourceOverrides> overrides
) {
    public static final int RESOLVER_VERSION = 1;
    public static final int CANONICAL_VERSION = 1;
    public static final String HASH_ALGORITHM = "SHA-256";

    public PolicyResolutionRequest {
        require(hashAlgorithm != null && preset != null && content != null && overrides != null, INVALID_INPUT);
        require(overrides.size() <= OverrideSource.values().length && overrides.stream().allMatch(v -> v != null), INVALID_INPUT);
        overrides = List.copyOf(overrides);
    }

    public enum OverrideSource { AUTHOR, PARTICIPANT }
    public enum Setting { ANSWER_ORDER, HINTS }

    /** Only the two supported R01/A03/A04 choices are representable, never arbitrary policy paths. */
    public sealed interface Choice permits AnswerOrderChoice, HintChoice {
        Setting setting();
    }

    public record AnswerOrderChoice(AnswerOrder value) implements Choice {
        public AnswerOrderChoice { require(value != null, INVALID_INPUT); }
        @Override public Setting setting() { return Setting.ANSWER_ORDER; }
    }

    public record HintChoice(HintMode value) implements Choice {
        public HintChoice { require(value != null, INVALID_INPUT); }
        @Override public Setting setting() { return Setting.HINTS; }
    }

    /** Exact allowed values, not an implicit permission to enable every supported value. */
    public record AllowedChoices(Set<AnswerOrder> answerOrders, Set<HintMode> hintModes) {
        public AllowedChoices {
            require(answerOrders != null && hintModes != null, INVALID_INPUT);
            require(answerOrders.stream().allMatch(v -> v != null) && hintModes.stream().allMatch(v -> v != null), INVALID_INPUT);
            answerOrders = Set.copyOf(answerOrders);
            hintModes = Set.copyOf(hintModes);
        }
    }

    public record SourceOverrides(OverrideSource source, int sourceVersion,
                                  AllowedChoices allowed, List<Choice> choices) {
        public SourceOverrides {
            require(source != null && allowed != null && choices != null, INVALID_INPUT);
            require(choices.size() <= Setting.values().length && choices.stream().allMatch(v -> v != null), INVALID_INPUT);
            choices = List.copyOf(choices);
        }
    }
}
