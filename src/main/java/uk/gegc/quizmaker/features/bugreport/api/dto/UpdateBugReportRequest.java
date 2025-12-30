package uk.gegc.quizmaker.features.bugreport.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportSeverity;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportStatus;

@Schema(description = "Fields that can be updated on a bug report (admin only)")
public record UpdateBugReportRequest(
        @Schema(
                description = "Updated description of the bug",
                maxLength = 4000
        )
        @Size(min = 1, max = 4000, message = "Message must be between 1 and 4000 characters when provided")
        String message,

        @Schema(description = "Reporter name", maxLength = 255)
        @Size(max = 255, message = "Name must be at most 255 characters")
        String reporterName,

        @Schema(description = "Reporter contact email", maxLength = 255)
        @Email(message = "{email.invalid}")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String reporterEmail,

        @Schema(description = "Page URL where the bug occurred", maxLength = 1024)
        @Size(max = 1024, message = "Page URL must be at most 1024 characters")
        String pageUrl,

        @Schema(description = "Steps to reproduce the issue", maxLength = 8000)
        @Size(max = 8000, message = "Steps to reproduce must be at most 8000 characters")
        String stepsToReproduce,

        @Schema(description = "Client version or environment details", maxLength = 255)
        @Size(max = 255, message = "Client version must be at most 255 characters")
        String clientVersion,

        @Schema(description = "Updated severity")
        BugReportSeverity severity,

        @Schema(description = "Workflow status")
        BugReportStatus status,

        @Schema(description = "Internal note for administrators", maxLength = 8000)
        @Size(max = 8000, message = "Internal note must be at most 8000 characters")
        String internalNote
) {
}
