package uk.gegc.quizmaker.features.assignment.domain.repository;

import java.time.Instant;
import java.util.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublication.*;

/** Scalar JDBC persistence, joining the application transaction. */
public interface AssignmentPublicationRepository {
    Set<String> permissions(UUID actorId);
    void create(UUID id, UUID ownerId, Scope scope, Instant now);
    Optional<Head> head(UUID id, boolean lock);
    Optional<Receipt> receipt(UUID assignmentId, UUID actorId, UUID commandId);
    Optional<Captured> snapshot(UUID assignmentId, UUID id, Status status);
    boolean revisionExists(UUID id, int schemaVersion);
    void insert(Captured captured);
    void point(Head before, Head after);
    void record(UUID assignmentId, UUID actorId, UUID commandId, Receipt receipt, Instant now);
}
