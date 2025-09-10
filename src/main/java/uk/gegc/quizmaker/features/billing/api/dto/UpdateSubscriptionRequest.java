package uk.gegc.quizmaker.features.billing.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateSubscriptionRequest(
        @JsonProperty("subscriptionId")
        String subscriptionId,
        
        @JsonProperty("newPriceLookupKey")
        String newPriceLookupKey
) {}
