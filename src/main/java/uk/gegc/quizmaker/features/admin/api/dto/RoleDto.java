package uk.gegc.quizmaker.features.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "RoleDto", description = "Role with permissions and user count")
public class RoleDto {

    @Schema(description = "Role ID", example = "1")
    private Long roleId;
    
    @Schema(description = "Role name", example = "ADMIN")
    private String roleName;
    
    @Schema(description = "Role description", example = "Administrator role with full access")
    private String description;
    
    @Schema(description = "Whether this is a default role assigned to new users", example = "false")
    private boolean isDefault;
    
    @Schema(description = "Set of permission names assigned to this role")
    private Set<String> permissions;
    
    @Schema(description = "Number of users with this role", example = "5")
    private int userCount;
}
