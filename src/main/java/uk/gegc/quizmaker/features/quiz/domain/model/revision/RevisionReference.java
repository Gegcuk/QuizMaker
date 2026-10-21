package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import java.util.UUID;

/** Classification of persisted identity metadata, never reconstruction from current authoring content. */
public record RevisionReference(UUID revisionId) {
    public enum Provenance { LEGACY, REVISION_BOUND }

    public Provenance provenance() {
        return revisionId == null ? Provenance.LEGACY : Provenance.REVISION_BOUND;
    }

    public UUID requireRevisionId() {
        if (revisionId == null) {
            throw new RevisionIntegrityException(RevisionIntegrityException.Reason.LEGACY_IDENTITY);
        }
        return revisionId;
    }
}
