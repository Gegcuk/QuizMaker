package uk.gegc.quizmaker.service.user.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.dto.user.MeResponse;
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.user.MeService;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MeServiceImpl implements MeService {

    private final UserRepository userRepository;

    @Override
    public MeResponse getCurrentUserProfile(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        String principal = authentication.getName();

        Optional<User> userOpt = userRepository.findByUsernameWithRoles(principal)
                .or(() -> userRepository.findByEmailWithRoles(principal));

        User user = userOpt.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found or inactive"));

        if (!user.isActive() || user.isDeleted()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found or inactive");
        }

        // Map roles to sorted list of strings
        List<String> roles = user.getRoles() == null ? List.of() : user.getRoles().stream()
                .map(Role::getRoleName)
                .filter(Objects::nonNull)
                .map(r -> r.replaceFirst("^ROLE_", ""))
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());

        // Preferences / displayName / bio / avatarUrl placeholders (extend later)
        Map<String, Object> preferences = Map.of();
        String displayName = user.getUsername();
        String bio = null;
        String avatarUrl = null;

        return new MeResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                displayName,
                bio,
                avatarUrl,
                preferences,
                user.getCreatedAt(),
                user.isEmailVerified(),
                roles
        );
    }
}


