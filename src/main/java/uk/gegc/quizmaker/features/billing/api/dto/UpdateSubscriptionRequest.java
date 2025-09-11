package uk.gegc.quizmaker.features.billing.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record UpdateSubscriptionRequest(
        @JsonProperty("subscriptionId")
        @NotBlank(message = "Subscription ID is required")
        String subscriptionId,
        
        @JsonProperty("newPriceLookupKey")
        @NotBlank(message = "New price lookup key is required")
        String newPriceLookupKey
) {}
