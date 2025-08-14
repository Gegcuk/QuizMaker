package uk.gegc.quizmaker.features.admin.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleDto {

    private Long roleId;
    private String roleName;
    private String description;
    private boolean isDefault;
    private Set<String> permissions;
    private int userCount;
}
