package uk.gegc.quizmaker.features.attempt.infra.policy;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException;

/** Historical NONE bytes stay identical. Exact integer units avoid mapper/zone/float drift. */
public final class TimingPolicyJson {
    private TimingPolicyJson() { }
    public static SimpleModule module() {
        return new SimpleModule("attempt-timing-v1").addSerializer(TimingPolicy.class, new JsonSerializer<TimingPolicy>() {
            @Override public void serialize(TimingPolicy v, JsonGenerator out, SerializerProvider p) throws IOException {
                out.writeStartObject();
                if (v.duration() != null) {
                    out.writeNumberField("durationNanos", v.duration().getNano());
                    out.writeNumberField("durationSeconds", v.duration().getSeconds());
                }
                out.writeStringField("mode", v.mode().name());
                if (v.overallDeadline() != null) {
                    out.writeNumberField("overallDeadlineNanos", v.overallDeadline().getNano());
                    out.writeNumberField("overallDeadlineSeconds", v.overallDeadline().getEpochSecond());
                }
                out.writeEndObject();
            }
        }).addDeserializer(TimingPolicy.class, new JsonDeserializer<TimingPolicy>() {
            @Override public TimingPolicy deserialize(JsonParser p, DeserializationContext c) throws IOException { return read(c.readTree(p)); }
            @Override public TimingPolicy getNullValue(DeserializationContext c) { throw invalid(); }
        });
    }
    private static TimingPolicy read(JsonNode v) {
        require(v != null && v.isObject() && v.path("mode").isTextual());
        var mode = ResolvedAttemptPolicy.parseValue(TimingMode.class, v.get("mode").textValue());
        boolean duration = v.has("durationSeconds"), overall = v.has("overallDeadlineSeconds");
        require(v.size() == 1 + (duration ? 2 : 0) + (overall ? 2 : 0));
        long ds = duration ? number(v.get("durationSeconds")) : 0;
        long dn = duration ? number(v.get("durationNanos")) : 0;
        long os = overall ? number(v.get("overallDeadlineSeconds")) : 0;
        long on = overall ? number(v.get("overallDeadlineNanos")) : 0;
        require(ds >= 0 && dn >= 0 && dn < 1_000_000_000L && on >= 0 && on < 1_000_000_000L);
        try { return new TimingPolicy(mode, duration ? Duration.ofSeconds(ds, dn) : null, overall ? Instant.ofEpochSecond(os, on) : null); }
        catch (java.time.DateTimeException | ArithmeticException failure) { throw invalid(); }
    }
    private static long number(JsonNode v) { require(v != null && v.isIntegralNumber() && v.canConvertToLong()); return v.longValue(); }
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static PolicyValidationException invalid() { return new PolicyValidationException(PolicyValidationException.Reason.INVALID_VALUE); }
}
