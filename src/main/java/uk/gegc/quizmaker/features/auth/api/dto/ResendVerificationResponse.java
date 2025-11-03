package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ResendVerificationResponse", description = "Response after resending verification email")
public record ResendVerificationResponse(
        @Schema(description = "Message indicating action taken", example = "If the email exists and is unverified, a verification link was sent")
        String message
) {}
