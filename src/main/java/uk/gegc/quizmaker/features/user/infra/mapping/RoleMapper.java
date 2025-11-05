package uk.gegc.quizmaker.features.user.infra.mapping;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.admin.api.dto.RoleDto;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RoleMapper {

    private final RoleRepository roleRepository;

    public RoleDto toDto(Role role) {
        if (role == null) {
            return null;
        }

        // Calculate userCount from database to avoid loading all users
        int userCount = role.getRoleId() != null ? 
                roleRepository.countUsersByRoleId(role.getRoleId()) : 0;

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

    public List<RoleDto> toDtoList(List<Role> roles) {
        if (roles == null) {
            return null;
        }

        return roles.stream()
                .map(this::toDto)
                .toList();
    }
}
