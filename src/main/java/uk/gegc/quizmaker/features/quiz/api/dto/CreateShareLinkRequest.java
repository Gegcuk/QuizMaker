package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import uk.gegc.quizmaker.model.quiz.ShareLinkScope;

import java.time.Instant;

@Schema(name = "CreateShareLinkRequest", description = "Request to create a share link")
public record CreateShareLinkRequest(
        @NotNull @Schema(description = "Scope of the link", example = "QUIZ_VIEW") ShareLinkScope scope,
        @Schema(description = "Expiry timestamp (UTC)") Instant expiresAt,
        @Schema(description = "Whether the link is one-time use", example = "true") Boolean oneTime
) {}


