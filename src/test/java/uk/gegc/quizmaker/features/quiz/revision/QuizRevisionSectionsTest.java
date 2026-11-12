package uk.gegc.quizmaker.features.quiz.revision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.quiz.revision.RevisionFixtures.*;

class QuizRevisionSectionsTest {
    static QuizRevision structured(String key) {
        var flat = quiz(key, question(key + "-one"), question(key + "-two"), question(key + "-three"));
        return withSections(flat, List.of(
                new RevisionSection(id(key + "-theory"), 1, 2, "Theory", "Read carefully", List.of(flat.items().get(0).id())),
                new RevisionSection(id(key + "-practice"), 1, 9, "Practice", null,
                        flat.items().subList(1, 3).stream().map(QuizRevision.Item::id).toList())));
    }

    static QuizRevision withSections(QuizRevision base, List<RevisionSection> sections) {
        return new QuizRevision(base.id(), base.quizId(), base.ownerId(), 2, base.title(), base.description(),
                base.createdAt(), base.items(), sections);
    }

    @Test
    void sortsSectionsWithoutRenumberingOrChangingStableIdentityAndCopiesInputs() {
        var expected = structured("ordered");
        var input = new ArrayList<>(List.of(expected.sections().get(1), expected.sections().get(0)));
        var actual = withSections(expected, input);
        input.clear();
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.sections()).extracting(RevisionSection::authoredPosition).containsExactly(2, 9);
        assertThatThrownBy(() -> actual.sections().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> actual.sections().get(0).itemIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(actual.structured()).isTrue();
    }

    @Test
    void rejectsDuplicateIdentityPositionMissingForeignRepeatedAndReorderedItemBindings() {
        var revision = structured("partition");
        var a = revision.sections().get(0); var b = revision.sections().get(1);
        List<List<RevisionSection>> invalid = List.of(
                List.of(), List.of(a), List.of(a, a),
                List.of(a, section(b, a.id(), b.authoredPosition(), b.itemIds())),
                List.of(a, section(b, b.id(), a.authoredPosition(), b.itemIds())),
                List.of(a, section(b, b.id(), b.authoredPosition(), List.of(id("foreign")))),
                List.of(a, section(b, b.id(), b.authoredPosition(), List.of(a.itemIds().get(0)))),
                List.of(a, section(b, b.id(), b.authoredPosition(), List.of(b.itemIds().get(1), b.itemIds().get(0)))));
        for (var sections : invalid) assertThatThrownBy(() -> withSections(revision, sections))
                .isInstanceOf(RevisionIntegrityException.class).hasNoCause();
        assertThat(withSections(revision, revision.sections())).isEqualTo(revision);
    }

    @Test
    void validatesSectionMetadataAndFormatVersionsWithoutRelaxingTheFlatConstructor() {
        var revision = structured("validation"); var a = revision.sections().get(0);
        for (int version : List.of(0, 2, Integer.MAX_VALUE)) assertThatThrownBy(() -> new RevisionSection(
                a.id(), version, a.authoredPosition(), a.title(), null, a.itemIds()))
                .isInstanceOf(RevisionIntegrityException.class).hasMessageContaining("UNSUPPORTED_VERSION");
        assertThatThrownBy(() -> section(a, null, 0, a.itemIds())).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> section(a, a.id(), -1, a.itemIds())).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> section(a, a.id(), 0, List.of(a.itemIds().get(0), a.itemIds().get(0))))
                .isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> new RevisionSection(a.id(), 1, 0, " ", null, List.of())).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> new RevisionSection(a.id(), 1, 0, "x".repeat(101), null, List.of())).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> new RevisionSection(a.id(), 1, 0, "Title", "x".repeat(1001), List.of())).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> new QuizRevision(revision.id(), revision.quizId(), OWNER, 2, "Title", null, CREATED, revision.items()))
                .isInstanceOf(RevisionIntegrityException.class).hasMessageContaining("UNSUPPORTED_VERSION");
        assertThatThrownBy(() -> new QuizRevision(revision.id(), revision.quizId(), OWNER, 1, "Title", null, CREATED,
                revision.items(), revision.sections())).isInstanceOf(RevisionIntegrityException.class);
        assertThat(new RevisionSection(a.id(), 1, 0, "x".repeat(100), "x".repeat(1000), List.of()).instructions()).hasSize(1000);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 3})
    void flatAdapterKeepsExactItemOrderWithoutInventingAuthoredMetadata(int size) {
        var flat = quiz("flat-" + size, java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> question("flat-" + size + "-" + i)).toArray(QuestionRevision[]::new));
        var adapted = RevisionStructure.from(flat);
        assertThat(adapted.provenance()).isEqualTo(RevisionStructure.Provenance.IMPLICIT_FLAT);
        assertThat(adapted.revisionId()).isEqualTo(flat.id());
        assertThat(adapted.sections()).singleElement().satisfies(section -> {
            assertThat(section.implicit()).isTrue();
            assertThat(section.title()).isNull(); assertThat(section.instructions()).isNull();
            assertThat(section.itemIds()).containsExactlyElementsOf(flat.items().stream().map(QuizRevision.Item::id).toList());
        });
        assertThat(RevisionStructure.from(flat)).isEqualTo(adapted);
        assertThat(RevisionStructure.from(quiz("other-flat")).sections().get(0).id()).isNotEqualTo(adapted.sections().get(0).id());
        assertThat(flat.structured()).isFalse();
    }

    @Test
    void legacyHasNoFabricatedRevisionOrSectionAndEmptyStructureDoesNotAuthorizeExecution() {
        var legacy = RevisionStructure.legacy();
        assertThat(legacy.provenance()).isEqualTo(RevisionStructure.Provenance.LEGACY);
        assertThat(legacy.revisionId()).isNull(); assertThat(legacy.sections()).isEmpty();
        assertThat(new RevisionReference(null).provenance()).isEqualTo(RevisionReference.Provenance.LEGACY);
        var empty = withSections(quiz("empty"), List.of(new RevisionSection(id("empty-section"), 1, 0, "Empty", null, List.of())));
        assertThat(RevisionStructure.from(empty).sections()).singleElement().satisfies(s -> assertThat(s.itemIds()).isEmpty());
    }

    @Test
    void structuralProjectionContainsNoQuestionAnswerOrContactFields() throws Exception {
        var revision = structured("redaction"); var view = RevisionStructure.from(revision);
        assertThat(view.sections()).extracting(RevisionStructure.Section::id)
                .containsExactlyElementsOf(revision.sections().stream().map(RevisionSection::id).toList());
        assertThat(new ObjectMapper().writeValueAsString(view)).doesNotContain("Paris", "correct", "questionText", "content",
                "explanation", "hint", "email", "ownerId", "questionId");
        assertThat(view.toString() + view.sections() + revision.sections()).doesNotContain("Theory", "Practice", "Read carefully");
    }

    private static RevisionSection section(RevisionSection base, UUID id, int position, List<UUID> items) {
        return new RevisionSection(id, 1, position, base.title(), base.instructions(), items);
    }
}
