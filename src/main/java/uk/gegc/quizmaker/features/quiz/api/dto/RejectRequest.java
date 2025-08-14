package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(name = "RejectRequest", description = "Request to reject a quiz under moderation")
public record RejectRequest(
        @NotNull @Schema(description = "Moderator user UUID") UUID moderatorId,
        @NotBlank @Schema(description = "Reason for rejection") String reason
) {}


