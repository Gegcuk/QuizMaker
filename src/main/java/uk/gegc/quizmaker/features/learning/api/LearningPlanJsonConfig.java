package uk.gegc.quizmaker.features.learning.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;
import uk.gegc.quizmaker.features.learning.infra.scheduling.LearningPlanFormat;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;

/** Strict, bounded request parsing before any command; private values never enter MVC errors. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="quizmaker.learning",name={"sessions-enabled","custom-plans-enabled"},havingValue="true")
public class LearningPlanJsonConfig implements WebMvcConfigurer {
    private final Validator validator;
    public LearningPlanJsonConfig(Validator validator) { this.validator=validator; }
    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        var original=converters.stream().filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast).findFirst().orElseThrow();
        var mapper=original.getObjectMapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        var types=Set.of(LearningPlanApi.SaveRequest.class,LearningPlanApi.PauseRequest.class,LearningPlanApi.BindRequest.class,LearningPlanApi.ChangeRequest.class,
                LearningTimezoneController.PreviewRequest.class,LearningTimezoneController.ConfirmRequest.class);
        converters.add(0,new MappingJackson2HttpMessageConverter(mapper) {
            @Override public boolean canRead(Class<?> type,MediaType media) { return types.contains(type) && super.canRead(type,media); }
            @Override public boolean canRead(Type type,Class<?> context,MediaType media) { return types.contains(type) && super.canRead(type,context,media); }
            @Override public boolean canWrite(Class<?> type,MediaType media) { return false; }
            @Override public Object read(Type type,Class<?> context,HttpInputMessage input) throws IOException { return parse((Class<?>)type,input); }
            @Override protected Object readInternal(Class<?> type,HttpInputMessage input) throws IOException { return parse(type,input); }
            private Object parse(Class<?> type,HttpInputMessage input) throws IOException {
                try {
                    byte[] bytes=input.getBody().readNBytes(LearningPlanFormat.MAX_BYTES+1);
                    if(bytes.length>LearningPlanFormat.MAX_BYTES) throw new LearningSessionException(INVALID_INPUT);
                    Object value=mapper.readValue(bytes,type);
                    if(value==null || !validator.validate(value).isEmpty()) throw new LearningSessionException(INVALID_INPUT);
                    return value;
                } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {
                    Throwable cause=failure;
                    for(int i=0;cause!=null && i<8;i++,cause=cause.getCause()) if(cause instanceof LearningSessionException domain)
                        throw new LearningSessionException(domain.reason());
                    throw new LearningSessionException(INVALID_INPUT);
                }
            }
        });
    }
}
