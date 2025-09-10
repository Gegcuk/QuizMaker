package uk.gegc.quizmaker.features.billing.api.dto;

public record CustomerResponse(
        String id,
        String email,
        String name
) {}
