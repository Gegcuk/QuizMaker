package uk.gegc.quizmaker.features.billing.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateCustomerRequest(
        @JsonProperty("email")
        String email
) {}
