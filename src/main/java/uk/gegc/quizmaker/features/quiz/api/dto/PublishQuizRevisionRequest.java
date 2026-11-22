package uk.gegc.quizmaker.features.quiz.api.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Schema(name = "PublishQuizRevisionRequest", requiredProperties = {"commandId", "expectedPublicationVersion", "questionOrder"})
public record PublishQuizRevisionRequest(
        @NotNull @Schema(description = "Stable command UUID. Reuse it with identical inputs to reconcile the outcome.")
        UUID commandId,
        @NotNull @PositiveOrZero @JsonDeserialize(using = PublicationVersionDeserializer.class)
        @Schema(description = "Expected current publication counter. Use zero for an unpublished quiz.", minimum = "0")
        Long expectedPublicationVersion,
        @NotEmpty @ArraySchema(minItems = 1, uniqueItems = true,
                arraySchema = @Schema(description = "Every draft question UUID, in the desired author order."),
                schema = @Schema(type = "string", format = "uuid"))
        List<@NotNull UUID> questionOrder) {

    static final class PublicationVersionDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            // The application must never compare a version rounded or coerced by JSON binding.
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            }
            return parser.getLongValue();
        }
    }
}
