package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;

/** Named schema for the RFC 7807 wire shape emitted by the centralized advice. */
@Schema(name = "ResponseBatchProblem")
public record ResponseBatchProblem(URI type, String title, int status, String detail, URI instance,
                                   String code, @Schema(nullable = true) List<ItemError> errors) {
    @Schema(name = "ResponseBatchItemError", description = "Zero-based request index; list ordering follows saved form position, unknown items last in request order.")
    public record ItemError(int index, @Schema(allowableValues = {"DUPLICATE_ITEM", "UNKNOWN_ITEM", "INVALID_RESPONSE", "UNSUPPORTED_CONTRACT", "ANSWER_LOCKED"}) String code) { }
}
