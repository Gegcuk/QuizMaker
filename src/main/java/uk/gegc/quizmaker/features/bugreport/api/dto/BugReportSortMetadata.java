package uk.gegc.quizmaker.features.bugreport.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Sort state emitted by Spring Data")
public record BugReportSortMetadata(
        @Schema(example = "true")
        boolean sorted,

        @Schema(example = "false")
        boolean unsorted,

        @Schema(example = "false")
        boolean empty
) {
}
