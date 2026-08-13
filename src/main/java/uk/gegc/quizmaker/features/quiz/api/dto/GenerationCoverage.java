package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationCoverageSnapshot;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationCoverageOutcome;

import java.util.List;

@Schema(
        name = "GenerationCoverage",
        description = "Immutable generated-question coverage reconciled before finalization. Coverage describes quantity only; the job status remains authoritative for quiz availability and billing entitlement."
)
public record GenerationCoverage(
        @Schema(description = "Coverage result under the generation policy", example = "PARTIAL", requiredMode = Schema.RequiredMode.REQUIRED)
        GenerationCoverageOutcome outcome,

        @Schema(description = "Strict success threshold used for this decision. Accepted coverage must be greater than this percentage unless coverage is complete.", example = "80", minimum = "0", maximum = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer thresholdPercent,

        @Schema(description = "Total questions requested across selected chunks and types", example = "10", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer requested,

        @Schema(description = "Total runtime-valid questions accepted into requested type and difficulty buckets", example = "9", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer accepted,

        @Schema(description = "Total requested questions not accepted", example = "1", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer missing,

        @Schema(description = "Generated candidates discarded because they were invalid, out of bucket, or beyond the requested count", example = "2", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer discarded,

        @NotNull
        @ArraySchema(
                arraySchema = @Schema(description = "Requested positive-count types in deterministic enum order"),
                schema = @Schema(implementation = GenerationTypeCoverage.class)
        )
        List<GenerationTypeCoverage> types
) {

    public GenerationCoverage {
        types = List.copyOf(types);
    }

    public static GenerationCoverage fromSnapshot(GenerationCoverageSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new GenerationCoverage(
                snapshot.outcome(),
                snapshot.thresholdPercent(),
                snapshot.requestedTotal(),
                snapshot.acceptedTotal(),
                snapshot.missingTotal(),
                snapshot.discardedTotal(),
                snapshot.types().stream()
                        .map(GenerationTypeCoverage::fromSnapshot)
                        .toList()
        );
    }
}
