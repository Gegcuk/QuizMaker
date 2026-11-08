package uk.gegc.quizmaker.features.result.disclosure;

import java.time.Instant;
import java.util.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.question.application.contract.v1.BaselineQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.result.domain.model.AutomaticResult;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.*;

/** Server-fact contract fixtures, not proof of authentication or actual release persistence. */
final class DisclosureFixtures {
    static final Set<Category> PRACTICE = Set.of(Category.TOTAL_SCORE, Category.QUESTION_SCORE, Category.CORRECTNESS,
            Category.CORRECT_ANSWER, Category.EXPLANATION, Category.SUBMITTED_ANSWERS, Category.REVIEW);
    static final Set<Category> FORMAL = Set.of(Category.TOTAL_SCORE, Category.PASS_FAIL);
    static final Set<Category> ALL = EnumSet.allOf(Category.class);
    static final UUID USER = new UUID(1, 1);
    static final AutomaticResult RESULT = new AutomaticResult(new UUID(1, 2), 1, new UUID(1, 3), new UUID(1, 4),
            new UUID(1, 5), new UUID(1, 6), new UUID(1, 7), "a".repeat(64), AutomaticResult.Score.calculate(1, 1, 7000),
            Instant.parse("2000-01-01T00:00:00Z"), List.of(new AutomaticResult.Item(new UUID(1, 8), new UUID(1, 9),
            new UUID(1, 10), 0, BaselineQuestionContracts.response(QuestionType.TRUE_FALSE),
            BaselineQuestionContracts.scoring(QuestionType.TRUE_FALSE), 1)));
    static final Binding BINDING = Binding.of(RESULT);

    static ResolvedAttemptPolicy savedPractice() {
        return BuiltInPracticePreset.V1.bindContent(new ResolvedAttemptPolicy.ContentPolicy(RESULT.quizRevisionId(), 1,
                ResolvedAttemptPolicy.FormMode.PRECOMPUTED, ResolvedAttemptPolicy.SectionMode.IMPLICIT_FLAT,
                ResolvedAttemptPolicy.CaseMode.NOT_CONFIGURED));
    }

    static final class Fixture {
        int version = 1;
        Audience audience = new Audience(Access.VERIFIED, RESULT.attemptId(), USER, USER);
        Policy policy = Policy.practice(BINDING, savedPractice());
        AttemptFacts attempt = new AttemptFacts(BINDING, new AttemptLifecycle.State(1,
                AttemptLifecycle.Execution.SUBMITTED, AttemptLifecycle.AnswerCollection.COMPLETE));
        ResultFacts result = ResultFacts.finalized(RESULT);
        ReleaseFacts release = new ReleaseFacts(1, BINDING, RESULT.id(), ReleaseState.RELEASED);
        ReviewAccess review = ReviewAccess.AVAILABLE;
        Set<Category> available = ALL;

        Context build() { return new Context(version, audience, policy, attempt, result, release, review, available); }
        Fixture formal() { policy = Policy.formalDefault(BINDING); return this; }
        Fixture pendingRelease() { release = new ReleaseFacts(1, BINDING, RESULT.id(), ReleaseState.PENDING); return this; }
        Fixture pendingResult() {
            result = new ResultFacts(1, BINDING, null, ResultState.PENDING);
            release = new ReleaseFacts(1, BINDING, null, ReleaseState.PENDING); return this;
        }
    }

    private DisclosureFixtures() { }
}
