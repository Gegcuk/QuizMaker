package uk.gegc.quizmaker.features.assignment.domain.model;

import java.util.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException.*;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException.Reason.*;

/** Internal commands have no client-selected owner. Each receipt retains its exact result. */
public final class AssignmentPublication {
    private AssignmentPublication() { }
    public enum Operation { CREATE, SAVE, PUBLISH }
    public record Save(UUID assignmentId, long expectedVersion, UUID commandId, Scope scope, Definition definition) {
        @Override public String toString() { return "AssignmentSave[redacted]"; }
    }
    public record Publish(UUID assignmentId, long expectedVersion, UUID commandId) {
        @Override public String toString() { return "AssignmentPublish[redacted]"; }
    }
    public record Captured(Snapshot snapshot, Map<UUID, String> designations) {
        public Captured {
            require(snapshot != null && designations != null, INVALID_INPUT);
            require(designations.keySet().equals(new HashSet<>(snapshot.definition().audience().memberIds())), INVALID_REFERENCE);
            require(designations.values().stream().allMatch(v -> v != null && !v.isBlank()
                    && v.codePoints().noneMatch(Character::isISOControl)), INVALID_REFERENCE);
            designations = Collections.unmodifiableMap(new TreeMap<>(designations));
        }
        @Override public String toString() { return "AssignmentCapture[redacted]"; }
    }
    public record Result(long version, Captured captured, boolean replayed) {
        @Override public String toString() { return "AssignmentResult[version=" + version + ", replayed=" + replayed + "]"; }
    }
    public record Head(UUID assignmentId, UUID ownerId, Scope scope, long version, UUID draftId,
                       UUID publicationId, UUID publishedDraftId) {
        @Override public String toString() { return "AssignmentHead[version=" + version + "]"; }
    }
    public record Receipt(Operation operation, String fingerprint, long version, UUID snapshotId) {
        @Override public String toString() { return "AssignmentReceipt[operation=" + operation + ", version=" + version + "]"; }
    }
}
