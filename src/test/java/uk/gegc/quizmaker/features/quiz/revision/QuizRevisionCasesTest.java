package uk.gegc.quizmaker.features.quiz.revision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.quiz.revision.RevisionFixtures.*;

class QuizRevisionCasesTest {
    static QuizRevision cased(String key) { return cased(key, 1); }

    static QuizRevision cased(String key, int blocksPerSection) {
        var flat = quiz(key, IntStream.range(0, blocksPerSection * 4)
                .mapToObj(i -> question(key + "-q-" + i)).toArray(QuestionRevision[]::new));
        var sections = new ArrayList<RevisionSection>();
        var blocks = new ArrayList<RevisionCaseBlock>();
        for (int section = 0; section < 2; section++) {
            var items = flat.items().subList(section * blocksPerSection * 2, (section + 1) * blocksPerSection * 2)
                    .stream().map(QuizRevision.Item::id).toList();
            var sectionId = id(key + "-section-" + section);
            sections.add(new RevisionSection(sectionId, 1, section * 7, "Case section", null, items));
            for (int block = 0; block < blocksPerSection; block++) blocks.add(new RevisionCaseBlock(
                    id(key + "-case-" + section + "-" + block), 1, sectionId, block * 3,
                    id(key + "-stimulus-" + section + "-" + block), section == 0,
                    List.of(items.get(block * 2 + 1), items.get(block * 2))));
        }
        return withCases(flat, sections, blocks);
    }

    static QuizRevision withCases(QuizRevision base, List<RevisionSection> sections, List<RevisionCaseBlock> blocks) {
        return new QuizRevision(base.id(), base.quizId(), base.ownerId(), 3, base.title(), base.description(),
                base.createdAt(), base.items(), sections, blocks);
    }

    static RevisionCaseBlock block(RevisionCaseBlock base, UUID id, UUID section, int position, List<UUID> members) {
        return new RevisionCaseBlock(id, 1, section, position, base.stimulusRevisionId(), base.atomic(), members);
    }

    @Test void retainsAuthoredMemberOrderReferencesAtomicityAndDefensiveCopies() {
        var expected = cased("ordered", 2);
        var blocks = new ArrayList<>(expected.caseBlocks());
        java.util.Collections.reverse(blocks);
        var actual = withCases(expected, expected.sections(), blocks);
        blocks.clear();
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.structured()).isTrue();
        assertThat(actual.caseBlocks()).extracting(RevisionCaseBlock::atomic).containsExactly(true, true, false, false);
        assertThat(actual.caseBlocks().get(0).itemIds()).containsExactly(actual.items().get(1).id(), actual.items().get(0).id());
        var members = new ArrayList<>(actual.caseBlocks().get(0).itemIds());
        var copy = block(actual.caseBlocks().get(0), id("copy"), actual.sections().get(0).id(), 19, members);
        members.clear();
        assertThat(copy.itemIds()).hasSize(2);
        assertThatThrownBy(() -> copy.itemIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> actual.caseBlocks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsInvalidMissingForeignCrossSectionAndOverlappingMembership() {
        var revision = cased("invalid"); var a = revision.caseBlocks().get(0); var b = revision.caseBlocks().get(1);
        var variants = List.of(
                List.<RevisionCaseBlock>of(), List.of(a, a),
                List.of(a, block(b, a.id(), b.sectionId(), b.authoredPosition(), b.itemIds())),
                List.of(a, block(b, b.id(), a.sectionId(), a.authoredPosition(), a.itemIds())),
                List.of(block(a, a.id(), id("missing-section"), 0, a.itemIds())),
                List.of(block(a, a.id(), a.sectionId(), 0, List.of(id("missing-item")))),
                List.of(block(a, a.id(), a.sectionId(), 0, b.itemIds())),
                List.of(a, block(a, id("overlap"), a.sectionId(), 1, a.itemIds())),
                List.of(block(a, a.id(), a.sectionId(), 0, cased("other").caseBlocks().get(0).itemIds())));
        for (var blocks : variants) assertThatThrownBy(() -> withCases(revision, revision.sections(), blocks))
                .isInstanceOf(RevisionIntegrityException.class).hasNoCause();
        assertThat(withCases(revision, revision.sections(), List.of(a))).isNotNull(); // Ordinary items need not belong to a case.
    }

    @Test void validatesVersionIdentityAndNonemptyUniqueMembersWithoutInventingReferences() {
        var a = cased("metadata").caseBlocks().get(0);
        for (int version : List.of(0, 2, Integer.MAX_VALUE)) assertThatThrownBy(() -> new RevisionCaseBlock(
                a.id(), version, a.sectionId(), 0, a.stimulusRevisionId(), true, a.itemIds()))
                .isInstanceOf(RevisionIntegrityException.class).hasMessageContaining("UNSUPPORTED_VERSION");
        for (List<UUID> members : List.of(List.<UUID>of(), List.of(a.itemIds().get(0), a.itemIds().get(0))))
            assertThatThrownBy(() -> block(a, a.id(), a.sectionId(), 0, members)).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> new RevisionCaseBlock(null, 1, a.sectionId(), 0, a.stimulusRevisionId(), true, a.itemIds()))
                .isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> new RevisionCaseBlock(a.id(), 1, null, 0, a.stimulusRevisionId(), true, a.itemIds()))
                .isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> new RevisionCaseBlock(a.id(), 1, a.sectionId(), -1, a.stimulusRevisionId(), true, a.itemIds()))
                .isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> new RevisionCaseBlock(a.id(), 1, a.sectionId(), 0, null, true, a.itemIds()))
                .isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> block(a, a.id(), a.sectionId(), 0, null)).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> block(a, a.id(), a.sectionId(), 0, java.util.Arrays.asList((UUID) null)))
                .isInstanceOf(RevisionIntegrityException.class);
    }

    @Test void casesRequireExplicitFormatAndOldFlatAndSectionOnlyFormatsRemainCaseFree() {
        var revision = cased("format");
        for (int version : List.of(1, 2, 4)) assertThatThrownBy(() -> new QuizRevision(revision.id(), revision.quizId(), OWNER,
                version, "Title", null, CREATED, revision.items(), revision.sections(), revision.caseBlocks()))
                .isInstanceOf(RevisionIntegrityException.class);
        var flat = quiz("flat"); var sections = QuizRevisionSectionsTest.structured("sections");
        assertThat(flat.caseBlocks()).isEmpty(); assertThat(sections.caseBlocks()).isEmpty();
        assertThat(RevisionStructure.from(flat).caseBlocks()).isEmpty();
        assertThat(RevisionStructure.from(sections).caseBlocks()).isEmpty();
        assertThat(RevisionStructure.legacy().caseBlocks()).isEmpty();
    }

    @Test void safeStructureHasExactMembershipButNoAuthorStimulusReferenceOrQuestionContent() throws Exception {
        var revision = cased("privacy"); var structure = RevisionStructure.from(revision);
        assertThat(structure.caseBlocks()).extracting(RevisionStructure.CaseBlock::itemIds)
                .containsExactlyElementsOf(revision.caseBlocks().stream().map(RevisionCaseBlock::itemIds).toList());
        String json = new ObjectMapper().writeValueAsString(structure);
        assertThat(json).doesNotContain("stimulus", "correct", "Paris", "questionText", "explanation", "email");
        for (var block : revision.caseBlocks()) {
            assertThat(json).doesNotContain(block.stimulusRevisionId().toString());
            assertThat(block.toString()).doesNotContain(block.stimulusRevisionId().toString());
        }
    }
}
