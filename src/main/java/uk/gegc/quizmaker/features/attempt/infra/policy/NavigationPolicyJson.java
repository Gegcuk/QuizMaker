package uk.gegc.quizmaker.features.attempt.infra.policy;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;

/** Infrastructure-only codec. Historical FREE bytes stay exact; domain values remain transport-neutral. */
public final class NavigationPolicyJson {
    private NavigationPolicyJson() { }
    public static SimpleModule module() {
        return new SimpleModule("attempt-navigation-policy-v1")
                .addSerializer(NavigationPolicy.class, new JsonSerializer<NavigationPolicy>() {
                    @Override public void serialize(NavigationPolicy value, JsonGenerator out, SerializerProvider provider) throws IOException {
                        out.writeStartObject(); out.writeStringField("mode", value.mode().name());
                        if (value.rules() != null) out.writeObjectField("rules", value.rules());
                        out.writeEndObject();
                    }
                })
                .addDeserializer(NavigationPolicy.class, new JsonDeserializer<NavigationPolicy>() {
                    @Override public NavigationPolicy deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                        return read(context.readTree(parser));
                    }
                    @Override public NavigationPolicy getNullValue(DeserializationContext context) {
                        throw new PolicyValidationException(PolicyValidationException.Reason.INVALID_VALUE);
                    }
                });
    }

    private static NavigationPolicy read(JsonNode value) {
        require(value != null && value.isObject() && value.path("mode").isTextual());
        var mode = ResolvedAttemptPolicy.parseValue(NavigationMode.class, value.get("mode").textValue());
        if (!value.has("rules")) {
            require(value.size() == 1 && mode == NavigationMode.FREE);
            return new NavigationPolicy(mode);
        }
        var rules = value.get("rules");
        require(value.size() == 2 && rules.isObject() && rules.size() == 3
                && rules.path("version").isIntegralNumber() && rules.path("version").canConvertToInt()
                && rules.path("allowSkipAndReturn").isBoolean() && rules.path("allowAcceptedRevisit").isBoolean());
        return new NavigationPolicy(mode, new NavigationRules(rules.get("version").intValue(),
                rules.get("allowSkipAndReturn").booleanValue(), rules.get("allowAcceptedRevisit").booleanValue()));
    }
    private static void require(boolean valid) {
        if (!valid) throw new PolicyValidationException(PolicyValidationException.Reason.INVALID_VALUE);
    }
}
