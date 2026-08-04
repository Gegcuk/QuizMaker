package uk.gegc.quizmaker.features.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(name = "EstimationDto", description = "Quiz-generation operational estimate and customer maximum charge")
public record EstimationDto(
        @Schema(description = "Estimated LLM tokens required", example = "1500")
        long estimatedLlmTokens,
        
        @Schema(description = "Maximum billing tokens reserved before generation. The customer is never charged more than this amount.", example = "10")
        long estimatedBillingTokens,
        
        @Schema(description = "Approximate cost in cents (not implemented in MVP)", example = "null")
        Long approxCostCents,
        
        @Schema(description = "Currency code", example = "usd")
        String currency,
        
        @Schema(description = "Whether this is an estimate (always true)", example = "true")
        boolean estimate,
        
        @Schema(description = "Human-readable maximum charge based on source length and requested question types", example = "Up to 7 billing tokens for 2 question types from 4,000 source characters")
        String humanizedEstimate,
        
        @Schema(description = "Unique estimation ID for correlation", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID estimationId,

        @Schema(description = "Version of the pricing rule used for the maximum quote. Optional for responses produced by legacy code.", example = "v1-content-length-per-question-type", nullable = true)
        String tariffVersion,

        @Schema(description = "Fixed base billing tokens included in this quote. Optional for legacy responses.", example = "3", nullable = true)
        Long billingBaseTokens,

        @Schema(description = "Billing-token rate per 1,000 source characters for each requested question type. Optional for legacy responses.", example = "0.35", nullable = true)
        BigDecimal billingTokensPerThousandCharacters,

        @Schema(description = "Source character count used to calculate the maximum quote. Optional for legacy responses.", example = "4000", nullable = true)
        Long quotedContentCharacters,

        @Schema(description = "Requested question-type count used to calculate the maximum quote. Optional for legacy responses.", example = "2", nullable = true)
        Integer quotedQuestionTypeCount
) {

    /**
     * Source-compatible constructor for callers compiled against the pre-tariff response shape.
     */
    public EstimationDto(
            long estimatedLlmTokens,
            long estimatedBillingTokens,
            Long approxCostCents,
            String currency,
            boolean estimate,
            String humanizedEstimate,
            UUID estimationId
    ) {
        this(
                estimatedLlmTokens,
                estimatedBillingTokens,
                approxCostCents,
                currency,
                estimate,
                humanizedEstimate,
                estimationId,
                null,
                null,
                null,
                null,
                null
        );
    }
    
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

    public static String createHumanizedEstimate(
            long maximumBillingTokens,
            long sourceCharacterCount,
            int requestedQuestionTypeCount
    ) {
        if (maximumBillingTokens == 0L) {
            return "No tokens required";
        }

        String billingPart = maximumBillingTokens == 1L
                ? "1 billing token"
                : maximumBillingTokens + " billing tokens";
        String questionTypePart = requestedQuestionTypeCount == 1
                ? "1 question type"
                : requestedQuestionTypeCount + " question types";
        return "Up to " + billingPart + " for " + questionTypePart
                + " from " + sourceCharacterCount + " source characters";
    }
}
