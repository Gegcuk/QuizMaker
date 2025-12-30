package uk.gegc.quizmaker.features.bugreport.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportSeverity;

@Schema(description = "Payload for submitting a new bug report")
public record CreateBugReportRequest(
        @Schema(
                description = "Description of the bug or issue",
                example = "Saving a quiz fails with 500 error when adding images",
                minLength = 1,
                maxLength = 4000
        )
        @NotBlank(message = "Message is required")
        @Size(max = 4000, message = "Message must be at most 4000 characters")
        String message,

        @Schema(
                description = "Name of the person reporting the bug",
                example = "Jane Doe",
                maxLength = 255
        )
        @Size(max = 255, message = "Name must be at most 255 characters")
        String reporterName,

        @Schema(
                description = "Contact email for follow-up",
                example = "jane@example.com",
                maxLength = 255
        )
        @Email(message = "{email.invalid}")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String reporterEmail,

        @Schema(
                description = "Page URL where the bug occurred",
                example = "https://app.quizmaker.com/quizzes/123/edit",
                maxLength = 1024
        )
        @Size(max = 1024, message = "Page URL must be at most 1024 characters")
        String pageUrl,

        @Schema(
                description = "Optional steps to reproduce the issue",
                example = "1) Open quiz editor 2) Add image 3) Click save",
                maxLength = 8000
        )
        @Size(max = 8000, message = "Steps to reproduce must be at most 8000 characters")
        String stepsToReproduce,

        @Schema(
                description = "Client version or environment information",
                example = "web 1.2.3 (Chrome 124)",
                maxLength = 255
        )
        @Size(max = 255, message = "Client version must be at most 255 characters")
        String clientVersion,

        @Schema(
                description = "Optional severity reported by the user",
                example = "HIGH"
        )
        BugReportSeverity severity
) {
}
