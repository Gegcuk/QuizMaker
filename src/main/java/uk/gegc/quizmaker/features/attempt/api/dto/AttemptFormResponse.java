package uk.gegc.quizmaker.features.attempt.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.application.AttemptDeliveryService;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;

@Schema(name = "AttemptFormResponse", description = "Complete saved flat form. No pagination; item and option order are authoritative. All fields are non-null.")
public record AttemptFormResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID attemptId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", description = "Lifecycle storage version, not a timestamp.") long stateVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID formId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1") int schemaVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Advisory server decisions from proven facts. This read-only increment reports VIEW_ATTEMPT; absent answer/completion facts never authorize writes.")
        List<AttemptCommands.Type> allowedActions) {
    public AttemptFormResponse { items = List.copyOf(items); allowedActions = List.copyOf(allowedActions); }
    public static AttemptFormResponse from(AttemptDeliveryService.View view) {
        return new AttemptFormResponse(view.attemptId(), view.stateVersion(), view.form().id(), view.form().schemaVersion(),
                items(view.form()), view.allowedActions());
    }
    public static List<Item> items(uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm form) {
        return form.items().stream().map(item -> new Item(item.id(), item.position(), item.type(),
                item.questionText(), content(item.content()))).toList();
    }

    @Schema(name = "AttemptFormItem", description = "Stable form-item ID; position is zero-based. No source IDs or protected grading fields.")
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") int position,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"MCQ_SINGLE", "MCQ_MULTI", "TRUE_FALSE", "FILL_GAP", "ORDERING"}) QuestionType type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String questionText,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    oneOf = {Choices.class, TrueFalse.class, FillGap.class, Ordering.class}) Content content) {
        @Override public String toString() { return "AttemptFormItem[type=" + type + "]"; }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
    @JsonSubTypes({@JsonSubTypes.Type(value = Choices.class, name = "CHOICES"),
            @JsonSubTypes.Type(value = TrueFalse.class, name = "TRUE_FALSE"),
            @JsonSubTypes.Type(value = FillGap.class, name = "FILL_GAP"),
            @JsonSubTypes.Type(value = Ordering.class, name = "ORDERING")})
    @Schema(name = "AttemptFormContent", discriminatorProperty = "kind", discriminatorMapping = {
            @DiscriminatorMapping(value = "CHOICES", schema = Choices.class),
            @DiscriminatorMapping(value = "TRUE_FALSE", schema = TrueFalse.class),
            @DiscriminatorMapping(value = "FILL_GAP", schema = FillGap.class),
            @DiscriminatorMapping(value = "ORDERING", schema = Ordering.class)})
    public sealed interface Content permits Choices, TrueFalse, FillGap, Ordering {
        @JsonProperty("kind") String kind();
    }

    @Schema(name = "AttemptFormOption")
    public record Option(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String text) {
        @Override public String toString() { return "AttemptFormOption"; }
    }
    @Schema(name = "AttemptFormGap")
    public record Gap(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int id) { }
    @Schema(name = "AttemptFormChoices")
    public record Choices(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Option> options) implements Content {
        public Choices { options = List.copyOf(options); }
        @JsonProperty("kind") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "CHOICES")
        public String kind() { return "CHOICES"; }
        @Override public String toString() { return "AttemptFormChoices"; }
    }
    @Schema(name = "AttemptFormTrueFalse")
    public record TrueFalse() implements Content {
        @JsonProperty("kind") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "TRUE_FALSE")
        public String kind() { return "TRUE_FALSE"; }
    }
    @Schema(name = "AttemptFormFillGap")
    public record FillGap(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String text,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Gap> gaps,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Option> options) implements Content {
        public FillGap { gaps = List.copyOf(gaps); options = List.copyOf(options); }
        @JsonProperty("kind") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "FILL_GAP")
        public String kind() { return "FILL_GAP"; }
        @Override public String toString() { return "AttemptFormFillGap"; }
    }
    @Schema(name = "AttemptFormOrdering")
    public record Ordering(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Option> items) implements Content {
        public Ordering { items = List.copyOf(items); }
        @JsonProperty("kind") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "ORDERING")
        public String kind() { return "ORDERING"; }
        @Override public String toString() { return "AttemptFormOrdering"; }
    }

    private static Content content(ParticipantContent value) {
        if (value instanceof ParticipantContent.Choices choices) return new Choices(options(choices.options()));
        if (value instanceof ParticipantContent.TrueFalse) return new TrueFalse();
        if (value instanceof ParticipantContent.FillGap gap) return new FillGap(gap.text(),
                gap.gaps().stream().map(g -> new Gap(g.id())).toList(), options(gap.options()));
        if (value instanceof ParticipantContent.Ordering order) return new Ordering(options(order.items()));
        throw new IllegalArgumentException("Unsupported participant content");
    }
    private static List<Option> options(List<ParticipantContent.Option> options) {
        return options.stream().map(option -> new Option(option.id(), option.text())).toList();
    }
    @Override public String toString() { return "AttemptFormResponse[itemCount=" + items.size() + "]"; }
}
