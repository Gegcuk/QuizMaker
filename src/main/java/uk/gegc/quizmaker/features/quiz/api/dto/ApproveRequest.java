package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(name = "ApproveRequest", description = "Request to approve a quiz under moderation")
public record ApproveRequest(
        @NotNull @Schema(description = "Moderator user UUID") UUID moderatorId,
        @Schema(description = "Optional approval reason/notes") String reason
) {}


