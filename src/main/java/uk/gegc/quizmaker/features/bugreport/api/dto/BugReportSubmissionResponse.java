package uk.gegc.quizmaker.features.bugreport.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Public acknowledgement returned after a bug report is accepted.
 */
@Schema(description = "Public acknowledgement for a submitted bug report")
public record BugReportSubmissionResponse(
        @Schema(
                description = "Identifier assigned to the submitted bug report",
                example = "d2719d51-2c24-4c06-a3de-9db1f0b8e8b9"
        )
        UUID bugReportId
) {
}
