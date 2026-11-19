package uk.gegc.quizmaker.features.learning.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession;

/**
 * Dark #575 boundary. Identity is resolved from the current authenticated principal, never from
 * request data. No endpoint, form, enrollment, scheduler or assessment mutation is provided.
 * Standalone calls own RC; participating calls require explicit writable outer RC. A participating
 * return is provisional until its caller commits. Reconcile uncertain creation with the same key.
 */
public interface LearningSessionService {
    record Create(int version, UUID commandId, LearningSession.Source source, FsrsManifest configuration) {
        @Override public String toString() { return "LearningSessionCreate[version=" + version + "]"; }
    }
    record Result(LearningSession session, boolean replayed) {
        @Override public String toString() { return "LearningSessionResult[replayed=" + replayed + "]"; }
    }

    Result create(Create command);
    LearningSession read(UUID sessionId);
}
