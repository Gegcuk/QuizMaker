package uk.gegc.quizmaker.features.learning.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSet.*;

/** Self-scoped organization and selection. Nested session start remains gated by #667. */
public interface LearningSetService {
    record Create(UUID commandId, String name) { @Override public String toString() { return "CreateLearningSet[redacted]"; } }
    record Change(UUID commandId, long expectedVersion, Member member, boolean present) {
        @Override public String toString() { return "ChangeLearningSetMember[redacted]"; }
    }
    record Delete(UUID commandId, long expectedVersion) {
        @Override public String toString() { return "DeleteLearningSet[redacted]"; }
    }
    Acknowledgement create(Create request);
    Acknowledgement change(UUID setId, Change request);
    Acknowledgement delete(UUID setId, Delete request);
    View read(UUID setId);
    SetPage list(int limit, UUID after);
    MemberPage members(UUID setId, int limit, String after);
    ItemPage items(Scope scope, Window window, int limit, String after);
}
