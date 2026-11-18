package uk.gegc.quizmaker.features.attempt.infra.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract.*;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.infra.policy.*;
import uk.gegc.quizmaker.features.result.domain.model.AutomaticResult;

import static uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException.require;

/**
 * Explicit internal activity/v1 envelopes; no global mapper registration or polymorphic class names.
 * Existing scored DTOs and canonical policy bytes are unchanged. This codec activates no endpoint.
 * Strict shape checks reject score fields even when null/zero in an ungraded envelope.
 */
public final class ActivityContractJson {
    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(NavigationPolicyJson.module()).addModule(SelectionPolicyJson.module())
            .addModule(AttemptLimitPolicyJson.module()).addModule(TimingPolicyJson.module())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    // Existing AutomaticResult.Item explicitly permits a null responseId for an unanswered item.
    // Required object fields are checked by shape/domain constructors; primitives still reject null.

    public String encodePolicy(Policy policy) {
        return safe(() -> {
            require(policy != null, INVALID_INPUT);
            var root = envelope(policy.version(), policy.kind());
            if (policy instanceof GradedPolicy graded) root.set("policy", mapper.valueToTree(graded.policy()));
            else {
                var ungraded = (UngradedPolicy) policy;
                root.put("responseRetention", ungraded.responseRetention().name());
                root.set("participation", mapper.valueToTree(ungraded.participation()));
            }
            return root.toString();
        });
    }

    public Policy decodePolicy(String json) {
        return safe(() -> {
            var root = read(json);
            var kind = kind(root);
            if (kind == Kind.GRADED) {
                shape(root, "version", "kind", "policy");
                return new GradedPolicy(ActivityContract.VERSION, convert(root.get("policy"), ResolvedAttemptPolicy.class));
            }
            shape(root, "version", "kind", "responseRetention", "participation");
            var retention = ActivityContract.parseValue(ResponseRetention.class, text(root.get("responseRetention")));
            var participation = root.get("participation");
            shape(participation, "items");
            require(participation.get("items").isArray(), INVALID_INPUT);
            var items = new ArrayList<ItemRequirement>();
            for (var item : participation.get("items")) {
                shape(item, "formItemId", "required");
                var rawId = text(item.get("formItemId"));
                var id = UUID.fromString(rawId);
                require(id.toString().equals(rawId) && item.get("required").isBoolean(), INVALID_INPUT);
                items.add(new ItemRequirement(id, item.get("required").booleanValue()));
            }
            return new UngradedPolicy(ActivityContract.VERSION, retention, new ParticipationRequirements(items));
        });
    }

    public String encodeOutcome(Outcome outcome) {
        return safe(() -> {
            require(outcome != null, INVALID_INPUT);
            var root = envelope(outcome.version(), outcome.kind());
            if (outcome instanceof GradedOutcome graded) root.set("result", mapper.valueToTree(graded.result()));
            else {
                var completion = root.putObject("completion");
                if (outcome instanceof Completed completed) {
                    completion.put("state", "COMPLETED");
                    completion.put("completedAt", completed.completedAt().toString());
                } else completion.put("state", ((Incomplete) outcome).state().name());
            }
            return root.toString();
        });
    }

    public Outcome decodeOutcome(String json) {
        return safe(() -> {
            var root = read(json);
            var kind = kind(root);
            if (kind == Kind.GRADED) {
                shape(root, "version", "kind", "result");
                return new GradedOutcome(ActivityContract.VERSION, convert(root.get("result"), AutomaticResult.class));
            }
            shape(root, "version", "kind", "completion");
            var completion = root.get("completion");
            require(completion != null && completion.isObject(), INVALID_INPUT);
            var state = text(completion.get("state"));
            if ("COMPLETED".equals(state)) {
                shape(completion, "state", "completedAt");
                return new Completed(ActivityContract.VERSION, Instant.parse(text(completion.get("completedAt"))));
            }
            shape(completion, "state");
            return new Incomplete(ActivityContract.VERSION, ActivityContract.parseValue(IncompleteState.class, state));
        });
    }

    private ObjectNode envelope(int version, Kind kind) {
        var root = mapper.createObjectNode();
        root.put("version", version);
        root.put("kind", kind.name());
        return root;
    }

    private Kind kind(JsonNode root) {
        require(root != null && root.isObject(), INVALID_INPUT);
        var version = root.get("version");
        require(version != null && version.isIntegralNumber() && version.canConvertToInt(), INVALID_INPUT);
        require(version.intValue() == ActivityContract.VERSION, UNSUPPORTED_VERSION);
        return ActivityContract.parseKind(text(root.get("kind")));
    }

    private JsonNode read(String json) {
        require(json != null, INVALID_INPUT);
        try { return mapper.readTree(json); }
        catch (JsonProcessingException failure) { throw new ActivityContractException(INVALID_INPUT); }
    }

    private <T> T convert(JsonNode value, Class<T> type) {
        require(value != null && value.isObject(), INVALID_INPUT);
        try { return mapper.treeToValue(value, type); }
        catch (JsonProcessingException failure) { throw new ActivityContractException(INVALID_INPUT); }
    }

    private static String text(JsonNode value) {
        require(value != null && value.isTextual(), INVALID_INPUT);
        return value.textValue();
    }

    private static void shape(JsonNode value, String... fields) {
        require(value != null && value.isObject() && value.size() == fields.length, INVALID_INPUT);
        for (var field : Set.of(fields)) require(value.hasNonNull(field), INVALID_INPUT);
    }

    private static <T> T safe(Supplier<T> operation) {
        try { return operation.get(); }
        catch (ActivityContractException failure) { throw failure; }
        catch (RuntimeException failure) { throw new ActivityContractException(INVALID_INPUT); }
    }
}
