package uk.gegc.quizmaker.features.bugreport.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportSeverity;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportStatus;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Bug report details")
public record BugReportDto(
        @Schema(description = "Unique identifier of the bug report", example = "d2719d51-2c24-4c06-a3de-9db1f0b8e8b9")
        UUID id,

        @Schema(description = "Reported issue description")
        String message,

        @Schema(description = "Name of the reporter")
        String reporterName,

        @Schema(description = "Contact email of the reporter")
        String reporterEmail,

        @Schema(description = "Page URL where the bug occurred")
        String pageUrl,

        @Schema(description = "Steps to reproduce the issue")
        String stepsToReproduce,

        @Schema(description = "Client version or environment")
        String clientVersion,

        @Schema(description = "Client IP address captured at submission time")
        String clientIp,

        @Schema(description = "Severity assigned to this report")
        BugReportSeverity severity,

        @Schema(description = "Current workflow status")
        BugReportStatus status,

        @Schema(description = "Internal note for administrators")
        String internalNote,

        @Schema(description = "Creation timestamp")
        Instant createdAt,

        @Schema(description = "Last update timestamp")
        Instant updatedAt
) {
}
