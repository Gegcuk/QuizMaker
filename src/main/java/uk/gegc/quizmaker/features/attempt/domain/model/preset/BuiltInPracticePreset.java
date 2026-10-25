package uk.gegc.quizmaker.features.attempt.domain.model.preset;

import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;

import java.util.List;

/** Code-defined #612 A02/D06/R01 definition. No mutable quiz configuration contributes defaults. */
public final class BuiltInPracticePreset {
    public static final PresetReference REFERENCE = new PresetReference("FREE_PRACTICE", 1);
    public static final BuiltInPracticePreset V1 = new BuiltInPracticePreset();

    private BuiltInPracticePreset() {}

    public PresetReference reference() { return REFERENCE; }

    /**
     * Supply exact revision metadata; existence, ownership and contract checks belong to the caller.
     * This supplies resolver input, not authorization, canonical bytes, persistence or activation.
     */
    public ResolvedAttemptPolicy bindContent(ContentPolicy content) {
        return new ResolvedAttemptPolicy(1, content,
                new SelectionPolicy(SelectionMode.ALL, 1),
                new QuestionOrderingPolicy(QuestionOrder.AUTHORED, 1),
                new AnswerOrderingPolicy(AnswerOrder.AUTHORED, 1),
                new DeliveryPolicy(DeliveryMode.ALL_AT_ONCE),
                new NavigationPolicy(NavigationMode.FREE),
                new SubmissionPolicy(false, false, false, true, SubmissionMode.SINGLE_AND_ATOMIC_BATCH,
                        RetrySemantics.IDENTICAL_REPLAY_CHANGED_CONFLICT, FinalSubmitPayload.NONE,
                        UnansweredPolicy.NO_CREDIT_WITHOUT_ACCEPTANCE),
                new AnswerChangePolicy(AnswerChangeMode.LOCK_ON_ACCEPTANCE),
                new DisclosurePolicy(List.of(
                        new DisclosureRule(DisclosureCategory.CORRECTNESS, ReleaseMoment.AFTER_ACCEPTANCE),
                        new DisclosureRule(DisclosureCategory.QUESTION_SCORE, ReleaseMoment.AFTER_ACCEPTANCE),
                        new DisclosureRule(DisclosureCategory.CORRECT_ANSWER, ReleaseMoment.AFTER_ACCEPTANCE),
                        new DisclosureRule(DisclosureCategory.EXPLANATION, ReleaseMoment.AFTER_ACCEPTANCE),
                        new DisclosureRule(DisclosureCategory.TOTAL_SCORE, ReleaseMoment.AFTER_COMPLETION),
                        new DisclosureRule(DisclosureCategory.RUBRIC, ReleaseMoment.NEVER),
                        new DisclosureRule(DisclosureCategory.GRADER_COMMENTS, ReleaseMoment.NEVER),
                        new DisclosureRule(DisclosureCategory.COHORT_STATISTICS, ReleaseMoment.NEVER))),
                new HintPolicy(HintMode.ON_REQUEST, HintSource.AUTHORED),
                new TimingPolicy(TimingMode.NONE),
                new PausePolicy(PauseMode.DISABLED),
                new AttemptLimitPolicy(AttemptLimitMode.UNLIMITED),
                new AccessPolicy(ParticipantMode.AUTHENTICATED, AccessValidation.SERVER_REVALIDATED_AT_START_AND_READ),
                new GradingPolicy(GradingMode.BINARY, ScoreUnits.EXACT_POINTS_WITH_INTEGER_BASIS_POINTS),
                new ReviewPolicy(ReviewAvailability.AFTER_COMPLETION,
                        List.of(ReviewCategory.SUBMITTED_ANSWERS, ReviewCategory.CORRECTNESS,
                                ReviewCategory.CORRECT_ANSWERS, ReviewCategory.EXPLANATIONS),
                        ReviewWindow.WHILE_ACCOUNT_AND_RESULT_RETAINED),
                new ResultPolicy(true, 7_000, ResultSelectionMode.NOT_CONFIGURED,
                        LeaderboardMode.DISABLED, CertificateMode.DISABLED),
                new AccessibilityPolicy(AccommodationMode.NOT_CONFIGURED),
                new IntegrityPolicy(FormEvidence.EXACT_PERSISTED_IDENTITIES_AND_ORDER,
                        EngineBinding.IMMUTABLE, VersionHandling.REJECT_UNKNOWN));
    }
}
