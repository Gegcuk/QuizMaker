package uk.gegc.quizmaker.features.attempt.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import uk.gegc.quizmaker.features.attempt.application.ParticipantViewMediaReader;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
import uk.gegc.quizmaker.features.attempt.application.AttemptDeliveryService;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;

@Schema(name = "AttemptFormResponse", description = "Saved permitted flat content: complete for ALL_AT_ONCE, current readable item for ONE_AT_A_TIME. No pagination; item and option order are authoritative.")
public record AttemptFormResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID attemptId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", description = "Lifecycle storage version, not a timestamp.") long stateVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID formId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1") int schemaVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Advisory server decisions from proven facts. ONE_AT_A_TIME includes permitted read/navigation decisions from locked facts; every command reauthorizes.")
        List<AttemptCommands.Type> allowedActions,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(description = "Current item and optimistic position version for ONE_AT_A_TIME; absent for ALL_AT_ONCE.") NavigationPosition navigation) {
    public AttemptFormResponse(UUID attemptId, long stateVersion, UUID formId, int schemaVersion, List<Item> items, List<AttemptCommands.Type> allowedActions) {
        this(attemptId, stateVersion, formId, schemaVersion, items, allowedActions, null);
    }
    public AttemptFormResponse { items = List.copyOf(items); allowedActions = List.copyOf(allowedActions); }
    public static AttemptFormResponse from(AttemptDeliveryService.View view) {
        return new AttemptFormResponse(view.attemptId(), view.stateVersion(), view.form().id(), view.form().schemaVersion(),
                items(view.form(), view.images()), view.allowedActions(), NavigationPosition.from(view.navigation()));
    }
    public static List<Item> items(uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm form) {
        return items(form, Map.of());
    }
    public static List<Item> items(uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm form,
                                  Map<ParticipantViewMediaReader.Reference, ParticipantViewMediaReader.Image> images) {
        Map<UUID, Map<UUID, AuthorizedImage>> inline = new HashMap<>();
        images.forEach((ref, image) -> {
            if (ref.optionId() != null && inline.computeIfAbsent(ref.formItemId(), id -> new HashMap<>())
                    .put(ref.optionId(), new AuthorizedImage(ref, image)) != null)
                throw new AttemptDeliveryException(AttemptDeliveryException.Reason.CORRUPT);
        });
        return form.items().stream().map(item -> new Item(item.id(), item.position(), item.type(),
                item.questionText(), content(item.content(), inline.getOrDefault(item.id(), Map.of())))).toList();
    }

    @Schema(name = "AttemptFormItem", description = "Stable form-item ID; position is zero-based. No source IDs or protected grading fields.")
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") int position,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"MCQ_SINGLE", "MCQ_MULTI", "TRUE_FALSE", "FILL_GAP", "ORDERING", "OPEN", "MATCHING", "COMPLIANCE"}) QuestionType type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String questionText,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    oneOf = {Choices.class, TrueFalse.class, FillGap.class, Ordering.class, Open.class, Matching.class, Compliance.class}) Content content) {
        @Override public String toString() { return "AttemptFormItem[type=" + type + "]"; }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
    @JsonSubTypes({@JsonSubTypes.Type(value = Choices.class, name = "CHOICES"),
            @JsonSubTypes.Type(value = TrueFalse.class, name = "TRUE_FALSE"),
            @JsonSubTypes.Type(value = FillGap.class, name = "FILL_GAP"),
            @JsonSubTypes.Type(value = Ordering.class, name = "ORDERING"),
            @JsonSubTypes.Type(value = Open.class, name = "OPEN"),
            @JsonSubTypes.Type(value = Matching.class, name = "MATCHING"),
            @JsonSubTypes.Type(value = Compliance.class, name = "COMPLIANCE")})
    @Schema(name = "AttemptFormContent", discriminatorProperty = "kind", discriminatorMapping = {
            @DiscriminatorMapping(value = "CHOICES", schema = Choices.class),
            @DiscriminatorMapping(value = "TRUE_FALSE", schema = TrueFalse.class),
            @DiscriminatorMapping(value = "FILL_GAP", schema = FillGap.class),
            @DiscriminatorMapping(value = "ORDERING", schema = Ordering.class),
            @DiscriminatorMapping(value = "OPEN", schema = Open.class),
            @DiscriminatorMapping(value = "MATCHING", schema = Matching.class),
            @DiscriminatorMapping(value = "COMPLIANCE", schema = Compliance.class)})
    public sealed interface Content permits Choices, TrueFalse, FillGap, Ordering, Open, Matching, Compliance {
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
    @Schema(name = "AttemptFormOpen", description = "Text-answer question. The answer key and private author fields are never part of the form.")
    public record Open() implements Content {
        @JsonProperty("kind") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "OPEN")
        public String kind() { return "OPEN"; }
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

    private static Content content(ParticipantContent value, Map<UUID, AuthorizedImage> images) {
        if (value instanceof ParticipantContent.Choices choices) return new Choices(options(choices.options()));
        if (value instanceof ParticipantContent.TrueFalse) return new TrueFalse();
        if (value instanceof ParticipantContent.Open) return new Open();
        if (value instanceof ParticipantContent.Matching matching) return new Matching(matchingOptions(matching.left(), images), matchingOptions(matching.right(), images));
        if (value instanceof ParticipantContent.Compliance compliance) return new Compliance(complianceStatements(compliance.statements(), images));
        if (value instanceof ParticipantContent.FillGap gap) return new FillGap(gap.text(),
                gap.gaps().stream().map(g -> new Gap(g.id())).toList(), options(gap.options()));
        if (value instanceof ParticipantContent.Ordering order) return new Ordering(options(order.items()));
        throw new IllegalArgumentException("Unsupported participant content");
    }
    private static List<Option> options(List<ParticipantContent.Option> options) {
        return options.stream().map(option -> new Option(option.id(), option.text())).toList();
    }
    @Schema(name = "AttemptFormMatching", description = "Saved left/right columns with stable option UUIDs. No matchId, answer pairs or source keys. Left order is authored; right order is saved for this attempt.")
    public record Matching(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @jakarta.validation.constraints.Size(min = 2, max = 100) List<MatchingOption> left,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @jakarta.validation.constraints.Size(min = 2, max = 100) List<MatchingOption> right) implements Content {
        public Matching { left = List.copyOf(left); right = List.copyOf(right); }
        @JsonProperty("kind") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "MATCHING")
        public String kind() { return "MATCHING"; }
        @Override public String toString() { return "AttemptFormMatching[redacted]"; }
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(name = "AttemptFormMatchingOption", description = "Text, an exact publication-resolved image, or both. A media-only item has no text field.")
    public record MatchingOption(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                 @Schema(maxLength = 10000) String text, MatchingImage image) {
        @Override public String toString() { return "AttemptFormMatchingOption[redacted]"; }
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(name = "AttemptFormMatchingImage", description = "Current display status of the retained inline image. Only AVAILABLE has a reader-authorized URL; no captured/mutable URL fallback. Metadata remains bound to the original publication.")
    public record MatchingImage(@Schema(description = "Present only after current AVAILABLE permission.") String url,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int width,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int height,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mimeType,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ParticipantViewMediaReader.Availability availability) {
        @Override public String toString() { return "AttemptFormMatchingImage[redacted]"; }
    }
    @Schema(name = "AttemptFormCompliance", description = "Saved statement order and UUIDs. No compliant flags, answer key or authoring IDs.")
    public record Compliance(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @jakarta.validation.constraints.Size(min = 2, max = 6) List<ComplianceStatement> statements) implements Content {
        public Compliance { statements = List.copyOf(statements); }
        @JsonProperty("kind") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "COMPLIANCE")
        public String kind() { return "COMPLIANCE"; }
        @Override public String toString() { return "AttemptFormCompliance[redacted]"; }
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(name = "AttemptFormComplianceStatement", description = "Text, an exact retained image, or both. A media-only statement omits text.")
    public record ComplianceStatement(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                      @Schema(maxLength = 10000) String text, ComplianceImage image) {
        @Override public String toString() { return "AttemptFormComplianceStatement[redacted]"; }
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(name = "AttemptFormComplianceImage", description = "Current display status of the original retained inline image. Only AVAILABLE includes a reader-authorized URL; no captured URL fallback.")
    public record ComplianceImage(String url,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int width,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int height,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mimeType,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ParticipantViewMediaReader.Availability availability) {
        @Override public String toString() { return "AttemptFormComplianceImage[redacted]"; }
    }
    private static List<ComplianceStatement> complianceStatements(List<ParticipantContent.ComplianceStatement> statements, Map<UUID, AuthorizedImage> images) {
        return statements.stream().map(statement -> {
            ComplianceImage image = null;
            if (statement.image() != null) {
                var permitted = images.get(statement.id());
                if (permitted == null) throw new AttemptDeliveryException(AttemptDeliveryException.Reason.UNSUPPORTED);
                if (!permitted.reference().assetId().equals(statement.image().assetId()))
                    throw new AttemptDeliveryException(AttemptDeliveryException.Reason.CORRUPT);
                image = new ComplianceImage(permitted.image().url(), statement.image().width(), statement.image().height(),
                        statement.image().mimeType(), permitted.image().availability());
            }
            return new ComplianceStatement(statement.id(), statement.text(), image);
        }).toList();
    }
    private record AuthorizedImage(ParticipantViewMediaReader.Reference reference, ParticipantViewMediaReader.Image image) { }
    private static List<MatchingOption> matchingOptions(List<ParticipantContent.MatchingOption> options, Map<UUID, AuthorizedImage> images) {
        return options.stream().map(option -> {
            MatchingImage image = null;
            if (option.image() != null) {
                var permitted = images.get(option.id());
                if (permitted == null) throw new AttemptDeliveryException(AttemptDeliveryException.Reason.UNSUPPORTED);
                if (!permitted.reference().assetId().equals(option.image().assetId()))
                    throw new AttemptDeliveryException(AttemptDeliveryException.Reason.CORRUPT);
                image = new MatchingImage(permitted.image().url(), option.image().width(), option.image().height(),
                        option.image().mimeType(), permitted.image().availability());
            }
            return new MatchingOption(option.id(), option.text(), image);
        }).toList();
    }
    @Override public String toString() { return "AttemptFormResponse[itemCount=" + items.size() + "]"; }
}
