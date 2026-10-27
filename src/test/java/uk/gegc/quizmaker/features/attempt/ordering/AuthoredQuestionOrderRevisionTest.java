package uk.gegc.quizmaker.features.attempt.ordering;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.AuthoredQuestionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.AuthoredQuestionOrder.Input;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.QuestionOrderingException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;

class AuthoredQuestionOrderRevisionTest {
    private static final UUID REVISION = new UUID(0, 1);
    private static final UUID FIRST = new UUID(0, 10);
    private static final UUID SECOND = new UUID(0, 20);
    private static final Instant CREATED = Instant.parse("2026-10-27T12:00:00Z");

    @Test
    void realRevisionAndPolicy_whenProjected_preserveOccurrenceIdsAndExcludeProtectedContent() {
        var revision = revision(REVISION, 1, 7);
        var input = Input.fromRevision(policy(REVISION), revision, List.of(SECOND, FIRST));
        var result = AuthoredQuestionOrder.order(input);

        assertThat(result.itemIds()).containsExactly(FIRST, SECOND);
        assertThat(result.evidence().quizRevisionId()).isEqualTo(REVISION);
        assertThat(result.evidence().revisionSchemaVersion()).isEqualTo(1);
        assertThat(result.evidence().version()).isEqualTo(1);
        assertThat(result.evidence().policy()).isEqualTo(new QuestionOrderingPolicy(QuestionOrder.AUTHORED, 1));
        // Two occurrences of the same question revision must not collapse into one question ID.
        assertThat(revision.items()).extracting(item -> item.question().id()).containsExactly(new UUID(0, 99), new UUID(0, 99));
        assertThat(input.toString()).doesNotContain("private-question", "{\"answer\":true}", "private-hint", "private-explanation");
        assertThat(result.toString()).doesNotContain("private-question", "{\"answer\":true}", "private-hint", "private-explanation");
    }

    @Test
    void laterRevision_whenAuthorMovesItems_changesNewOrderWithoutRewritingOldInputOrIdentity() {
        var originalInput = Input.fromRevision(policy(REVISION), revision(REVISION, 1, 7), List.of(SECOND, FIRST));
        UUID newerId = new UUID(0, 2);
        var newerInput = Input.fromRevision(policy(newerId), revision(newerId, 7, 1), List.of(SECOND, FIRST));

        assertThat(AuthoredQuestionOrder.order(originalInput).itemIds()).containsExactly(FIRST, SECOND);
        assertThat(AuthoredQuestionOrder.order(newerInput).itemIds()).containsExactly(SECOND, FIRST);
        assertThat(AuthoredQuestionOrder.order(originalInput).itemIds()).containsExactly(FIRST, SECOND);
        assertThat(AuthoredQuestionOrder.order(newerInput).evidence().quizRevisionId()).isEqualTo(newerId);
    }

    @Test
    void policyForOtherRevision_whenRealSourceIsProjected_rejectsMismatchWithoutEchoingIds() {
        assertThatThrownBy(() -> Input.fromRevision(policy(new UUID(0, 2)), revision(REVISION, 1, 7), List.of(FIRST)))
                .isInstanceOfSatisfying(QuestionOrderingException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(QuestionOrderingException.Reason.REVISION_MISMATCH))
                .hasMessage("Question ordering rejected: REVISION_MISMATCH");
    }

    @Test
    void missingRealSourceOrPolicy_whenProjected_rejectsRatherThanReadingMutableAuthoringData() {
        assertThatThrownBy(() -> Input.fromRevision(null, revision(REVISION, 1, 7), List.of(FIRST)))
                .isInstanceOf(QuestionOrderingException.class).hasMessage("Question ordering rejected: INVALID_INPUT");
        assertThatThrownBy(() -> Input.fromRevision(policy(REVISION), null, List.of(FIRST)))
                .isInstanceOf(QuestionOrderingException.class).hasMessage("Question ordering rejected: INVALID_INPUT");
    }

    private static ResolvedAttemptPolicy policy(UUID revisionId) {
        return BuiltInPracticePreset.V1.bindContent(new ContentPolicy(revisionId, 1,
                FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED));
    }

    private static QuizRevision revision(UUID id, int firstPosition, int secondPosition) {
        var question = new QuestionRevision(new UUID(0, 99), new UUID(0, 100), 1,
                QuestionType.TRUE_FALSE, Difficulty.EASY, "private-question", "{\"answer\":true}",
                "private-hint", "private-explanation", null, null, CREATED, List.of());
        return new QuizRevision(id, new UUID(0, 3), new UUID(0, 4), 1, "Quiz", null, CREATED, List.of(
                new QuizRevision.Item(SECOND, secondPosition, question),
                new QuizRevision.Item(FIRST, firstPosition, question)));
    }
}
