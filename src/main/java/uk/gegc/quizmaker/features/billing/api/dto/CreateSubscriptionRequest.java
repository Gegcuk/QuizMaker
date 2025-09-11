package uk.gegc.quizmaker.features.billing.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CreateSubscriptionRequest(
        @JsonProperty("priceId")
        @NotBlank(message = "Price ID is required")
        String priceId
) {}
