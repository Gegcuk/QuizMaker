package uk.gegc.quizmaker.service.user.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.dto.user.UserProfileResponse;
import uk.gegc.quizmaker.dto.user.UpdateUserProfileRequest;
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.user.UserProfileService;
import uk.gegc.quizmaker.util.XssSanitizer;

import java.util.*;
 

@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileServiceImpl implements UserProfileService {

    private final UserRepository userRepository;
    private final XssSanitizer xssSanitizer;
    private final ObjectMapper objectMapper;

    @Override
    public UserProfileResponse getCurrentUserProfile(Authentication authentication) {
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

        return toMeResponse(user);
    }

    @Override
    @Transactional
    public UserProfileResponse updateCurrentUserProfile(Authentication authentication, UpdateUserProfileRequest request) {
        return updateCurrentUserProfile(authentication, request, null);
    }

    @Override
    @Transactional
    public UserProfileResponse updateCurrentUserProfile(Authentication authentication, UpdateUserProfileRequest request, Long ifMatchVersion) {
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

        // If-Match version precondition
        if (ifMatchVersion != null && !java.util.Objects.equals(ifMatchVersion, user.getVersion())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Version mismatch");
        }

        // Sanitize and update display name
        if (request.displayName() != null) {
            // present with null => clear; present with non-null => set sanitized
            user.setDisplayName(xssSanitizer.sanitizeAndTruncate(request.displayName(), 50));
        }

        // Sanitize and update bio
        if (request.bio() != null) {
            user.setBio(xssSanitizer.sanitizeAndTruncate(request.bio(), 500));
        }

        // Update preferences
        if (request.preferences() != null) {
            validatePreferences(request.preferences());
            try {
                String preferencesJson = objectMapper.writeValueAsString(request.preferences());
                user.setPreferences(preferencesJson);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize preferences for user {}: {}", user.getId(), e.getMessage());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid preferences format");
            }
        }

        // Save the updated user and map directly without re-query
        User savedUser = userRepository.save(user);
        log.info("User profile updated for user: {}", savedUser.getId());

        return toMeResponse(savedUser);
    }

    @Override
    @Transactional
    public UserProfileResponse updateCurrentUserProfile(Authentication authentication, com.fasterxml.jackson.databind.JsonNode mergePatch, Long ifMatchVersion) {
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

        if (ifMatchVersion != null && !java.util.Objects.equals(ifMatchVersion, user.getVersion())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Version mismatch");
        }

        if (mergePatch != null) {
            // displayName
            if (mergePatch.has("displayName")) {
                JsonNode node = mergePatch.get("displayName");
                user.setDisplayName(node.isNull() ? null : xssSanitizer.sanitizeAndTruncate(node.asText(), 50));
            }
            // bio
            if (mergePatch.has("bio")) {
                JsonNode node = mergePatch.get("bio");
                user.setBio(node.isNull() ? null : xssSanitizer.sanitizeAndTruncate(node.asText(), 500));
            }
            // preferences
            if (mergePatch.has("preferences")) {
                JsonNode node = mergePatch.get("preferences");
                if (node.isNull()) {
                    user.setPreferences(null);
                } else if (node.isObject()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> prefs = objectMapper.convertValue(node, Map.class);
                    validatePreferences(prefs);
                    try {
                        String preferencesJson = objectMapper.writeValueAsString(prefs);
                        user.setPreferences(preferencesJson);
                    } catch (JsonProcessingException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid preferences format");
                    }
                } else {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "preferences must be an object or null");
                }
            }
        }

        User savedUser = userRepository.save(user);
        log.info("User profile updated for user: {}", savedUser.getId());
        return toMeResponse(savedUser);
    }
    
    private static final int MAX_PREF_KEYS = 50;
    private static final int MAX_KEY_LEN = 64;
    private void validatePreferences(Map<String, Object> prefs) {
        if (prefs == null) {
            return;
        }
        if (prefs.size() > MAX_PREF_KEYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many preferences");
        }
        for (String k : prefs.keySet()) {
            if (k == null || k.length() > MAX_KEY_LEN) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Preference key too long");
            }
        }
    }
    
    private UserProfileResponse toMeResponse(User user) {
        List<String> roles = user.getRoles() == null ? List.of() : user.getRoles().stream()
                .map(Role::getRoleName)
                .filter(Objects::nonNull)
                .map(r -> r.replaceFirst("^ROLE_", "").toUpperCase(java.util.Locale.ROOT))
                .sorted(Comparator.naturalOrder())
                .toList();

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

        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                displayName,
                bio,
                avatarUrl,
                preferences,
                user.getCreatedAt(),
                user.isEmailVerified(),
                roles,
                user.getVersion()
        );
    }
}


