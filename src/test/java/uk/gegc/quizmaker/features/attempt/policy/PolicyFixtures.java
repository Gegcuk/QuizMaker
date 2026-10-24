package uk.gegc.quizmaker.features.attempt.policy;

import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;

import java.util.List;
import java.util.UUID;

/** Explicit approved input vector, not a production preset or a runtime integration fake. */
final class PolicyFixtures {
    private PolicyFixtures() {}

    static Builder fixture() { return new Builder(); }

    static final class Builder {
        int schemaVersion = 1;
        ContentPolicy content = new ContentPolicy(UUID.fromString("00000000-0000-0000-0000-000000000042"),
                1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);
        SelectionPolicy questionSelection = new SelectionPolicy(SelectionMode.ALL, 1);
        QuestionOrderingPolicy questionOrdering = new QuestionOrderingPolicy(QuestionOrder.AUTHORED, 1);
        AnswerOrderingPolicy answerOrdering = new AnswerOrderingPolicy(AnswerOrder.AUTHORED, 1);
        DeliveryPolicy delivery = new DeliveryPolicy(DeliveryMode.ALL_AT_ONCE);
        NavigationPolicy navigation = new NavigationPolicy(NavigationMode.FREE);
        SubmissionPolicy submission = submission(false, false, false, true);
        AnswerChangePolicy answerChange = new AnswerChangePolicy(AnswerChangeMode.LOCK_ON_ACCEPTANCE);
        DisclosurePolicy disclosure = new DisclosurePolicy(List.of(
                new DisclosureRule(DisclosureCategory.CORRECTNESS, ReleaseMoment.AFTER_ACCEPTANCE),
                new DisclosureRule(DisclosureCategory.QUESTION_SCORE, ReleaseMoment.AFTER_ACCEPTANCE),
                new DisclosureRule(DisclosureCategory.CORRECT_ANSWER, ReleaseMoment.AFTER_ACCEPTANCE),
                new DisclosureRule(DisclosureCategory.EXPLANATION, ReleaseMoment.AFTER_ACCEPTANCE),
                new DisclosureRule(DisclosureCategory.TOTAL_SCORE, ReleaseMoment.AFTER_COMPLETION),
                new DisclosureRule(DisclosureCategory.RUBRIC, ReleaseMoment.NEVER),
                new DisclosureRule(DisclosureCategory.GRADER_COMMENTS, ReleaseMoment.NEVER),
                new DisclosureRule(DisclosureCategory.COHORT_STATISTICS, ReleaseMoment.NEVER)));
        HintPolicy hints = new HintPolicy(HintMode.ON_REQUEST, HintSource.AUTHORED);
        TimingPolicy timing = new TimingPolicy(TimingMode.NONE);
        PausePolicy pause = new PausePolicy(PauseMode.DISABLED);
        AttemptLimitPolicy attempts = new AttemptLimitPolicy(AttemptLimitMode.UNLIMITED);
        AccessPolicy access = new AccessPolicy(ParticipantMode.AUTHENTICATED,
                AccessValidation.SERVER_REVALIDATED_AT_START_AND_READ);
        GradingPolicy grading = new GradingPolicy(GradingMode.BINARY, ScoreUnits.EXACT_POINTS_WITH_INTEGER_BASIS_POINTS);
        ReviewPolicy review = new ReviewPolicy(ReviewAvailability.AFTER_COMPLETION,
                List.of(ReviewCategory.SUBMITTED_ANSWERS, ReviewCategory.CORRECTNESS,
                        ReviewCategory.CORRECT_ANSWERS, ReviewCategory.EXPLANATIONS),
                ReviewWindow.WHILE_ACCOUNT_AND_RESULT_RETAINED);
        ResultPolicy results = result(true, 7000);
        AccessibilityPolicy accessibility = new AccessibilityPolicy(AccommodationMode.NOT_CONFIGURED);
        IntegrityPolicy integrity = new IntegrityPolicy(FormEvidence.EXACT_PERSISTED_IDENTITIES_AND_ORDER,
                EngineBinding.IMMUTABLE, VersionHandling.REJECT_UNKNOWN);

        ResolvedAttemptPolicy build() {
            return new ResolvedAttemptPolicy(schemaVersion, content, questionSelection, questionOrdering,
                    answerOrdering, delivery, navigation, submission, answerChange, disclosure, hints,
                    timing, pause, attempts, access, grading, review, results, accessibility, integrity);
        }
    }

    static SubmissionPolicy submission(boolean drafts, boolean autosave, boolean requireAll, boolean confirm) {
        return new SubmissionPolicy(drafts, autosave, requireAll, confirm, SubmissionMode.SINGLE_AND_ATOMIC_BATCH,
                RetrySemantics.IDENTICAL_REPLAY_CHANGED_CONFLICT, FinalSubmitPayload.NONE,
                UnansweredPolicy.NO_CREDIT_WITHOUT_ACCEPTANCE);
    }

    static ResultPolicy result(boolean progress, int threshold) {
        return new ResultPolicy(progress, threshold, ResultSelectionMode.NOT_CONFIGURED,
                LeaderboardMode.DISABLED, CertificateMode.DISABLED);
    }
}
