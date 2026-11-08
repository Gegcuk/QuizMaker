package uk.gegc.quizmaker.features.result.api.dto;

import com.fasterxml.jackson.annotation.*;
import io.swagger.v3.oas.annotations.media.*;
import java.time.Instant;
import java.util.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ParticipantResultView", description = "Only committed, individually authorized categories. Missing fields are withheld or unavailable, never zero/false substitutes. No pass/fail or separate feedback is implied by a practice score.")
public record ParticipantResultView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1") int releaseVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "UTC release instant.") Instant releasedAt,
        Score totalScore,
        @Schema(description = "Present and true only when completed review is currently authorized.") Boolean reviewAvailable,
        List<Item> items) {
    public ParticipantResultView { items = items == null ? null : List.copyOf(items); }
    @Override public String toString() { return "ParticipantResultView"; }

    @Schema(name = "ReleasedPracticeScore", description = "Exact raw points and normalized integer basis points, 10000 = 100%. No floating-point authority.")
    public record Score(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long earned,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") long possible,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "10000") int basisPoints) {
        @Override public String toString() { return "ReleasedPracticeScore"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ReleasedPracticeItem", description = "Original form-item identity/order. Each protected category is independently omitted unless allowed. UNANSWERED never becomes an incorrect accepted response.")
    public record Item(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID formItemId,
                       @Schema(minimum = "0", maximum = "1") Integer score, Boolean correctness, ResponseState responseState,
                       Answer submittedAnswer, Answer correctAnswer, String explanation) {
        @Override public String toString() { return "ReleasedPracticeItem"; }
    }
    public enum ResponseState { ACCEPTED, UNANSWERED }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
    @JsonSubTypes({@JsonSubTypes.Type(value = Options.class, name = "OPTIONS"),
            @JsonSubTypes.Type(value = OrderedItems.class, name = "ORDERED_ITEMS"),
            @JsonSubTypes.Type(value = BooleanAnswer.class, name = "BOOLEAN"),
            @JsonSubTypes.Type(value = Gaps.class, name = "GAPS")})
    @Schema(name = "ReleasedPracticeAnswer", discriminatorProperty = "kind", discriminatorMapping = {
            @DiscriminatorMapping(value = "OPTIONS", schema = Options.class),
            @DiscriminatorMapping(value = "ORDERED_ITEMS", schema = OrderedItems.class),
            @DiscriminatorMapping(value = "BOOLEAN", schema = BooleanAnswer.class),
            @DiscriminatorMapping(value = "GAPS", schema = Gaps.class)})
    public sealed interface Answer permits Options, OrderedItems, BooleanAnswer, Gaps {
        @JsonProperty("kind") String kind();
    }
    @Schema(name = "ReleasedPracticeOptions", description = "Saved stable option UUIDs, never display indexes or authoring source keys.")
    public record Options(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> optionIds) implements Answer {
        public Options { optionIds = List.copyOf(optionIds); }
        @JsonProperty("kind") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "OPTIONS")
        public String kind() { return "OPTIONS"; }
        @Override public String toString() { return "ReleasedPracticeOptions"; }
    }
    @Schema(name = "ReleasedPracticeOrderedItems")
    public record OrderedItems(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> itemIds) implements Answer {
        public OrderedItems { itemIds = List.copyOf(itemIds); }
        @JsonProperty("kind") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "ORDERED_ITEMS")
        public String kind() { return "ORDERED_ITEMS"; }
        @Override public String toString() { return "ReleasedPracticeOrderedItems"; }
    }
    @Schema(name = "ReleasedPracticeBoolean")
    public record BooleanAnswer(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean answer) implements Answer {
        @JsonProperty("kind") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "BOOLEAN")
        public String kind() { return "BOOLEAN"; }
        @Override public String toString() { return "ReleasedPracticeBoolean"; }
    }
    @Schema(name = "ReleasedPracticeGaps")
    public record Gaps(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Gap> answers) implements Answer {
        public Gaps { answers = List.copyOf(answers); }
        @JsonProperty("kind") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "GAPS")
        public String kind() { return "GAPS"; }
        @Override public String toString() { return "ReleasedPracticeGaps"; }
    }
    @Schema(name = "ReleasedPracticeGap")
    public record Gap(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int gapId,
                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String answer) {
        @Override public String toString() { return "ReleasedPracticeGap"; }
    }
}
