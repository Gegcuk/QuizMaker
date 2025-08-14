package uk.gegc.quizmaker.dto.result;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "LeaderboardEntryDto", description = "Quiz leaderboard entry")
public record LeaderboardEntryDto(
        @Schema(description = "User identifier")
        UUID userId,

        @Schema(description = "Username")
        String username,

        @Schema(description = "Best score for the quiz")
        Double bestScore
) {
}
