package uk.gegc.quizmaker.features.billing.application;

import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;

import java.util.UUID;

/**
 * Estimates token usage for quiz generation and converts LLM tokens to billing tokens.
 */
public interface EstimationService {

    /**
     * Estimate tokens required to generate a quiz from a document based on the request scope and parameters.
     *
     * @param documentId Document to base the estimation on.
     * @param request    Quiz generation request (scope, questions per type, etc.).
     * @return Estimation DTO with LLM and billing tokens and currency; flagged as an estimate.
     */
    EstimationDto estimateQuizGeneration(UUID documentId, GenerateQuizFromDocumentRequest request);

    /**
     * Convert LLM tokens to billing tokens per configured ratio with ceil rounding.
     */
    long llmTokensToBillingTokens(long llmTokens);
}

