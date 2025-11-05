package uk.gegc.quizmaker.features.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

@Schema(name = "RoleDto", description = "Role with permissions and user count")
public record RoleDto(
    @Schema(description = "Role ID", example = "1")
    Long roleId,
    
    @Schema(description = "Role name", example = "ADMIN")
    String roleName,
    
    @Schema(description = "Role description", example = "Administrator role with full access")
    String description,
    
    @Schema(description = "Whether this is a default role assigned to new users", example = "false")
    boolean isDefault,
    
    @Schema(description = "Set of permission names assigned to this role")
    Set<String> permissions,
    
    @Schema(description = "Number of users with this role", example = "5")
    int userCount
) {}
