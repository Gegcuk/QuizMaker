package uk.gegc.quizmaker.shared.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Thin facade over {@link AppPermissionEvaluator} that applies resource-level
 * authorization rules (owner vs moderator/admin, organization membership, etc.)
 * in a single place. Keeps feature services focused on business logic while
 * enabling multi-tenant scenarios when they are introduced.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccessPolicy {

    private static final String DEFAULT_FORBIDDEN_MESSAGE = "Access denied";

    private final AppPermissionEvaluator permissionEvaluator;
    private final MembershipResolver membershipResolver;

    public boolean isOwner(User user, UUID ownerId) {
        return user != null && ownerId != null && ownerId.equals(user.getId());
    }

    public void requireOwner(User user, UUID ownerId) {
        if (!isOwner(user, ownerId)) {
            throwForbidden("Owner required");
        }
    }

    public boolean hasAny(User user, PermissionName... permissions) {
        if (user == null || permissions == null || permissions.length == 0) {
            return false;
        }

        return Arrays.stream(permissions)
                .filter(Objects::nonNull)
                .anyMatch(permission -> permissionEvaluator.hasPermission(user, permission));
    }

    public void requireAny(User user, PermissionName... permissions) {
        if (!hasAny(user, permissions)) {
            throwForbidden("Required permission missing");
        }
    }

    public void requireOwnerOrAny(User user, UUID ownerId, PermissionName... permissions) {
        if (isOwner(user, ownerId)) {
            return;
        }
        if (hasAny(user, permissions)) {
            return;
        }
        throwForbidden("Owner or elevated permission required");
    }

    public void requireOwnerOrMemberOrAny(User user, OwnerRef ownerRef, PermissionName... permissions) {
        if (ownerRef == null) {
            throwForbidden("Owner reference missing");
        }

        if (ownerRef.type() == OwnerType.USER && isOwner(user, ownerRef.id())) {
            return;
        }

        UUID userId = user != null ? user.getId() : null;
        if (userId != null) {
            if (ownerRef.type() == OwnerType.GROUP && membershipResolver.isMemberOfGroup(userId, ownerRef.id())) {
                return;
            }
            if (ownerRef.type() == OwnerType.ORGANIZATION && membershipResolver.isMemberOfOrganization(userId, ownerRef.id())) {
                return;
            }
        }

        if (hasAny(user, permissions)) {
            return;
        }

        throwForbidden("Owner/member or elevated permission required");
    }

    public void requireSameOrganization(User user, UUID organizationId) {
        if (organizationId == null) {
            throwForbidden("Organization scope is required");
        }
        UUID userId = user != null ? user.getId() : null;
        if (userId != null && membershipResolver.isMemberOfOrganization(userId, organizationId)) {
            return;
        }
        throwForbidden("Organization membership required");
    }

    public void requireOrganizationRoleOrAny(User user, UUID organizationId, String[] roles, PermissionName... permissions) {
        UUID userId = user != null ? user.getId() : null;
        if (userId != null && organizationId != null) {
            if (roles != null) {
                for (String role : roles) {
                    if (role != null && membershipResolver.hasOrganizationRole(userId, organizationId, role)) {
                        return;
                    }
                }
            }
        }

        if (hasAny(user, permissions)) {
            return;
        }

        throwForbidden("Organization role or elevated permission required");
    }

    private void throwForbidden(String message) {
        String msg = message != null ? message : DEFAULT_FORBIDDEN_MESSAGE;
        log.debug("AccessPolicy denying access: {}", msg);
        throw new ForbiddenException(msg);
    }
}
