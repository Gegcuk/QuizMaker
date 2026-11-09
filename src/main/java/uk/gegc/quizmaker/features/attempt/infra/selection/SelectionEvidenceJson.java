package uk.gegc.quizmaker.features.attempt.infra.selection;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.Evidence;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException.Reason.*;

/** Metadata only. Contains no document bytes, question content, raw seed or secret. */
public final class SelectionEvidenceJson {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private SelectionEvidenceJson() { }
    public static String encode(Evidence evidence) {
        try { return JSON.writeValueAsString(evidence); }
        catch (Exception invalid) { throw new FixedFormException(INVALID_FORM); }
    }
    public static Evidence decode(String json) {
        try {
            Evidence value = JSON.readValue(json, Evidence.class);
            if (value == null || !json.equals(encode(value))) throw new FixedFormException(INVALID_STORED_FORM);
            return value;
        } catch (Exception invalid) { throw new FixedFormException(INVALID_STORED_FORM); }
    }
}
