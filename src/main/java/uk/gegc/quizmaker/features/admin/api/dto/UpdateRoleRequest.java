package uk.gegc.quizmaker.features.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UpdateRoleRequest", description = "Request to update an existing role")
public record UpdateRoleRequest(
    @Schema(description = "Updated role description", example = "Updated description for content creators")
    String description,

    @Schema(description = "Whether this should be a default role", example = "false")
    boolean isDefault
) {}
