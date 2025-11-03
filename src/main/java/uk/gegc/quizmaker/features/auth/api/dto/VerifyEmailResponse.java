package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "VerifyEmailResponse", description = "Response after email verification")
public record VerifyEmailResponse(
        @Schema(description = "Whether verification was successful", example = "true")
        boolean verified,
        
        @Schema(description = "Success or error message", example = "Email verified successfully")
        String message,
        
        @Schema(description = "Timestamp when email was verified")
        LocalDateTime verifiedAt
) {}
