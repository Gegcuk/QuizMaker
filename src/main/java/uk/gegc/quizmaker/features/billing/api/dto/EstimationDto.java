package uk.gegc.quizmaker.features.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "EstimationDto", description = "Token estimation for quiz generation")
public record EstimationDto(
        @Schema(description = "Estimated LLM tokens required", example = "1500")
        long estimatedLlmTokens,
        
        @Schema(description = "Estimated billing tokens (after conversion)", example = "2")
        long estimatedBillingTokens,
        
        @Schema(description = "Approximate cost in cents (not implemented in MVP)", example = "null")
        Long approxCostCents,
        
        @Schema(description = "Currency code", example = "usd")
        String currency,
        
        @Schema(description = "Whether this is an estimate (always true)", example = "true")
        boolean estimate,
        
        @Schema(description = "Humanized description of the estimate", example = "~2 billing tokens (1,500 LLM tokens)")
        String humanizedEstimate,
        
        @Schema(description = "Unique estimation ID for correlation", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID estimationId
) {
    
    /**
     * Create a humanized string representation of the estimate
     */
    public static String createHumanizedEstimate(long estimatedLlmTokens, long estimatedBillingTokens, String currency) {
        if (estimatedBillingTokens == 0) {
            return "No tokens required";
        }
        
        String billingPart = estimatedBillingTokens == 1 ? 
            "1 billing token" : 
            estimatedBillingTokens + " billing tokens";
            
        String llmPart = estimatedLlmTokens == 1 ? 
            "1 LLM token" : 
            estimatedLlmTokens + " LLM tokens";
            
        return String.format("~%s (%s)", billingPart, llmPart);
    }
}

