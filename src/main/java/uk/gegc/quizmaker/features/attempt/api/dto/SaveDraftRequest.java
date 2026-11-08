package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@Schema(name = "SaveDraftRequest", description = "Private provisional value. Saving never confirms an answer or grants feedback.")
public record SaveDraftRequest(@NotNull @Min(0) Long expectedStateVersion,
                               @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long expectedDraftVersion,
                               @NotNull @Valid @Schema(oneOf = {AcceptResponseRequest.Single.class, AcceptResponseRequest.Multiple.class,
                                       AcceptResponseRequest.Truth.class, AcceptResponseRequest.Ordering.class, AcceptResponseRequest.Gaps.class})
                               AcceptResponseRequest.Response response) {
    @Override public String toString() { return "SaveDraftRequest[redacted]"; }
}
