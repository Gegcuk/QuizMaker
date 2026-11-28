package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(name = "BranchAdvanceRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        description = "Use both versions from the current participant view. The server selects the next question.")
public record BranchAdvanceRequest(@NotNull @Min(1) Long expectedStateVersion, @NotNull @Min(1) Long expectedFormVersion) { }
