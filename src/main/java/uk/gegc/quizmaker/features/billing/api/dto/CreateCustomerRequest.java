package uk.gegc.quizmaker.features.billing.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateCustomerRequest(
        @JsonProperty("email")
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        String email
) {}
