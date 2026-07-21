package uk.gegc.quizmaker.features.document.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Sort state emitted by Spring Data")
public record DocumentPageSortMetadata(
        @Schema(example = "false")
        boolean sorted,

        @Schema(example = "true")
        boolean unsorted,

        @Schema(example = "true")
        boolean empty
) {
}
