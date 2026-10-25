package uk.gegc.quizmaker.features.attempt.resolution;

import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

final class PolicyResolutionFixtures {
    static final AllowedChoices ALL_CHOICES = new AllowedChoices(Set.of(AnswerOrder.values()), Set.of(HintMode.values()));
    static final AllowedChoices NO_CHOICES = new AllowedChoices(Set.of(), Set.of());

    static ContentPolicy content(long id) {
        return new ContentPolicy(new UUID(0, id), 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);
    }

    static PolicyResolutionRequest request(SourceOverrides... sources) {
        return new PolicyResolutionRequest(1, 1, "SHA-256", BuiltInPracticePreset.REFERENCE, content(42), List.of(sources));
    }

    static SourceOverrides source(OverrideSource source, Choice... choices) {
        return new SourceOverrides(source, 1, ALL_CHOICES, List.of(choices));
    }

    private PolicyResolutionFixtures() {}
}
