package uk.gegc.quizmaker.shared.security;

import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight value object that identifies the owner of a resource
 * without coupling AccessPolicy to specific feature entities.
 */
public record OwnerRef(OwnerType type, UUID id) {

    public OwnerRef {
        Objects.requireNonNull(type, "Owner type must not be null");
    }

    public static OwnerRef user(UUID userId) {
        return new OwnerRef(OwnerType.USER, userId);
    }

    public static OwnerRef group(UUID groupId) {
        return new OwnerRef(OwnerType.GROUP, groupId);
    }

    public static OwnerRef organization(UUID organizationId) {
        return new OwnerRef(OwnerType.ORGANIZATION, organizationId);
    }
}
