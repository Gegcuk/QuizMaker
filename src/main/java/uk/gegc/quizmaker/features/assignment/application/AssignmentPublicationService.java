package uk.gegc.quizmaker.features.assignment.application;

import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublication.*;

/**
 * Internal author boundary; no assignment HTTP route or participant activation.
 * Owns RC or joins an explicitly RC writable transaction; participating returns are provisional
 * until the caller commits. On uncertain outcome retain and retry the exact command identity.
 * Result.version is the aggregate optimistic version; snapshot.version independently numbers
 * drafts/publications. Read receipts replay their original acknowledgement version.
 * Every operation requires current AssignmentManagementAuthority for the authenticated actor
 * and exact assignment/scope. Missing authority integration denies reads and replay as well.
 */
public interface AssignmentPublicationService {
    Result create(Save command);
    Result save(Save command);
    Result publish(Publish command);
    Result draft(UUID assignmentId);
    Optional<Result> current(UUID assignmentId);
    Result read(UUID assignmentId, UUID publicationId);
}
