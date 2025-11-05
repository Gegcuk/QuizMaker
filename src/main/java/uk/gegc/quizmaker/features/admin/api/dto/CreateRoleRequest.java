package uk.gegc.quizmaker.features.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "CreateRoleRequest", description = "Request to create a new role")
public record CreateRoleRequest(
    @Schema(description = "Role name (will be uppercased)", example = "CONTENT_CREATOR")
    @NotBlank(message = "Role name is required")
    String roleName,

    @Schema(description = "Role description", example = "Content creator role for quiz authors")
    String description,

    @Schema(description = "Whether this should be a default role for new users", example = "false")
    boolean isDefault
) {}
