package uk.gegc.quizmaker.shared.security;

/**
 * Describes the different types of owners a resource can have.
 * Allows AccessPolicy to remain feature-agnostic while supporting
 * future group/organization ownership models.
 */
public enum OwnerType {
    USER,
    GROUP,
    ORGANIZATION
}
