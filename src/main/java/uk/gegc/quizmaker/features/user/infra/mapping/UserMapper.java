package uk.gegc.quizmaker.features.user.infra.mapping;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.user.api.dto.AuthenticatedUserDto;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UserMapper {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    public AuthenticatedUserDto toDto(User user) {
        return new AuthenticatedUserDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.isActive(),
                user.getRoles()
                        .stream()
                        .map(Role::getRoleName)
                        .map(RoleName::valueOf)
                        .collect(Collectors.toSet()),
                user.getCreatedAt(),
                user.getLastLoginDate(),
                user.getUpdatedAt()
        );
    }


}
