package uk.gegc.quizmaker.features.attempt.selection;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.*;
import uk.gegc.quizmaker.features.attempt.infra.selection.SelectionEvidenceJson;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SelectionEvidenceJsonTest {
    @Test void exactMetadataRoundTripsForEveryDeliveredStrategyWithoutSeedBytesOrContent() {
        for (var config : List.of(Configuration.all(), Configuration.manualSet(List.of(new UUID(0, 3), new UUID(0, 1))),
                Configuration.randomCount(2), Configuration.randomPercentage(3300))) {
            var evidence = evidence(config);
            String json = SelectionEvidenceJson.encode(evidence);
            assertThat(SelectionEvidenceJson.decode(json)).isEqualTo(evidence);
            assertThat(json).doesNotContain("seedBytes", "secret", "correctAnswer", "questionText", "eligibleItems");
        }
    }
    @Test void unknownVersionsMalformedAndNoncanonicalMetadataHaveABoundedFailure() {
        String original = SelectionEvidenceJson.encode(evidence(Configuration.randomCount(2)));
        for (String invalid : List.of("null", "{}", original + " {}",
                original.replace("\"version\":1", "\"version\":2"),
                original.replace("\"version\":1", "\"version\":1,\"version\":1"),
                original.replace("\"count\":2", "\"count\":\"2\""),
                original.replace("\"count\":2", "\"count\":6"),
                original.replace("\"keyVersion\":7", "\"keyVersion\":0"),
                original.replaceFirst("\\{", "{\"private-secret\":true,"), " " + original)) {
            assertThat(invalid).isNotEqualTo(original);
            assertThatThrownBy(() -> SelectionEvidenceJson.decode(invalid)).isInstanceOfSatisfying(FixedFormException.class,
                    e -> assertThat(e.reason()).isEqualTo(FixedFormException.Reason.INVALID_STORED_FORM))
                    .hasNoCause().hasMessageNotContaining("private-secret");
        }
    }
    private static Evidence evidence(Configuration config) {
        RandomEvidence random = config.strategy().random() ? new RandomEvidence(1,
                new SelectionContext(1, new UUID(0, 50)), 7, "0".repeat(64), 5, 2) : null;
        return new Evidence(1, 1, new UUID(0, 90), 1, config, random);
    }
}
