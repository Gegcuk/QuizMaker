package uk.gegc.quizmaker.features.learning.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "quizmaker.learning.sessions-enabled", havingValue = "true")
public class LearningResponseJsonConfig implements WebMvcConfigurer {
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : List.copyOf(converters)) {
            if (!(converter instanceof MappingJackson2HttpMessageConverter json)) continue;
            var strict = json.getObjectMapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
            for (var shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)) {
                strict.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
            }
            converters.add(0, new MappingJackson2HttpMessageConverter(strict) {
                @Override public boolean canRead(Class<?> type, MediaType media) {
                    return type == LearningSessionApi.RespondRequest.class && super.canRead(type, media);
                }
                @Override public boolean canRead(Type type, Class<?> context, MediaType media) {
                    return type == LearningSessionApi.RespondRequest.class && super.canRead(type, context, media);
                }
                @Override public boolean canWrite(Class<?> type, MediaType media) { return false; }
                @Override public Object read(Type type, Class<?> context, HttpInputMessage input) throws IOException {
                    try { return super.read(type, context, input); }
                    catch (HttpMessageNotReadableException failure) { throw invalid(); }
                }
                @Override protected Object readInternal(Class<?> type, HttpInputMessage input) throws IOException {
                    try { return super.readInternal(type, input); }
                    catch (HttpMessageNotReadableException failure) { throw invalid(); }
                }
            });
            return;
        }
    }

    private static LearningSessionException invalid() {
        return new LearningSessionException(LearningSessionException.Reason.INVALID_INPUT);
    }
}
