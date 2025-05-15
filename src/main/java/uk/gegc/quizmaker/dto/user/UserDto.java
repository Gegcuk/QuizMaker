package uk.gegc.quizmaker.dto.user;

import uk.gegc.quizmaker.model.user.RoleName;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

public record UserDto(
        UUID id,
        String username,
        String email,
        boolean isActive,
        Set<RoleName> roles,
        LocalDateTime createdAt,
        LocalDateTime lastLoginDate,
        LocalDateTime updatedAt
) {}
