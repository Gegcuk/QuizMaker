package uk.gegc.quizmaker.features.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "UpdateRoleRequest", description = "Request to update an existing role")
public class UpdateRoleRequest {

    @Schema(description = "Updated role description", example = "Updated description for content creators")
    private String description;

    @Schema(description = "Whether this should be a default role", example = "false")
    private boolean isDefault;
}
