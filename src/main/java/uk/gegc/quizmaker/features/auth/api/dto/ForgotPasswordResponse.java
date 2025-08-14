package uk.gegc.quizmaker.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ForgotPasswordResponse", description = "Response for password reset initiation")
public record ForgotPasswordResponse(
        @Schema(description = "Generic message indicating the action taken", example = "If the email exists, a reset link was sent.")
        String message
) {
}
