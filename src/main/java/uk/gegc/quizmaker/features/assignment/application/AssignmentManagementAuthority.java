package uk.gegc.quizmaker.features.assignment.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.Scope;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException.require;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException.Reason.UNAVAILABLE;

/**
 * Current assignment-management authority, separate from source visibility and membership.
 * Resolve the named permission for this actor, operation and exact assignment/scope inside the
 * caller transaction, retaining necessary authority protection through commit. Global roles plus
 * organization membership are not scoped authority. Context contains a request, never a grant;
 * the service supplies its authenticated actor and persisted scope for an existing assignment.
 * Missing/ambiguous providers deny all operations, including reads and exact receipt replay.
 * Real scope-role/grant integration is required before exposing authoring; #835 still owns
 * delegation/transfer. Implementations must sanitize failures before their own proxy boundary.
 */
public interface AssignmentManagementAuthority {
    boolean allows(Context context);

    enum Action {
        CREATE_DRAFT(PermissionName.ASSIGNMENT_CREATE),
        SAVE_DRAFT(PermissionName.ASSIGNMENT_CREATE),
        READ_DRAFT(PermissionName.ASSIGNMENT_CREATE),
        PUBLISH(PermissionName.ASSIGNMENT_PUBLISH),
        READ_CURRENT(PermissionName.ASSIGNMENT_PUBLISH),
        READ_PUBLICATION(PermissionName.ASSIGNMENT_PUBLISH);

        private final PermissionName permission;
        Action(PermissionName permission) { this.permission = permission; }
        public PermissionName permission() { return permission; }
    }

    record Context(UUID actorId, Action action, UUID assignmentId, Scope scope) {
        public Context {
            require(actorId != null && action != null && assignmentId != null && scope != null, UNAVAILABLE);
        }
        @Override public String toString() { return "AssignmentManagementContext[action=" + action + "]"; }
    }
}
