package uk.gegc.quizmaker.features.result.api;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gegc.quizmaker.features.result.api.dto.ReleaseConfigurationDtos.*;

/** Strict only for these additive DTOs; existing clients keep their parsers. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "quizmaker.results.release-configuration-enabled", havingValue = "true")
public class ReleaseConfigurationJsonConfig implements WebMvcConfigurer {
    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : List.copyOf(converters)) if (converter instanceof MappingJackson2HttpMessageConverter existing) {
            var mapper = existing.getObjectMapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
            mapper.registerModule(new com.fasterxml.jackson.databind.module.SimpleModule().addDeserializer(UUID.class, new JsonDeserializer<UUID>() {
                @Override public UUID deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                    String value = parser.getValueAsString();
                    try {
                        if (!parser.hasToken(JsonToken.VALUE_STRING) || value == null || value.length() != 36) throw new IllegalArgumentException();
                        UUID id = UUID.fromString(value);
                        if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
                        return id;
                    } catch (IllegalArgumentException invalid) { throw JsonMappingException.from(parser, "Invalid configuration identifier"); }
                }
            }));
            converters.add(0, new MappingJackson2HttpMessageConverter(mapper) {
                private boolean supported(Type type) { return type == RuleRequest.class || type == PublishRequest.class; }
                @Override public boolean canRead(Class<?> type, MediaType media) { return supported(type) && super.canRead(type, media); }
                @Override public boolean canRead(Type type, Class<?> context, MediaType media) { return supported(type) && super.canRead(type, context, media); }
                @Override public boolean canWrite(Class<?> type, MediaType media) { return false; }
            });
            break;
        }
    }
}
