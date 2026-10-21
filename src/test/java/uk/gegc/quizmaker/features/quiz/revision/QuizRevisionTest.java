package uk.gegc.quizmaker.features.quiz.revision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionReference;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.quiz.revision.RevisionFixtures.*;

class QuizRevisionTest {
    @Test
    void collectionsAndNestedValuesCannotBeChangedAfterConstruction() {
        var question = question("immutable");
        var items = new ArrayList<>(quiz("immutable", question).items());
        var revision = new QuizRevision(id("immutable"), id("quiz"), OWNER, 1, "Title", null, CREATED, items);
        items.clear();
        assertThat(revision.items()).hasSize(1);
        assertThatThrownBy(() -> revision.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> question.options().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void identityIsIndependentOfItemAndOptionPositions() {
        var question = question("order");
        var a = question.options().get(0);
        var b = question.options().get(1);
        var reordered = copy(question, question.content(), List.of(
                new QuestionRevision.Option(a.id(), a.sourceKey(), 7),
                new QuestionRevision.Option(b.id(), b.sourceKey(), 2)));
        assertThat(reordered.options()).extracting(QuestionRevision.Option::id).containsExactly(b.id(), a.id());
        var original = quiz("order", question, question("other"));
        var first = original.items().get(0);
        var second = original.items().get(1);
        var moved = new QuizRevision(id("moved"), original.quizId(), OWNER, 1, "Title", null, CREATED, List.of(
                new QuizRevision.Item(first.id(), 3, first.question()),
                new QuizRevision.Item(second.id(), 1, second.question())));
        assertThat(moved.items()).extracting(QuizRevision.Item::id).containsExactly(second.id(), first.id());
    }

    @ParameterizedTest
    @EnumSource(QuestionType.class)
    void storesEveryExistingFamilyWithoutChangingItsPayload(QuestionType type) {
        var base = question("family");
        String content = QuestionContracts.example(type, QuestionContracts.View.AUTHOR).toString();
        var revision = new QuestionRevision(base.id(), base.questionId(), 1, type, base.difficulty(),
                base.questionText(), content, base.hint(), base.explanation(), null, null, CREATED, List.of());
        assertThatCode(() -> QuestionContracts.read(type, QuestionContracts.View.AUTHOR, revision.content()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "[]", "null", "{bad", "{} {}", "{\"a\":1,\"a\":2}"})
    void rejectsMalformedOrAmbiguousContentWithoutEchoingIt(String content) {
        assertThatThrownBy(() -> copy(question("invalid"), content, List.of()))
                .isInstanceOf(RevisionIntegrityException.class)
                .hasMessage("Immutable revision rejected: INVALID_CONTENT");
    }

    @Test
    void normalizesJsonObjectKeysButRetainsArrayOrderAndTimestampPrecision() {
        var base = question("json");
        assertThat(copy(base, "{\"z\":[2,1],\"a\":{\"y\":1,\"b\":2}}", List.of()).content())
                .isEqualTo("{\"a\":{\"b\":2,\"y\":1},\"z\":[2,1]}");
        var revision = new QuizRevision(id("time"), id("quiz"), OWNER, 1, "Title", null,
                Instant.parse("2026-10-21T12:00:00.123456789Z"), List.of());
        assertThat(revision.createdAt()).isEqualTo(CREATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "18446744073709551617",
            "0.1234567890123456789012345678901234567890",
            "12345678901234567890.00000000000000000001",
            "1.234567890123456789E+100",
            "1.234567890123456789E-100",
            "1E+400",
            "1E-400"
    })
    void normalizingAndReconstructingContentDoesNotRoundNumbers(String number) {
        String content = "{\"answer\":true,\"metadata\":" + number + "}";
        QuestionContracts.read(QuestionType.TRUE_FALSE, QuestionContracts.View.AUTHOR, content);
        var base = question("numeric");
        var accepted = new QuestionRevision(base.id(), base.questionId(), 1, QuestionType.TRUE_FALSE,
                base.difficulty(), base.questionText(), content, null, null, null, null, CREATED, List.of());
        assertThat(accepted.content()).isEqualTo(content);
        assertThat(copy(accepted, accepted.content(), accepted.options())).isEqualTo(accepted);
    }

    @Test
    void rejectsDuplicateOptionIdentityKeyAndPosition() {
        var base = question("duplicate");
        var a = base.options().get(0);
        assertThatThrownBy(() -> copy(base, base.content(), List.of(a, a))).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> copy(base, base.content(), List.of(a,
                new QuestionRevision.Option(id("another"), a.sourceKey(), 2))))
                .isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> copy(base, base.content(), List.of(a,
                new QuestionRevision.Option(id("another"), "different", a.authoredPosition()))))
                .isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> new QuestionRevision.Option(null, "a", 0)).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> new QuestionRevision.Option(id("a"), "a", -1)).isInstanceOf(RevisionIntegrityException.class);
    }

    @Test
    void rejectsUnknownVersionsMissingIdentityAndConflictingQuestionSnapshots() {
        var base = quiz("invalid", question("q"));
        assertThatThrownBy(() -> new QuizRevision(base.id(), base.quizId(), OWNER, 2, "Title", null, CREATED, List.of()))
                .isInstanceOf(RevisionIntegrityException.class).hasMessageContaining("UNSUPPORTED_VERSION");
        assertThatThrownBy(() -> new QuizRevision(null, base.quizId(), OWNER, 1, "Title", null, CREATED, List.of()))
                .isInstanceOf(RevisionIntegrityException.class);
        var q = question("q");
        assertThatThrownBy(() -> quiz("conflict", q, copy(q, "{\"different\":true}", q.options())))
                .isInstanceOf(RevisionIntegrityException.class);
        var item = base.items().get(0);
        assertThatThrownBy(() -> new QuizRevision(base.id(), base.quizId(), OWNER, 1, "Title", null, CREATED, List.of(item, item)))
                .isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> new QuizRevision(base.id(), base.quizId(), OWNER, 1, " ", null, CREATED, List.of()))
                .isInstanceOf(RevisionIntegrityException.class);
    }

    @Test
    void legacyIdentityNeverBecomesAnInventedTargetRevision() {
        var legacy = new RevisionReference(null);
        assertThat(legacy.provenance()).isEqualTo(RevisionReference.Provenance.LEGACY);
        assertThatThrownBy(legacy::requireRevisionId).isInstanceOf(RevisionIntegrityException.class)
                .hasMessageContaining("LEGACY_IDENTITY");
        var bound = new RevisionReference(id("known"));
        assertThat(bound.provenance()).isEqualTo(RevisionReference.Provenance.REVISION_BOUND);
        assertThat(bound.requireRevisionId()).isEqualTo(id("known"));
    }

    private QuestionRevision copy(QuestionRevision q, String content, List<QuestionRevision.Option> options) {
        return new QuestionRevision(q.id(), q.questionId(), q.schemaVersion(), q.type(), q.difficulty(), q.questionText(),
                content, q.hint(), q.explanation(), q.attachmentUrl(), q.attachmentAssetId(), q.createdAt(), options);
    }
}
