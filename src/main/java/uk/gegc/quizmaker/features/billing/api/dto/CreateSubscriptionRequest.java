package uk.gegc.quizmaker.features.billing.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateSubscriptionRequest(
        @JsonProperty("priceId")
        String priceId
) {}
