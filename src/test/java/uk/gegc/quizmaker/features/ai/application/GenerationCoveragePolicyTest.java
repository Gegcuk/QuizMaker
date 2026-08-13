package uk.gegc.quizmaker.features.ai.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Generation coverage policy")
class GenerationCoveragePolicyTest {

    @Test
    @DisplayName("Expected counts multiply positive per-chunk counts by selected chunks")
    void expectedCountsMultiplyBySelectedChunks() {
        Map<QuestionType, Integer> expected = GenerationCoveragePolicy.expectedCounts(
                3,
                Map.of(
                        QuestionType.MCQ_SINGLE, 4,
                        QuestionType.TRUE_FALSE, 2,
                        QuestionType.OPEN, 0
                )
        );

        assertThat(expected).containsExactlyInAnyOrderEntriesOf(Map.of(
                QuestionType.MCQ_SINGLE, 12,
                QuestionType.TRUE_FALSE, 6
        ));
    }

    @Test
    @DisplayName("Expected counts reject invalid and overflowing generation targets")
    void expectedCountsRejectInvalidTargets() {
        assertThatThrownBy(() -> GenerationCoveragePolicy.expectedCounts(
                0, Map.of(QuestionType.MCQ_SINGLE, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selectedChunkCount");

        assertThatThrownBy(() -> GenerationCoveragePolicy.expectedCounts(
                1, Map.of(QuestionType.MCQ_SINGLE, -1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question count");

        assertThatThrownBy(() -> GenerationCoveragePolicy.expectedCounts(
                Integer.MAX_VALUE, Map.of(QuestionType.MCQ_SINGLE, 2)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("Exactly eighty percent accepted coverage is a failure")
    void exactlyEightyPercentFails() {
        GenerationCoveragePolicy.Decision decision = GenerationCoveragePolicy.evaluate(
                Map.of(QuestionType.MCQ_SINGLE, 10),
                Difficulty.MEDIUM,
                Map.of(0, questions(8, QuestionType.MCQ_SINGLE, Difficulty.MEDIUM))
        );

        assertThat(decision.successful()).isFalse();
        assertThat(decision.partial()).isFalse();
        assertThat(decision.acceptedTotal()).isEqualTo(8);
        assertThat(decision.missingByType()).containsEntry(QuestionType.MCQ_SINGLE, 2);
    }

    @Test
    @DisplayName("Strictly more than eighty percent accepted coverage is partial success")
    void aboveEightyPercentSucceedsPartially() {
        GenerationCoveragePolicy.Decision decision = GenerationCoveragePolicy.evaluate(
                Map.of(QuestionType.MCQ_SINGLE, 10),
                Difficulty.MEDIUM,
                Map.of(0, questions(9, QuestionType.MCQ_SINGLE, Difficulty.MEDIUM))
        );

        assertThat(decision.successful()).isTrue();
        assertThat(decision.partial()).isTrue();
        assertThat(decision.requestedTotal()).isEqualTo(10);
        assertThat(decision.acceptedTotal()).isEqualTo(9);
        assertThat(decision.discardedTotal()).isZero();
    }

    @Test
    @DisplayName("Complete requested coverage remains a non-partial success")
    void completeCoverageRemainsSuccessful() {
        GenerationCoveragePolicy.Decision decision = GenerationCoveragePolicy.evaluate(
                Map.of(
                        QuestionType.MCQ_SINGLE, 2,
                        QuestionType.TRUE_FALSE, 1
                ),
                Difficulty.HARD,
                Map.of(
                        1, questions(1, QuestionType.TRUE_FALSE, Difficulty.HARD),
                        0, questions(2, QuestionType.MCQ_SINGLE, Difficulty.HARD)
                )
        );

        assertThat(decision.successful()).isTrue();
        assertThat(decision.partial()).isFalse();
        assertThat(decision.acceptedByType()).containsExactlyInAnyOrderEntriesOf(Map.of(
                QuestionType.MCQ_SINGLE, 2,
                QuestionType.TRUE_FALSE, 1
        ));
    }

    @Test
    @DisplayName("Surplus in one type cannot replace a missing requested type")
    void surplusCannotReplaceMissingType() {
        List<Question> generated = new ArrayList<>();
        generated.addAll(questions(4, QuestionType.MCQ_SINGLE, Difficulty.MEDIUM));
        generated.addAll(questions(1, QuestionType.TRUE_FALSE, Difficulty.MEDIUM));

        GenerationCoveragePolicy.Decision decision = GenerationCoveragePolicy.evaluate(
                Map.of(
                        QuestionType.MCQ_SINGLE, 2,
                        QuestionType.TRUE_FALSE, 2
                ),
                Difficulty.MEDIUM,
                Map.of(0, generated)
        );

        assertThat(decision.acceptedByType()).containsExactlyInAnyOrderEntriesOf(Map.of(
                QuestionType.MCQ_SINGLE, 2,
                QuestionType.TRUE_FALSE, 1
        ));
        assertThat(decision.missingByType()).containsOnlyKeys(QuestionType.TRUE_FALSE);
        assertThat(decision.acceptedTotal()).isEqualTo(3);
        assertThat(decision.discardedTotal()).isEqualTo(2);
        assertThat(decision.successful()).isFalse();
    }

    @Test
    @DisplayName("Null, wrong-difficulty, and unrequested questions are excluded from accepted coverage")
    void mismatchedQuestionsAreExcluded() {
        List<Question> generated = new ArrayList<>();
        generated.add(null);
        generated.add(question(null, Difficulty.MEDIUM, "missing-type"));
        generated.add(question(QuestionType.MCQ_SINGLE, Difficulty.EASY, "wrong-difficulty"));
        generated.add(question(QuestionType.OPEN, Difficulty.MEDIUM, "wrong-type"));
        generated.add(question(QuestionType.MCQ_SINGLE, Difficulty.MEDIUM, "accepted"));

        GenerationCoveragePolicy.Decision decision = GenerationCoveragePolicy.evaluate(
                Map.of(QuestionType.MCQ_SINGLE, 2),
                Difficulty.MEDIUM,
                Map.of(0, generated)
        );

        assertThat(decision.acceptedQuestions())
                .extracting(Question::getQuestionText)
                .containsExactly("accepted");
        assertThat(decision.acceptedTotal()).isEqualTo(1);
        assertThat(decision.discardedTotal()).isEqualTo(4);
    }

    @Test
    @DisplayName("Accepted questions are capped deterministically by chunk and question order")
    void acceptedQuestionsAreCappedInDeterministicOrder() {
        Question chunkZeroFirst = question(QuestionType.MCQ_SINGLE, Difficulty.MEDIUM, "chunk-0-first");
        Question chunkZeroSecond = question(QuestionType.MCQ_SINGLE, Difficulty.MEDIUM, "chunk-0-second");
        Question chunkTwo = question(QuestionType.MCQ_SINGLE, Difficulty.MEDIUM, "chunk-2");

        GenerationCoveragePolicy.Decision decision = GenerationCoveragePolicy.evaluate(
                Map.of(QuestionType.MCQ_SINGLE, 2),
                Difficulty.MEDIUM,
                Map.of(
                        2, List.of(chunkTwo),
                        0, List.of(chunkZeroFirst, chunkZeroSecond)
                )
        );

        assertThat(decision.acceptedQuestions())
                .extracting(Question::getQuestionText)
                .containsExactly("chunk-0-first", "chunk-0-second");
        assertThat(decision.acceptedByChunk()).containsOnlyKeys(0);
        assertThat(decision.discardedTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("Exact duplicates across chunks retain the first occurrence and reconcile as discarded")
    void exactDuplicatesAcrossChunksRetainFirstOccurrence() {
        Question first = question(QuestionType.MCQ_SINGLE, Difficulty.MEDIUM, "Repeated question");
        Question distinct = question(QuestionType.MCQ_SINGLE, Difficulty.MEDIUM, "Distinct question");
        Question repeated = question(QuestionType.MCQ_SINGLE, Difficulty.MEDIUM, "  REPEATED   QUESTION ");

        GenerationCoveragePolicy.Decision decision = GenerationCoveragePolicy.evaluate(
                Map.of(QuestionType.MCQ_SINGLE, 3),
                Difficulty.MEDIUM,
                Map.of(
                        2, List.of(repeated),
                        0, List.of(first, distinct)
                )
        );

        assertThat(decision.acceptedQuestions()).containsExactly(first, distinct);
        assertThat(decision.acceptedByChunk()).containsOnlyKeys(0);
        assertThat(decision.acceptedTotal()).isEqualTo(2);
        assertThat(decision.missingByType()).containsEntry(QuestionType.MCQ_SINGLE, 1);
        assertThat(decision.generatedTotal()).isEqualTo(3);
        assertThat(decision.discardedTotal()).isEqualTo(1);
        assertThat(decision.duplicateTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("Eight distinct questions plus repeated copies cannot cross the strict success threshold")
    void repeatedCopiesCannotCrossSuccessThreshold() {
        List<Question> generated = new ArrayList<>(questions(
                8, QuestionType.MCQ_SINGLE, Difficulty.MEDIUM));
        generated.add(question(QuestionType.MCQ_SINGLE, Difficulty.MEDIUM, "MCQ_SINGLE-0"));
        generated.add(question(QuestionType.MCQ_SINGLE, Difficulty.MEDIUM, "MCQ_SINGLE-1"));

        GenerationCoveragePolicy.Decision decision = GenerationCoveragePolicy.evaluate(
                Map.of(QuestionType.MCQ_SINGLE, 10),
                Difficulty.MEDIUM,
                Map.of(0, generated)
        );

        assertThat(decision.acceptedTotal()).isEqualTo(8);
        assertThat(decision.duplicateTotal()).isEqualTo(2);
        assertThat(decision.discardedTotal()).isEqualTo(2);
        assertThat(decision.successful()).isFalse();
        assertThat(decision.partial()).isFalse();
    }

    @Test
    @DisplayName("Meaningfully different stems remain eligible without fuzzy similarity rejection")
    void meaningfullyDifferentStemsRemainEligible() {
        Question first = question(
                QuestionType.TRUE_FALSE,
                Difficulty.MEDIUM,
                "Java is statically typed");
        first.setContent("{\"answer\":true}");
        Question second = question(
                QuestionType.TRUE_FALSE,
                Difficulty.MEDIUM,
                "JavaScript is statically typed");
        second.setContent("{\"answer\":false}");

        GenerationCoveragePolicy.Decision decision = GenerationCoveragePolicy.evaluate(
                Map.of(QuestionType.TRUE_FALSE, 2),
                Difficulty.MEDIUM,
                Map.of(0, List.of(first, second))
        );

        assertThat(decision.acceptedQuestions()).containsExactly(first, second);
        assertThat(decision.duplicateTotal()).isZero();
        assertThat(decision.discardedTotal()).isZero();
        assertThat(decision.successful()).isTrue();
    }

    @Test
    @DisplayName("A candidate without a canonical identity is discarded and never counted")
    void uncanonicalizableCandidateIsDiscarded() {
        Question malformed = question(
                QuestionType.MCQ_SINGLE,
                Difficulty.MEDIUM,
                "Malformed generated question");
        malformed.setContent("{not-json");

        GenerationCoveragePolicy.Decision decision = GenerationCoveragePolicy.evaluate(
                Map.of(QuestionType.MCQ_SINGLE, 1),
                Difficulty.MEDIUM,
                Map.of(0, List.of(malformed))
        );

        assertThat(decision.acceptedQuestions()).isEmpty();
        assertThat(decision.acceptedTotal()).isZero();
        assertThat(decision.discardedTotal()).isEqualTo(1);
        assertThat(decision.duplicateTotal()).isZero();
        assertThat(decision.successful()).isFalse();
    }

    @Test
    @DisplayName("Coverage decision collections are immutable snapshots")
    void decisionCollectionsAreImmutable() {
        List<Question> generated = new ArrayList<>(questions(
                1, QuestionType.MCQ_SINGLE, Difficulty.MEDIUM));
        GenerationCoveragePolicy.Decision decision = GenerationCoveragePolicy.evaluate(
                Map.of(QuestionType.MCQ_SINGLE, 1),
                Difficulty.MEDIUM,
                Map.of(0, generated)
        );

        generated.clear();

        assertThat(decision.acceptedQuestions()).hasSize(1);
        assertThatThrownBy(() -> decision.acceptedByType().put(QuestionType.OPEN, 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> decision.acceptedByChunk().get(0).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static List<Question> questions(int count, QuestionType type, Difficulty difficulty) {
        List<Question> questions = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            questions.add(question(type, difficulty, type + "-" + index));
        }
        return questions;
    }

    private static Question question(QuestionType type, Difficulty difficulty, String text) {
        Question question = new Question();
        question.setType(type);
        question.setDifficulty(difficulty);
        question.setQuestionText(text);
        if (type == null) {
            question.setContent("{}");
            return question;
        }
        question.setContent(switch (type) {
            case MCQ_SINGLE -> """
                    {"options":[
                      {"id":"a","text":"Correct","correct":true},
                      {"id":"b","text":"First distractor","correct":false},
                      {"id":"c","text":"Second distractor","correct":false},
                      {"id":"d","text":"Third distractor","correct":false}
                    ]}
                    """;
            case TRUE_FALSE -> "{\"answer\":true}";
            case OPEN -> "{\"answer\":\"Expected answer\"}";
            default -> throw new IllegalArgumentException("No coverage fixture for type " + type);
        });
        return question;
    }
}
