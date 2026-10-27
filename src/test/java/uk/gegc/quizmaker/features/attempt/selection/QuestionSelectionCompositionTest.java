package uk.gegc.quizmaker.features.attempt.selection;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.attempt.application.QuestionSelectionService;
import uk.gegc.quizmaker.features.attempt.application.impl.QuestionSelectionServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.AuthoredQuestionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.selection.QuestionSelectionFixtures.*;

/** Real in-memory revision/policy/ordering composition, not persistence, start or publication proof. */
class QuestionSelectionCompositionTest {
    private static final UUID QUESTION_REVISION = new UUID(0, 70);
    private static final UUID MUTABLE_QUESTION = new UUID(0, 80);
    private final QuestionSelectionService service = new QuestionSelectionServiceImpl();

    @Test
    void allFromRealRevisionAndPolicy_whenSelected_preservesOccurrenceIdentityWithoutCopyingProtectedContent() {
        var revision = revision(REVISION, false, "private-question");
        var policy = policy(REVISION);
        var request = Input.fromRevision(policy.content(), revision, Configuration.all(), List.of());
        var result = service.select(request);

        assertThat(result.itemIds()).containsExactlyElementsOf(ORDERED);
        assertThat(result.itemIds()).doesNotContain(QUESTION_REVISION, MUTABLE_QUESTION);
        assertThat(revision.items()).extracting(item -> item.question().id())
                .containsExactly(QUESTION_REVISION, QUESTION_REVISION, QUESTION_REVISION);
        assertThat(result.evidence().quizRevisionId()).isEqualTo(revision.id());
        assertThat(result.evidence().revisionSchemaVersion()).isEqualTo(revision.schemaVersion());
        assertThat(request.toString() + result).doesNotContain("private-question", "private-hint", "private-explanation", "answer", "true");
        assertThat(policy.questionSelection()).isEqualTo(new SelectionPolicy(SelectionMode.ALL, 1));
    }

    @Test
    void manualSelection_whenPassedToRealAuthoredOrdering_preservesOnlySelectedItemsAndOriginalPolicy() {
        var revision = revision(REVISION, false, "private-question");
        var policy = policy(REVISION);
        var request = Input.fromRevision(policy.content(), revision, Configuration.manualSet(List.of(LAST, FIRST)), List.of());
        var selection = service.select(request);
        var ordered = AuthoredQuestionOrder.order(AuthoredQuestionOrder.Input.fromRevision(policy, revision, selection.itemIds()));

        assertThat(ordered.itemIds()).containsExactly(FIRST, LAST);
        assertThat(selection.itemIds()).isEqualTo(ordered.itemIds());
        assertThat(selection.toString()).doesNotContain(MIDDLE.toString());
        // An internal configuration does not add MANUAL_SET to the resolved public policy vocabulary.
        assertThat(policy.questionSelection().mode()).isEqualTo(SelectionMode.ALL);
        assertThat(SelectionMode.values()).containsExactly(SelectionMode.ALL);
        assertThat(revision.items()).extracting(QuizRevision.Item::id).containsExactlyElementsOf(ORDERED);
    }

    @Test
    void suppliedAtomicConstraintWithRealRevision_whenPartial_rejectsAndThenAllowsTheCompleteGroup() {
        var revision = revision(REVISION, false, "private-question");
        var content = policy(REVISION).content();
        var groups = List.of(group(FIRST, MIDDLE));

        assertThatThrownBy(() -> service.select(Input.fromRevision(content, revision,
                Configuration.manualSet(List.of(FIRST)), groups)))
                .hasMessage("Question selection rejected: CONSTRAINT_VIOLATION");
        assertThat(service.select(Input.fromRevision(content, revision,
                Configuration.manualSet(List.of(MIDDLE, FIRST)), groups)).itemIds()).containsExactly(FIRST, MIDDLE);
    }

    @Test
    void laterImmutableRevision_whenContentAndPositionsChange_doesNotChangePreviouslyCapturedSelection() {
        var original = Input.fromRevision(policy(REVISION).content(), revision(REVISION, false, "original"),
                Configuration.all(), List.of());
        var expected = service.select(original);
        UUID newerId = new UUID(0, 901);
        var newer = Input.fromRevision(policy(newerId).content(), revision(newerId, true, "changed"),
                Configuration.all(), List.of());

        assertThat(service.select(newer).itemIds()).containsExactly(LAST, MIDDLE, FIRST);
        assertThat(service.select(newer).evidence().quizRevisionId()).isEqualTo(newerId);
        assertThat(service.select(original)).isEqualTo(expected);
        assertThat(expected.itemIds()).containsExactlyElementsOf(ORDERED);
        assertThat(expected.evidence().quizRevisionId()).isEqualTo(REVISION);
    }

    @Test
    void policyBoundToAnotherRevision_whenProjected_rejectsWithoutEchoingEitherIdentity() {
        assertThatThrownBy(() -> Input.fromRevision(policy(FOREIGN).content(), revision(REVISION, false, "private"),
                Configuration.all(), List.of()))
                .isInstanceOfSatisfying(QuestionSelectionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(QuestionSelectionException.Reason.REVISION_MISMATCH))
                .hasMessage("Question selection rejected: REVISION_MISMATCH");
    }

    @Test
    void missingRevisionOrPolicyBinding_whenProjected_rejectsInsteadOfConsultingMutableContent() {
        assertThatThrownBy(() -> Input.fromRevision(null, revision(REVISION, false, "private"), Configuration.all(), List.of()))
                .hasMessage("Question selection rejected: INVALID_INPUT");
        assertThatThrownBy(() -> Input.fromRevision(policy(REVISION).content(), null, Configuration.all(), List.of()))
                .hasMessage("Question selection rejected: INVALID_INPUT");
    }

    @Test
    void mutableQuestionOrQuestionRevisionId_whenUsedAsManualItem_rejectsRatherThanCollapsingOccurrences() {
        var revision = revision(REVISION, false, "private");
        for (UUID id : List.of(MUTABLE_QUESTION, QUESTION_REVISION, FOREIGN)) {
            assertThatThrownBy(() -> Input.fromRevision(policy(REVISION).content(), revision,
                    Configuration.manualSet(List.of(id)), List.of()))
                    .hasMessage("Question selection rejected: UNKNOWN_ITEM");
        }
    }

    private static ResolvedAttemptPolicy policy(UUID revisionId) {
        return BuiltInPracticePreset.V1.bindContent(new ContentPolicy(revisionId, 1,
                FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED));
    }

    private static QuizRevision revision(UUID id, boolean reversed, String text) {
        var question = new QuestionRevision(reversed ? new UUID(0, 71) : QUESTION_REVISION, MUTABLE_QUESTION, 1, QuestionType.TRUE_FALSE,
                Difficulty.EASY, text, "{\"answer\":true}", "private-hint", "private-explanation", null, null,
                Instant.parse("2026-10-27T12:00:00Z"), List.of());
        return new QuizRevision(id, new UUID(0, 60), new UUID(0, 50), 1, "Quiz", null,
                Instant.parse("2026-10-27T12:00:00Z"), List.of(new QuizRevision.Item(MIDDLE, 7, question),
                new QuizRevision.Item(LAST, reversed ? 1 : 9, question), new QuizRevision.Item(FIRST, reversed ? 9 : 1, question)));
    }
}
