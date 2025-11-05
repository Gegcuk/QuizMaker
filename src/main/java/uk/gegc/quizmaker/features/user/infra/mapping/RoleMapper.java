package uk.gegc.quizmaker.features.user.infra.mapping;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.admin.api.dto.RoleDto;
import uk.gegc.quizmaker.features.user.domain.model.Role;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RoleMapper {

    /**
     * Convert Role entity to RoleDto with userCount.
     * For single role conversions, userCount should be provided by the service layer.
     * 
     * @param role The role entity
     * @param userCount The count of users assigned to this role
     * @return RoleDto
     */
    public RoleDto toDto(Role role, int userCount) {
        if (role == null) {
            return null;
        }

        return new RoleDto(
                role.getRoleId(),
                role.getRoleName(),
                role.getDescription(),
                role.isDefault(),
                role.getPermissions() != null ?
                        role.getPermissions().stream()
                                .map(permission -> permission.getPermissionName())
                                .collect(Collectors.toSet()) : null,
                userCount
        );
    }

    /**
     * Convenience method for converting a single role without user count.
     * Sets userCount to 0 - use toDto(Role, int) when count is needed.
     */
    public RoleDto toDto(Role role) {
        return toDto(role, 0);
    }

    /**
     * Convert list of roles to DTOs.
     * Note: This sets userCount to 0 for all roles.
     * Use service layer methods that fetch counts in batch for accurate counts.
     */
    public List<RoleDto> toDtoList(List<Role> roles) {
        if (roles == null) {
            return null;
        }

        return roles.stream()
                .map(this::toDto)
                .toList();
    }
}
