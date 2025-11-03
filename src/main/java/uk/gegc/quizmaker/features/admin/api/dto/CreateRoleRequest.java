package uk.gegc.quizmaker.features.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "CreateRoleRequest", description = "Request to create a new role")
public class CreateRoleRequest {

    @Schema(description = "Role name (will be uppercased)", example = "CONTENT_CREATOR")
    @NotBlank(message = "Role name is required")
    private String roleName;

    @Schema(description = "Role description", example = "Content creator role for quiz authors")
    private String description;

    @Schema(description = "Whether this should be a default role for new users", example = "false")
    private boolean isDefault;
}
