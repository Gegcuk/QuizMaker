package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Internal author-side structure view, not participant delivery or reconstructed legacy history. */
public record RevisionStructure(Provenance provenance, UUID revisionId, List<Section> sections) {
    public enum Provenance { LEGACY, IMPLICIT_FLAT, EXPLICIT }
    public record Section(UUID id, int schemaVersion, int authoredPosition, boolean implicit,
                          String title, String instructions, List<UUID> itemIds) {
        public Section { itemIds = List.copyOf(itemIds); }
        @Override public String toString() { return "StructureSection[implicit=" + implicit + "]"; }
    }

    public RevisionStructure { sections = List.copyOf(sections); }

    public static RevisionStructure legacy() { return new RevisionStructure(Provenance.LEGACY, null, List.of()); }

    public static RevisionStructure from(QuizRevision revision) {
        if (!revision.structured()) {
            // Bounded identity metadata only; never read/hash document or question content.
            UUID implicitId = UUID.nameUUIDFromBytes(("quizmaker:implicit-section:v1:" + revision.id())
                    .getBytes(StandardCharsets.UTF_8));
            return new RevisionStructure(Provenance.IMPLICIT_FLAT, revision.id(), List.of(new Section(
                    implicitId, 1, 0, true, null, null, revision.items().stream().map(QuizRevision.Item::id).toList())));
        }
        return new RevisionStructure(Provenance.EXPLICIT, revision.id(), revision.sections().stream()
                .map(section -> new Section(section.id(), section.schemaVersion(), section.authoredPosition(), false,
                        section.title(), section.instructions(), section.itemIds())).toList());
    }

    @Override public String toString() { return "RevisionStructure[provenance=" + provenance + "]"; }
}
