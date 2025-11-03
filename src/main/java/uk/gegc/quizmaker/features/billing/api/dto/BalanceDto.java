package uk.gegc.quizmaker.features.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(name = "BalanceDto", description = "User's token balance")
public record BalanceDto(
        @Schema(description = "User UUID")
        UUID userId,
        
        @Schema(description = "Available tokens for use", example = "10000")
        long availableTokens,
        
        @Schema(description = "Reserved tokens (pending operations)", example = "500")
        long reservedTokens,
        
        @Schema(description = "Last balance update timestamp")
        LocalDateTime updatedAt
) {}
