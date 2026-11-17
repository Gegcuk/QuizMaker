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
                                    @NotNull @Valid @Schema(oneOf = {Single.class, Multiple.class, Truth.class, Ordering.class, Gaps.class, Open.class, Matching.class}) Response response) {
    @Override public String toString() { return "AcceptResponseRequest[redacted]"; }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
    @JsonSubTypes({@JsonSubTypes.Type(value = Single.class, name = "MCQ_SINGLE"),
            @JsonSubTypes.Type(value = Multiple.class, name = "MCQ_MULTI"),
            @JsonSubTypes.Type(value = Truth.class, name = "TRUE_FALSE"),
            @JsonSubTypes.Type(value = Ordering.class, name = "ORDERING"),
            @JsonSubTypes.Type(value = Gaps.class, name = "FILL_GAP"),
            @JsonSubTypes.Type(value = Open.class, name = "OPEN"),
            @JsonSubTypes.Type(value = Matching.class, name = "MATCHING")})
    @Schema(name = "ConfirmedResponse", discriminatorProperty = "type",
            discriminatorMapping = {@DiscriminatorMapping(value = "MCQ_SINGLE", schema = Single.class),
            @DiscriminatorMapping(value = "MCQ_MULTI", schema = Multiple.class),
            @DiscriminatorMapping(value = "TRUE_FALSE", schema = Truth.class),
            @DiscriminatorMapping(value = "ORDERING", schema = Ordering.class),
            @DiscriminatorMapping(value = "FILL_GAP", schema = Gaps.class),
            @DiscriminatorMapping(value = "OPEN", schema = Open.class),
            @DiscriminatorMapping(value = "MATCHING", schema = Matching.class)})
    public sealed interface Response permits Single, Multiple, Truth, Ordering, Gaps, Open, Matching {
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
    @Schema(name = "ConfirmedOpenAnswer", description = "Original text, including an empty answer. At most 10000 Unicode code points and the existing 65536-byte response limit. OPEN v1 compares trimmed ROOT-lowercase text; no partial credit.")
    public record Open(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 10000, example = " Paris ") String answer) implements Response {
        @JsonProperty("type") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "OPEN")
        public QuestionType type() { return QuestionType.OPEN; }
        @Override public String toString() { return "ConfirmedOpenAnswer[redacted]"; }
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
    @Schema(name = "ConfirmedMatching", description = "Ordered pairs of saved left/right option UUIDs from this question. Empty/partial answers are valid and score zero. Repeated left IDs retain order: the last pair wins. No partial credit or display indexes.")
    public record Matching(@NotNull @Size(max = 100) List<@NotNull @Valid Match> matches) implements Response {
        public Matching { matches = matches == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(matches)); }
        @JsonProperty("type") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "MATCHING")
        public QuestionType type() { return QuestionType.MATCHING; }
        @Override public String toString() { return "ConfirmedMatching[redacted]"; }
    }
    @Schema(name = "ConfirmedMatch", description = "Both UUIDs must belong to the named side of the same saved question.")
    public record Match(@NotNull UUID leftId, @NotNull UUID rightId) {
        @Override public String toString() { return "ConfirmedMatch[redacted]"; }
    }
}
