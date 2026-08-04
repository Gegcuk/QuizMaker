package uk.gegc.quizmaker.features.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
        
        @Schema(description = "Human-readable maximum charge based on requested valid questions", example = "Up to 10 billing tokens for 10 valid questions")
        String humanizedEstimate,
        
        @Schema(description = "Unique estimation ID for correlation", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID estimationId,

        @Schema(description = "Version of the pricing rule used for the maximum quote. Optional for responses produced by legacy code.", example = "v1-per-valid-question", nullable = true)
        String tariffVersion,

        @Schema(description = "Billing tokens charged for each valid accepted question under this quote. Optional for legacy responses.", example = "1", nullable = true)
        Long billingTokensPerValidQuestion,

        @Schema(description = "Requested question count used to calculate the maximum quote. Optional for legacy responses.", example = "10", nullable = true)
        Integer quotedQuestionCount
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

    public static String createHumanizedEstimate(long maximumBillingTokens, int requestedQuestionCount) {
        if (maximumBillingTokens == 0L || requestedQuestionCount == 0) {
            return "No tokens required";
        }

        String billingPart = maximumBillingTokens == 1L
                ? "1 billing token"
                : maximumBillingTokens + " billing tokens";
        String questionPart = requestedQuestionCount == 1
                ? "1 valid question"
                : requestedQuestionCount + " valid questions";
        return "Up to " + billingPart + " for " + questionPart;
    }
}
