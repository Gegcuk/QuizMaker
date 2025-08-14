package uk.gegc.quizmaker.features.user.domain.model;

public enum RoleName {
    ROLE_USER,           // Basic user - can take quizzes, view public content
    ROLE_QUIZ_CREATOR,   // Can create and manage their own quizzes
    ROLE_MODERATOR,      // Can moderate content, manage reported items
    ROLE_ADMIN,          // Can manage users, categories, and system settings
    ROLE_SUPER_ADMIN     // Full system access
}
