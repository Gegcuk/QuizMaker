package uk.gegc.quizmaker.features.user.domain.model;

public enum PermissionName {
    // Quiz Permissions
    QUIZ_READ("quiz", "read", "View quizzes"),
    QUIZ_CREATE("quiz", "create", "Create quizzes"),
    QUIZ_UPDATE("quiz", "update", "Update own quizzes"),
    QUIZ_DELETE("quiz", "delete", "Delete own quizzes"),
    QUIZ_PUBLISH("quiz", "publish", "Publish quizzes"),
    QUIZ_MODERATE("quiz", "moderate", "Moderate any quiz"),
    QUIZ_ADMIN("quiz", "admin", "Full quiz administration"),

    // Question Permissions
    QUESTION_READ("question", "read", "View questions"),
    QUESTION_CREATE("question", "create", "Create questions"),
    QUESTION_UPDATE("question", "update", "Update own questions"),
    QUESTION_DELETE("question", "delete", "Delete own questions"),
    QUESTION_MODERATE("question", "moderate", "Moderate any question"),
    QUESTION_ADMIN("question", "admin", "Full question administration"),

    // Category Permissions
    CATEGORY_READ("category", "read", "View categories"),
    CATEGORY_CREATE("category", "create", "Create categories"),
    CATEGORY_UPDATE("category", "update", "Update categories"),
    CATEGORY_DELETE("category", "delete", "Delete categories"),
    CATEGORY_ADMIN("category", "admin", "Full category administration"),

    // Tag Permissions
    TAG_READ("tag", "read", "View tags"),
    TAG_CREATE("tag", "create", "Create tags"),
    TAG_UPDATE("tag", "update", "Update tags"),
    TAG_DELETE("tag", "delete", "Delete tags"),
    TAG_ADMIN("tag", "admin", "Full tag administration"),

    // User Permissions
    USER_READ("user", "read", "View user profiles"),
    USER_UPDATE("user", "update", "Update own profile"),
    USER_DELETE("user", "delete", "Delete own account"),
    USER_MANAGE("user", "manage", "Manage other users"),
    USER_ADMIN("user", "admin", "Full user administration"),

    // Comment Permissions
    COMMENT_READ("comment", "read", "View comments"),
    COMMENT_CREATE("comment", "create", "Create comments"),
    COMMENT_UPDATE("comment", "update", "Update own comments"),
    COMMENT_DELETE("comment", "delete", "Delete own comments"),
    COMMENT_MODERATE("comment", "moderate", "Moderate any comment"),

    // Attempt Permissions
    ATTEMPT_CREATE("attempt", "create", "Take quizzes"),
    ATTEMPT_READ("attempt", "read", "View own attempts"),
    ATTEMPT_READ_ALL("attempt", "read_all", "View all attempts"),
    ATTEMPT_DELETE("attempt", "delete", "Delete attempts"),

    // Social Permissions
    BOOKMARK_CREATE("bookmark", "create", "Create bookmarks"),
    BOOKMARK_READ("bookmark", "read", "View bookmarks"),
    BOOKMARK_DELETE("bookmark", "delete", "Delete bookmarks"),
    FOLLOW_CREATE("follow", "create", "Follow users"),
    FOLLOW_DELETE("follow", "delete", "Unfollow users"),

    // Admin Permissions
    ROLE_READ("role", "read", "View roles"),
    ROLE_CREATE("role", "create", "Create roles"),
    ROLE_UPDATE("role", "update", "Update roles"),
    ROLE_DELETE("role", "delete", "Delete roles"),
    ROLE_ASSIGN("role", "assign", "Assign roles to users"),

    PERMISSION_READ("permission", "read", "View permissions"),
    PERMISSION_CREATE("permission", "create", "Create permissions"),
    PERMISSION_UPDATE("permission", "update", "Update permissions"),
    PERMISSION_DELETE("permission", "delete", "Delete permissions"),

    // System Permissions
    AUDIT_READ("audit", "read", "View audit logs"),
    SYSTEM_ADMIN("system", "admin", "Full system administration"),
    NOTIFICATION_READ("notification", "read", "View notifications"),
    NOTIFICATION_CREATE("notification", "create", "Create notifications"),
    NOTIFICATION_ADMIN("notification", "admin", "Manage all notifications");

    private final String resource;
    private final String action;
    private final String description;

    PermissionName(String resource, String action, String description) {
        this.resource = resource;
        this.action = action;
        this.description = description;
    }

    public String getResource() {
        return resource;
    }

    public String getAction() {
        return action;
    }

    public String getDescription() {
        return description;
    }

    public String getPermissionName() {
        return this.name();
    }
} 