package uk.gegc.quizmaker.shared.security;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Default MembershipResolver that always returns {@code false}.
 * Feature modules can provide their own {@link MembershipResolver}
 * beans (optionally marked {@code @Primary}) to override this
 * behaviour when group/organization functionality is introduced.
 */
@Component
@Primary
public class NoOpMembershipResolver implements MembershipResolver {

    @Override
    public boolean isMemberOfGroup(UUID userId, UUID groupId) {
        return false;
    }

    @Override
    public boolean isMemberOfOrganization(UUID userId, UUID organizationId) {
        return false;
    }

    @Override
    public boolean hasGroupRole(UUID userId, UUID groupId, String role) {
        return false;
    }

    @Override
    public boolean hasOrganizationRole(UUID userId, UUID organizationId, String role) {
        return false;
    }
}
