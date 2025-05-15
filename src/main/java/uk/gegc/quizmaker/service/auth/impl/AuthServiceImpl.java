package uk.gegc.quizmaker.service.auth.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.dto.auth.RegisterRequest;
import uk.gegc.quizmaker.dto.user.UserDto;
import uk.gegc.quizmaker.mapper.UserMapper;
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.model.user.RoleName;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.RoleRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.auth.AuthService;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;

    @Override
    public UserDto register(RegisterRequest request) {

        if(userRepository.existsByUsername(request.username())){
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already in use");
        }

        if(userRepository.existsByEmail(request.email())){
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setHashedPassword(passwordEncoder.encode(request.password()));
        user.setActive(true);

        Role userRole = roleRepository.findByRole(RoleName.ROLE_USER.name())
                .orElseThrow(() -> new IllegalStateException("ROLE_USER not found"));
        user.setRoles(Set.of(userRole));

        User saved = userRepository.save(user);
        return userMapper.toDto(saved);
    }
}
