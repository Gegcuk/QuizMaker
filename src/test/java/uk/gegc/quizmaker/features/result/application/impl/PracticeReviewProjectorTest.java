package uk.gegc.quizmaker.features.result.application.impl;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.FormOptionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.Value;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.result.domain.model.*;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.*;
import static org.assertj.core.api.Assertions.*;

/** Local composition of the real decision table and projector; not runtime-policy/authentication proof. */
class PracticeReviewProjectorTest {
    final Instant at = Instant.parse("2026-11-08T12:00:00Z");
    final UUID attempt = UUID.randomUUID(), form = UUID.randomUUID(), snapshot = UUID.randomUUID(), revision = UUID.randomUUID();
    final UUID item = UUID.randomUUID(), sourceItem = UUID.randomUUID(), question = UUID.randomUUID(), answer = UUID.randomUUID();
    final UUID user = UUID.randomUUID();
    final UUID quiz = UUID.randomUUID();
    final QuestionRevision q = new QuestionRevision(question, UUID.randomUUID(), 1, QuestionType.TRUE_FALSE, Difficulty.EASY,
            "Frozen question", "{\"answer\":true}", null, "Captured explanation", null, null, at, List.of());
    final uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy policy = BuiltInPracticePreset.V1.bindContent(
            new ContentPolicy(revision, 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED));
    final AttemptLifecycle.State state = new AttemptLifecycle.State(1, AttemptLifecycle.Execution.SUBMITTED, AttemptLifecycle.AnswerCollection.COMPLETE);
    final LifecycleState lifecycle = new LifecycleState(attempt, 3, state, at, at, at, at);
    final QuestionContractBinding binding = BaselineQuestionContracts.binding(q);
    final AutomaticResult result = new AutomaticResult(UUID.randomUUID(), 1, UUID.randomUUID(), attempt, form, snapshot, revision,
            "a".repeat(64), AutomaticResult.Score.calculate(1, 1, 7000), at,
            List.of(new AutomaticResult.Item(item, question, answer, 0, binding.response(), binding.scoring(), 1)));

    AttemptReadFactsService.Facts facts() {
        return new AttemptReadFactsService.Facts(new AttemptAccessService.Access(attempt, AttemptAccessService.Operation.RESULT,
                quiz, revision, form, snapshot, 3, user, lifecycle), policy,
                new FixedForm(form, 1, revision, List.of(new FixedForm.Item(item, sourceItem, 0))),
                new FormOptionOrder(form, 1, policy.answerOrdering(), List.of(new FormOptionOrder.Item(item, sourceItem, binding, List.of()))),
                new QuizRevision(revision, quiz, user, 1, "Frozen quiz", null, at, List.of(new QuizRevision.Item(sourceItem, 0, q))),
                Map.of(question, new ResolvedQuestionContracts(binding, new FamilyResponseContractV1(QuestionType.TRUE_FALSE),
                        new FamilyScoringContractV1(QuestionType.TRUE_FALSE))), "a".repeat(64));
    }
    List<Value> answers() {
        return List.of(new Value(answer, attempt, form, item, question, binding.response(), "{\"answer\":true}", UUID.randomUUID(), at));
    }
    Decisions decisions(Set<Category> categories, ReleaseState release) {
        var b = Binding.of(result);
        return ResultDisclosure.evaluate(new Context(1, new Audience(Access.VERIFIED, attempt, user, user),
                new Policy(1, b, Family.PERSONAL_PRACTICE, categories), new AttemptFacts(b, state), ResultFacts.finalized(result),
                new ReleaseFacts(1, b, result.id(), release), ReviewAccess.AVAILABLE, EnumSet.allOf(Category.class)));
    }
    @ParameterizedTest @EnumSource(Category.class)
    void eachAllowedCategoryRevealsOnlyItsOwnPayload(Category allowed) {
        var item = PracticeReviewProjector.items(facts(), result, answers(), decisions(Set.of(allowed), ReleaseState.RELEASED)).get(0);
        assertThat(item.score() != null).isEqualTo(allowed == Category.QUESTION_SCORE);
        assertThat(item.correctness() != null).isEqualTo(allowed == Category.CORRECTNESS);
        assertThat(item.correctAnswer() != null).isEqualTo(allowed == Category.CORRECT_ANSWER);
        assertThat(item.explanation() != null).isEqualTo(allowed == Category.EXPLANATION);
        assertThat(item.submittedAnswer() != null).isEqualTo(allowed == Category.SUBMITTED_ANSWERS);
        assertThat(item.responseState() != null).isEqualTo(allowed == Category.SUBMITTED_ANSWERS);
    }
    @Test void pendingReleaseNeverCarriesProtectedValues() {
        var item = PracticeReviewProjector.items(facts(), result, answers(), decisions(EnumSet.allOf(Category.class), ReleaseState.PENDING)).get(0);
        assertThat(item.score()).isNull(); assertThat(item.correctness()).isNull(); assertThat(item.correctAnswer()).isNull();
        assertThat(item.submittedAnswer()).isNull(); assertThat(item.responseState()).isNull(); assertThat(item.explanation()).isNull();
    }
    @Test void releaseMetadataRejectsUnknownVersionsAndTruncatesToDatabasePrecision() {
        assertThatThrownBy(() -> new ResultRelease(UUID.randomUUID(), 2, result.id(), at)).isInstanceOf(ResultReleaseException.class);
        var release = new ResultRelease(UUID.randomUUID(), 1, result.id(), at.plusNanos(123456789));
        assertThat(release.releasedAt().getNano()).isEqualTo(123456000);
        assertThat(release.toString()).doesNotContain(result.id().toString());
    }
}
