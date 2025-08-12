package uk.gegc.quizmaker.service.user.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.dto.user.MeResponse;
import uk.gegc.quizmaker.dto.user.UpdateMeRequest;
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.user.MeService;
import uk.gegc.quizmaker.util.XssSanitizer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MeServiceImpl implements MeService {

    private final UserRepository userRepository;
    private final XssSanitizer xssSanitizer;
    private final ObjectMapper objectMapper;

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

        // Parse preferences from JSON string
        Map<String, Object> preferences = Map.of();
        if (user.getPreferences() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsedPreferences = objectMapper.readValue(user.getPreferences(), Map.class);
                preferences = parsedPreferences;
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse preferences for user {}: {}", user.getId(), e.getMessage());
                preferences = Map.of();
            }
        }

        String displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getUsername();
        String bio = user.getBio();
        String avatarUrl = user.getAvatarUrl();

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

    @Override
    @Transactional
    public MeResponse updateCurrentUserProfile(Authentication authentication, UpdateMeRequest request) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        String principal = authentication.getName();

        User user = userRepository.findByUsernameWithRoles(principal)
                .or(() -> userRepository.findByEmailWithRoles(principal))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found or inactive"));

        if (!user.isActive() || user.isDeleted()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found or inactive");
        }

        // Sanitize and update display name
        if (request.displayName() != null) {
            String sanitizedDisplayName = xssSanitizer.sanitizeAndTruncate(request.displayName(), 50);
            user.setDisplayName(sanitizedDisplayName);
        }

        // Sanitize and update bio
        if (request.bio() != null) {
            String sanitizedBio = xssSanitizer.sanitizeAndTruncate(request.bio(), 500);
            user.setBio(sanitizedBio);
        }

        // Update preferences
        if (request.preferences() != null) {
            try {
                String preferencesJson = objectMapper.writeValueAsString(request.preferences());
                user.setPreferences(preferencesJson);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize preferences for user {}: {}", user.getId(), e.getMessage());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid preferences format");
            }
        }

        // Save the updated user
        User savedUser = userRepository.save(user);
        log.info("User profile updated for user: {}", savedUser.getId());

        // Return updated profile
        return getCurrentUserProfile(authentication);
    }
}


