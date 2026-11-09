package uk.gegc.quizmaker.features.attempt.infra.policy;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException;

/** Preserve historical ALL bytes; selection parameters are typed, explicit and canonical. */
public final class SelectionPolicyJson {
    private SelectionPolicyJson() { }
    public static SimpleModule module() {
        return new SimpleModule("attempt-selection-policy-v1")
                .addSerializer(SelectionPolicy.class, new JsonSerializer<SelectionPolicy>() {
                    @Override public void serialize(SelectionPolicy v, JsonGenerator out, SerializerProvider p) throws IOException {
                        out.writeStartObject(); out.writeNumberField("algorithmVersion", v.algorithmVersion());
                        if (v.count() != null) out.writeNumberField("count", v.count());
                        if (v.mode() == SelectionMode.MANUAL_SET) out.writeObjectField("manualItemIds", v.manualItemIds());
                        out.writeStringField("mode", v.mode().name());
                        if (v.percentageBasisPoints() != null) out.writeNumberField("percentageBasisPoints", v.percentageBasisPoints());
                        out.writeEndObject();
                    }
                }).addDeserializer(SelectionPolicy.class, new JsonDeserializer<SelectionPolicy>() {
                    @Override public SelectionPolicy deserialize(JsonParser p, DeserializationContext c) throws IOException {
                        return read(c.readTree(p));
                    }
                    @Override public SelectionPolicy getNullValue(DeserializationContext c) { throw invalid(); }
                });
    }
    private static SelectionPolicy read(JsonNode v) {
        require(v != null && v.isObject() && v.path("mode").isTextual());
        var mode = ResolvedAttemptPolicy.parseValue(SelectionMode.class, v.get("mode").textValue());
        int version = integer(v.get("algorithmVersion"));
        require(v.size() == (mode == SelectionMode.ALL ? 2 : 3));
        List<UUID> ids = new ArrayList<>();
        Integer count = null, percentage = null;
        switch (mode) {
            case ALL -> require(!v.has("count") && !v.has("manualItemIds") && !v.has("percentageBasisPoints"));
            case RANDOM_COUNT -> count = integer(v.get("count"));
            case RANDOM_PERCENTAGE -> percentage = integer(v.get("percentageBasisPoints"));
            case MANUAL_SET -> {
                require(v.path("manualItemIds").isArray());
                for (var id : v.get("manualItemIds")) {
                    require(id.isTextual());
                    try {
                        UUID parsed = UUID.fromString(id.textValue());
                        require(parsed.toString().equals(id.textValue())); ids.add(parsed);
                    } catch (IllegalArgumentException failure) { throw invalid(); }
                }
            }
        }
        return new SelectionPolicy(mode, version, ids, count, percentage);
    }
    private static int integer(JsonNode v) {
        require(v != null && v.isIntegralNumber() && v.canConvertToInt()); return v.intValue();
    }
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static PolicyValidationException invalid() {
        return new PolicyValidationException(PolicyValidationException.Reason.INVALID_VALUE);
    }
}
