package uk.gegc.quizmaker.features.billing.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CancelSubscriptionRequest(
        @JsonProperty("subscriptionId")
        @NotBlank(message = "Subscription ID is required")
        String subscriptionId
) {}
