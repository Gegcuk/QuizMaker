package uk.gegc.quizmaker.features.attempt.api.dto;

import com.fasterxml.jackson.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

@Schema(name = "AcceptResponseRequest", description = "One confirmed answer to a saved form item. No subject, grading or disclosure authority is accepted.")
public record AcceptResponseRequest(@NotNull UUID formItemId, @NotNull @Min(0) Long expectedStateVersion,
                                    @NotNull @Valid @Schema(oneOf = {Single.class, Multiple.class, Truth.class, Ordering.class, Gaps.class}) Response response) {
    @Override public String toString() { return "AcceptResponseRequest[redacted]"; }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
    @JsonSubTypes({@JsonSubTypes.Type(value = Single.class, name = "MCQ_SINGLE"),
            @JsonSubTypes.Type(value = Multiple.class, name = "MCQ_MULTI"),
            @JsonSubTypes.Type(value = Truth.class, name = "TRUE_FALSE"),
            @JsonSubTypes.Type(value = Ordering.class, name = "ORDERING"),
            @JsonSubTypes.Type(value = Gaps.class, name = "FILL_GAP")})
    @Schema(name = "ConfirmedResponse", discriminatorProperty = "type",
            discriminatorMapping = {@DiscriminatorMapping(value = "MCQ_SINGLE", schema = Single.class),
            @DiscriminatorMapping(value = "MCQ_MULTI", schema = Multiple.class),
            @DiscriminatorMapping(value = "TRUE_FALSE", schema = Truth.class),
            @DiscriminatorMapping(value = "ORDERING", schema = Ordering.class),
            @DiscriminatorMapping(value = "FILL_GAP", schema = Gaps.class)})
    public sealed interface Response permits Single, Multiple, Truth, Ordering, Gaps {
        @JsonProperty("type") QuestionType type();
    }
    @Schema(name = "ConfirmedSingleChoice")
    public record Single(@NotNull UUID selectedOptionId) implements Response {
        @JsonProperty("type") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "MCQ_SINGLE")
        public QuestionType type() { return QuestionType.MCQ_SINGLE; }
        @Override public String toString() { return "ConfirmedSingleChoice[redacted]"; }
    }
    @Schema(name = "ConfirmedMultipleChoice")
    public record Multiple(@NotNull @Size(max = 100) List<@NotNull UUID> selectedOptionIds) implements Response {
        @JsonProperty("type") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "MCQ_MULTI")
        public QuestionType type() { return QuestionType.MCQ_MULTI; }
        @Override public String toString() { return "ConfirmedMultipleChoice[redacted]"; }
    }
    @Schema(name = "ConfirmedTrueFalse")
    public record Truth(@NotNull Boolean answer) implements Response {
        @JsonProperty("type") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "TRUE_FALSE")
        public QuestionType type() { return QuestionType.TRUE_FALSE; }
        @Override public String toString() { return "ConfirmedTrueFalse[redacted]"; }
    }
    @Schema(name = "ConfirmedOrdering")
    public record Ordering(@NotNull @Size(max = 100) List<@NotNull UUID> orderedItemIds) implements Response {
        @JsonProperty("type") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "ORDERING")
        public QuestionType type() { return QuestionType.ORDERING; }
        @Override public String toString() { return "ConfirmedOrdering[redacted]"; }
    }
    @Schema(name = "ConfirmedGapAnswers")
    public record Gaps(@NotNull @Size(max = 100) List<@NotNull @Valid Gap> answers) implements Response {
        @JsonProperty("type") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "FILL_GAP")
        public QuestionType type() { return QuestionType.FILL_GAP; }
        @Override public String toString() { return "ConfirmedGapAnswers[redacted]"; }
    }
    @Schema(name = "ConfirmedGapAnswer")
    public record Gap(@NotNull Integer gapId, @NotNull @Schema(maxLength = 10000) String answer) {
        @Override public String toString() { return "ConfirmedGapAnswer[redacted]"; }
    }
}
