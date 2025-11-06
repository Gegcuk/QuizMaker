package uk.gegc.quizmaker.features.result.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "LeaderboardEntryDto", description = "Quiz leaderboard entry")
public record LeaderboardEntryDto(
        @Schema(description = "User identifier", example = "9f8e7d6c-5b4a-3c2d-1b0a-9f8e7d6c5b4a")
        UUID userId,

        @Schema(description = "Username", example = "john_doe")
        String username,

        @Schema(
                description = "Best score achieved by this user for the quiz (raw points, not percentage)",
                example = "145.5"
        )
        Double bestScore
) {
}
