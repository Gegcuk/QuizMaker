package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

@Schema(name = "AcceptResponseBatchRequest", description = "Atomic online batch; request order is part of retry identity. No completion or disclosure flags.")
public record AcceptResponseBatchRequest(@NotNull @Min(0) Long expectedStateVersion,
        @NotNull @ArraySchema(minItems = 1, maxItems = 500, schema = @Schema(implementation = Item.class))
        List<@NotNull @Valid Item> responses) {
    @Schema(name = "ResponseBatchItem")
    // Semantic response validation belongs to the command: it returns 422 in saved form order.
    public record Item(@NotNull UUID formItemId, @NotNull AcceptResponseRequest.Response response) {
        @Override public String toString() { return "ResponseBatchItemRequest[redacted]"; }
    }
    @Override public String toString() { return "AcceptResponseBatchRequest[redacted]"; }
}
