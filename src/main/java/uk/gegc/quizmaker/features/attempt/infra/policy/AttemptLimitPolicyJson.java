package uk.gegc.quizmaker.features.attempt.infra.policy;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Duration;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException;

/** Preserve historical unlimited bytes; durations are exact integer seconds plus nanoseconds. */
public final class AttemptLimitPolicyJson {
    private AttemptLimitPolicyJson() { }
    public static SimpleModule module() {
        return new SimpleModule("attempt-limit-policy-v1")
                .addSerializer(AttemptLimitPolicy.class, new JsonSerializer<AttemptLimitPolicy>() {
                    @Override public void serialize(AttemptLimitPolicy v, JsonGenerator out, SerializerProvider p) throws IOException {
                        out.writeStartObject();
                        if (!v.cooldown().isZero()) {
                            out.writeNumberField("cooldownNanos", v.cooldown().getNano());
                            out.writeNumberField("cooldownSeconds", v.cooldown().getSeconds());
                        }
                        if (v.maximumAttempts() != null) out.writeNumberField("maximumAttempts", v.maximumAttempts());
                        out.writeStringField("mode", v.mode().name()); out.writeEndObject();
                    }
                }).addDeserializer(AttemptLimitPolicy.class, new JsonDeserializer<AttemptLimitPolicy>() {
                    @Override public AttemptLimitPolicy deserialize(JsonParser p, DeserializationContext c) throws IOException {
                        return read(c.readTree(p));
                    }
                    @Override public AttemptLimitPolicy getNullValue(DeserializationContext c) { throw invalid(); }
                });
    }
    private static AttemptLimitPolicy read(JsonNode v) {
        require(v != null && v.isObject() && v.path("mode").isTextual());
        var mode = ResolvedAttemptPolicy.parseValue(AttemptLimitMode.class, v.get("mode").textValue());
        boolean timed = v.has("cooldownSeconds");
        require(v.size() == 1 + (mode == AttemptLimitMode.FIXED_COUNT ? 1 : 0) + (timed ? 2 : 0));
        long seconds = timed ? number(v.get("cooldownSeconds")) : 0;
        long nanos = timed ? number(v.get("cooldownNanos")) : 0;
        require(seconds >= 0 && nanos >= 0 && nanos < 1_000_000_000L && (!timed || seconds != 0 || nanos != 0));
        return new AttemptLimitPolicy(mode, mode == AttemptLimitMode.FIXED_COUNT ? number(v.get("maximumAttempts")) : null,
                Duration.ofSeconds(seconds, nanos));
    }
    private static long number(JsonNode v) {
        require(v != null && v.isIntegralNumber() && v.canConvertToLong()); return v.longValue();
    }
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static PolicyValidationException invalid() {
        return new PolicyValidationException(PolicyValidationException.Reason.INVALID_VALUE);
    }
}
