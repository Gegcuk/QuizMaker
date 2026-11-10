package uk.gegc.quizmaker.features.attempt.infra.repository;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.attempt.domain.model.AcceptedAnswerEvidenceException;
import uk.gegc.quizmaker.features.attempt.domain.model.AcceptedAnswerFact;
import uk.gegc.quizmaker.features.attempt.domain.repository.CurrentAnswerRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Reads only the projection already loaded by the parent locking query. */
@Component
public class JsonCurrentAnswerRepository implements CurrentAnswerRepository {
    private static final Set<String> ROOT_FIELDS = Set.of("version", "answers");
    private static final Set<String> FACT_FIELDS = Set.of("questionId", "isCorrect", "score", "answeredAtEpochSeconds");
    private final ObjectReader reader;

    public JsonCurrentAnswerRepository(ObjectMapper mapper) {
        reader = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @Override
    public List<AcceptedAnswerFact> readCurrent(UUID attemptId, String projection) {
        if (attemptId == null || projection == null) throw new AcceptedAnswerEvidenceException();
        try {
            JsonNode root = reader.readTree(projection);
            requireFields(root, ROOT_FIELDS);
            JsonNode version = root.get("version");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1)
                throw new AcceptedAnswerEvidenceException();
            JsonNode entries = root.get("answers");
            if (!entries.isObject()) throw new AcceptedAnswerEvidenceException();
            var questions = new HashSet<UUID>();
            var result = new ArrayList<AcceptedAnswerFact>(entries.size());
            var fields = entries.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                UUID id = canonicalUuid(entry.getKey());
                JsonNode fact = entry.getValue();
                requireFields(fact, FACT_FIELDS);
                JsonNode question = fact.get("questionId"), correct = fact.get("isCorrect");
                JsonNode score = fact.get("score"), answeredAt = fact.get("answeredAtEpochSeconds");
                if (!question.isTextual() || !correct.isBoolean() || !answeredAt.isNumber()
                        || (!score.isNull() && (!score.isNumber() || !Double.isFinite(score.doubleValue()))))
                    throw new AcceptedAnswerEvidenceException();
                UUID questionId = canonicalUuid(question.textValue());
                if (!questions.add(questionId)) throw new AcceptedAnswerEvidenceException();
                BigDecimal epoch = answeredAt.decimalValue();
                long seconds = epoch.setScale(0, RoundingMode.FLOOR).longValueExact();
                int nanos = epoch.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).intValueExact();
                result.add(new AcceptedAnswerFact(id, questionId, correct.booleanValue(),
                        score.isNull() ? null : score.doubleValue(), Instant.ofEpochSecond(seconds, nanos)));
            }
            // Preserve deterministic ordering for equal answer timestamps, independently of JSON object order.
            result.sort(Comparator.comparing(answer -> answer.id().toString()));
            return result;
        } catch (JsonProcessingException | IllegalArgumentException | DateTimeException | ArithmeticException invalid) {
            throw new AcceptedAnswerEvidenceException();
        }
    }

    private static UUID canonicalUuid(String text) {
        UUID id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new AcceptedAnswerEvidenceException();
        return id;
    }

    private static void requireFields(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject() || value.size() != expected.size())
            throw new AcceptedAnswerEvidenceException();
        var names = value.fieldNames();
        while (names.hasNext()) if (!expected.contains(names.next())) throw new AcceptedAnswerEvidenceException();
    }
}
