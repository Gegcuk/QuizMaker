package uk.gegc.quizmaker.features.attempt.domain.model.resolution;

import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.ContentPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.HintMode;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.NavigationPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.DeliveryMode;
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
    public enum Setting { ANSWER_ORDER, HINTS, DRAFTS, NAVIGATION, DELIVERY }

    /** Only explicit supported choices are representable, never arbitrary policy paths. */
    public sealed interface Choice permits AnswerOrderChoice, HintChoice, DraftsChoice, NavigationChoice, DeliveryChoice {
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

    /** Server-authorized at start; participants cannot grant themselves draft support. */
    public record DraftsChoice(boolean enabled) implements Choice {
        @Override public Setting setting() { return Setting.DRAFTS; }
    }

    public record NavigationChoice(NavigationPolicy value) implements Choice {
        public NavigationChoice { require(value != null, INVALID_INPUT); }
        @Override public Setting setting() { return Setting.NAVIGATION; }
    }

    public record DeliveryChoice(DeliveryMode value) implements Choice {
        public DeliveryChoice { require(value != null, INVALID_INPUT); }
        @Override public Setting setting() { return Setting.DELIVERY; }
    }

    /** Exact allowed values, not an implicit permission to enable every supported value. */
    public record AllowedChoices(Set<AnswerOrder> answerOrders, Set<HintMode> hintModes, Set<Boolean> drafts, Set<NavigationPolicy> navigation, Set<DeliveryMode> delivery) {
        public AllowedChoices(Set<AnswerOrder> answerOrders, Set<HintMode> hintModes, Set<Boolean> drafts, Set<NavigationPolicy> navigation) {
            this(answerOrders, hintModes, drafts, navigation, Set.of());
        }
        public AllowedChoices(Set<AnswerOrder> answerOrders, Set<HintMode> hintModes, Set<Boolean> drafts) {
            this(answerOrders, hintModes, drafts, Set.of());
        }
        public AllowedChoices(Set<AnswerOrder> answerOrders, Set<HintMode> hintModes) {
            this(answerOrders, hintModes, Set.of(), Set.of());
        }
        public AllowedChoices {
            require(answerOrders != null && hintModes != null && drafts != null && navigation != null && delivery != null, INVALID_INPUT);
            require(answerOrders.stream().allMatch(v -> v != null) && hintModes.stream().allMatch(v -> v != null)
                    && drafts.stream().allMatch(v -> v != null) && navigation.stream().allMatch(v -> v != null) && delivery.stream().allMatch(v -> v != null), INVALID_INPUT);
            answerOrders = Set.copyOf(answerOrders);
            hintModes = Set.copyOf(hintModes);
            drafts = Set.copyOf(drafts);
            navigation = Set.copyOf(navigation);
            delivery = Set.copyOf(delivery);
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
