package uk.gegc.quizmaker.features.quiz.application.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationCoverageOutcome;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Generation coverage snapshot")
class GenerationCoverageSnapshotTest {

    @Test
    @DisplayName("Requested types are immutable and ordered by the stable question enum")
    void requestedTypesAreImmutableAndDeterministic() {
        List<GenerationCoverageSnapshot.TypeCoverage> types = new ArrayList<>(List.of(
                new GenerationCoverageSnapshot.TypeCoverage(QuestionType.FILL_GAP, 5, 4, 1),
                new GenerationCoverageSnapshot.TypeCoverage(QuestionType.MCQ_SINGLE, 5, 5, 0)
        ));

        GenerationCoverageSnapshot snapshot = new GenerationCoverageSnapshot(
                GenerationCoverageOutcome.PARTIAL, 80, 10, 9, 1, 2, types);
        types.clear();

        assertThat(snapshot.types()).extracting(GenerationCoverageSnapshot.TypeCoverage::questionType)
                .containsExactly(QuestionType.MCQ_SINGLE, QuestionType.FILL_GAP);
        assertThatThrownBy(() -> snapshot.types().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Aggregate counts must equal the sum of every requested type")
    void aggregateCountsMustMatchTypeCounts() {
        assertThatThrownBy(() -> new GenerationCoverageSnapshot(
                GenerationCoverageOutcome.PARTIAL,
                80,
                10,
                9,
                1,
                0,
                List.of(new GenerationCoverageSnapshot.TypeCoverage(
                        QuestionType.MCQ_SINGLE, 10, 8, 2))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate coverage");
    }

    @Test
    @DisplayName("Duplicate question type facts are rejected")
    void duplicateQuestionTypesAreRejected() {
        assertThatThrownBy(() -> new GenerationCoverageSnapshot(
                GenerationCoverageOutcome.COMPLETE,
                80,
                2,
                2,
                0,
                0,
                List.of(
                        new GenerationCoverageSnapshot.TypeCoverage(QuestionType.OPEN, 1, 1, 0),
                        new GenerationCoverageSnapshot.TypeCoverage(QuestionType.OPEN, 1, 1, 0)
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    @DisplayName("Outcome structure cannot contradict complete or missing totals")
    void outcomeMustMatchCompleteOrMissingStructure() {
        assertThatThrownBy(() -> new GenerationCoverageSnapshot(
                GenerationCoverageOutcome.COMPLETE,
                80,
                10,
                9,
                1,
                0,
                List.of(new GenerationCoverageSnapshot.TypeCoverage(
                        QuestionType.MCQ_SINGLE, 10, 9, 1))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COMPLETE");
    }
}
