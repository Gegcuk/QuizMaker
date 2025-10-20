package uk.gegc.quizmaker.shared.security;

import java.util.UUID;

/**
 * SPI that feature modules (groups, organizations, companies, etc.)
 * can implement to expose membership and role lookup logic. AccessPolicy
 * depends on this interface instead of concrete implementations so that
 * the security layer stays decoupled from feature packages.
 */
public interface MembershipResolver {

    default boolean isMemberOfGroup(UUID userId, UUID groupId) {
        return false;
    }

    default boolean isMemberOfOrganization(UUID userId, UUID organizationId) {
        return false;
    }

    default boolean hasGroupRole(UUID userId, UUID groupId, String role) {
        return false;
    }

    default boolean hasOrganizationRole(UUID userId, UUID organizationId, String role) {
        return false;
    }
}
