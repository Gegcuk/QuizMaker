package uk.gegc.quizmaker.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.admin.RoleDto;
import uk.gegc.quizmaker.model.user.Role;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RoleMapper {

    public RoleDto toDto(Role role) {
        if (role == null) {
            return null;
        }

        return RoleDto.builder()
                .roleId(role.getRoleId())
                .roleName(role.getRoleName())
                .description(role.getDescription())
                .isDefault(role.isDefault())
                .permissions(role.getPermissions() != null ?
                        role.getPermissions().stream()
                                .map(permission -> permission.getPermissionName())
                                .collect(Collectors.toSet()) : null)
                .build();
    }

    public List<RoleDto> toDtoList(List<Role> roles) {
        if (roles == null) {
            return null;
        }

        return roles.stream()
                .map(this::toDto)
                .toList();
    }
}
