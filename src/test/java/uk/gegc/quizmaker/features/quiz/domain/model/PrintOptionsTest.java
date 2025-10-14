package uk.gegc.quizmaker.features.quiz.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.CONCURRENT)
@DisplayName("PrintOptions Tests")
class PrintOptionsTest {

    // Preset Tests

    @Test
    @DisplayName("defaults: returns expected default values")
    void defaults_returnsExpectedValues() {
        // When
        PrintOptions options = PrintOptions.defaults();

        // Then
        assertThat(options.includeCover()).isTrue();
        assertThat(options.includeMetadata()).isTrue();
        assertThat(options.answersOnSeparatePages()).isTrue();
        assertThat(options.includeHints()).isFalse();
        assertThat(options.includeExplanations()).isFalse();
        assertThat(options.groupQuestionsByType()).isFalse();
    }

    @Test
    @DisplayName("compact: returns minimal formatting options")
    void compact_returnsMinimalFormatting() {
        // When
        PrintOptions options = PrintOptions.compact();

        // Then
        assertThat(options.includeCover()).isFalse();
        assertThat(options.includeMetadata()).isFalse();
        assertThat(options.answersOnSeparatePages()).isTrue(); // Still separates answers
        assertThat(options.includeHints()).isFalse();
        assertThat(options.includeExplanations()).isFalse();
        assertThat(options.groupQuestionsByType()).isFalse();
    }

    @Test
    @DisplayName("teacherEdition: returns options with hints and explanations")
    void teacherEdition_includesHintsAndExplanations() {
        // When
        PrintOptions options = PrintOptions.teacherEdition();

        // Then
        assertThat(options.includeCover()).isTrue();
        assertThat(options.includeMetadata()).isTrue();
        assertThat(options.answersOnSeparatePages()).isTrue();
        assertThat(options.includeHints()).isTrue();
        assertThat(options.includeExplanations()).isTrue();
        assertThat(options.groupQuestionsByType()).isFalse();
    }

    // Construction Tests

    @Test
    @DisplayName("constructor: accepts all true values")
    void constructor_allTrue_succeeds() {
        // When
        PrintOptions options = new PrintOptions(true, true, true, true, true, true);

        // Then
        assertThat(options.includeCover()).isTrue();
        assertThat(options.includeMetadata()).isTrue();
        assertThat(options.answersOnSeparatePages()).isTrue();
        assertThat(options.includeHints()).isTrue();
        assertThat(options.includeExplanations()).isTrue();
        assertThat(options.groupQuestionsByType()).isTrue();
    }

    @Test
    @DisplayName("constructor: accepts all false values")
    void constructor_allFalse_succeeds() {
        // When
        PrintOptions options = new PrintOptions(false, false, false, false, false, false);

        // Then
        assertThat(options.includeCover()).isFalse();
        assertThat(options.includeMetadata()).isFalse();
        assertThat(options.answersOnSeparatePages()).isFalse();
        assertThat(options.includeHints()).isFalse();
        assertThat(options.includeExplanations()).isFalse();
        assertThat(options.groupQuestionsByType()).isFalse();
    }

    @Test
    @DisplayName("constructor: accepts all null values")
    void constructor_allNull_succeeds() {
        // When
        PrintOptions options = new PrintOptions(null, null, null, null, null, null);

        // Then
        assertThat(options.includeCover()).isNull();
        assertThat(options.includeMetadata()).isNull();
        assertThat(options.answersOnSeparatePages()).isNull();
        assertThat(options.includeHints()).isNull();
        assertThat(options.includeExplanations()).isNull();
        assertThat(options.groupQuestionsByType()).isNull();
    }

    @Test
    @DisplayName("constructor: accepts mixed boolean and null values")
    void constructor_mixedValues_succeeds() {
        // When
        PrintOptions options = new PrintOptions(true, null, false, true, null, false);

        // Then
        assertThat(options.includeCover()).isTrue();
        assertThat(options.includeMetadata()).isNull();
        assertThat(options.answersOnSeparatePages()).isFalse();
        assertThat(options.includeHints()).isTrue();
        assertThat(options.includeExplanations()).isNull();
        assertThat(options.groupQuestionsByType()).isFalse();
    }

    // Preset Comparison Tests

    @Test
    @DisplayName("defaults vs compact: differ in cover and metadata")
    void defaultsVsCompact_differInCoverAndMetadata() {
        // When
        PrintOptions defaults = PrintOptions.defaults();
        PrintOptions compact = PrintOptions.compact();

        // Then
        assertThat(defaults.includeCover()).isNotEqualTo(compact.includeCover());
        assertThat(defaults.includeMetadata()).isNotEqualTo(compact.includeMetadata());
        assertThat(defaults.answersOnSeparatePages()).isEqualTo(compact.answersOnSeparatePages());
    }

    @Test
    @DisplayName("defaults vs teacherEdition: differ in hints and explanations")
    void defaultsVsTeacherEdition_differInHintsAndExplanations() {
        // When
        PrintOptions defaults = PrintOptions.defaults();
        PrintOptions teacher = PrintOptions.teacherEdition();

        // Then
        assertThat(defaults.includeHints()).isNotEqualTo(teacher.includeHints());
        assertThat(defaults.includeExplanations()).isNotEqualTo(teacher.includeExplanations());
        assertThat(defaults.includeCover()).isEqualTo(teacher.includeCover());
        assertThat(defaults.includeMetadata()).isEqualTo(teacher.includeMetadata());
    }

    @Test
    @DisplayName("all presets: always separate answers on different pages")
    void allPresets_alwaysSeparateAnswers() {
        // When & Then
        assertThat(PrintOptions.defaults().answersOnSeparatePages()).isTrue();
        assertThat(PrintOptions.compact().answersOnSeparatePages()).isTrue();
        assertThat(PrintOptions.teacherEdition().answersOnSeparatePages()).isTrue();
    }

    @Test
    @DisplayName("all presets: never group questions by type by default")
    void allPresets_neverGroupByTypeByDefault() {
        // When & Then
        assertThat(PrintOptions.defaults().groupQuestionsByType()).isFalse();
        assertThat(PrintOptions.compact().groupQuestionsByType()).isFalse();
        assertThat(PrintOptions.teacherEdition().groupQuestionsByType()).isFalse();
    }

    // Equality and Immutability Tests

    @Test
    @DisplayName("equals: same values are equal")
    void equals_sameValues_areEqual() {
        // Given
        PrintOptions options1 = new PrintOptions(true, false, true, false, true, false);
        PrintOptions options2 = new PrintOptions(true, false, true, false, true, false);

        // Then
        assertThat(options1).isEqualTo(options2);
        assertThat(options1.hashCode()).isEqualTo(options2.hashCode());
    }

    @Test
    @DisplayName("equals: different values are not equal")
    void equals_differentValues_areNotEqual() {
        // Given
        PrintOptions options1 = new PrintOptions(true, true, true, false, false, false);
        PrintOptions options2 = new PrintOptions(false, false, false, true, true, true);

        // Then
        assertThat(options1).isNotEqualTo(options2);
    }

    @Test
    @DisplayName("equals: presets called twice return equal instances")
    void equals_presetCalledTwice_returnEqualInstances() {
        // When
        PrintOptions defaults1 = PrintOptions.defaults();
        PrintOptions defaults2 = PrintOptions.defaults();

        // Then
        assertThat(defaults1).isEqualTo(defaults2);
        assertThat(defaults1.hashCode()).isEqualTo(defaults2.hashCode());
    }

    @Test
    @DisplayName("record: is immutable")
    void record_isImmutable() {
        // Given
        PrintOptions original = PrintOptions.defaults();

        // When - try to "modify" by creating new instance
        PrintOptions modified = new PrintOptions(
                false, // Changed
                original.includeMetadata(),
                original.answersOnSeparatePages(),
                original.includeHints(),
                original.includeExplanations(),
                original.groupQuestionsByType()
        );

        // Then - original is unchanged
        assertThat(original.includeCover()).isTrue();
        assertThat(modified.includeCover()).isFalse();
        assertThat(original).isNotEqualTo(modified);
    }

    // Specific Use Case Tests

    @ParameterizedTest
    @CsvSource({
        "true, true, true, false, false, false",   // Student version (no hints/explanations)
        "true, true, true, true, true, false",     // Teacher version (with hints/explanations)
        "false, false, true, false, false, false", // Minimal (just questions and answers)
        "true, true, true, false, false, true",    // Grouped by type
        "true, true, false, false, false, false"   // Answers inline (not separate)
    })
    @DisplayName("constructor: handles common use case combinations")
    void constructor_commonUseCases_work(
            boolean cover, boolean meta, boolean separate,
            boolean hints, boolean explanations, boolean grouped
    ) {
        // When
        PrintOptions options = new PrintOptions(cover, meta, separate, hints, explanations, grouped);

        // Then
        assertThat(options.includeCover()).isEqualTo(cover);
        assertThat(options.includeMetadata()).isEqualTo(meta);
        assertThat(options.answersOnSeparatePages()).isEqualTo(separate);
        assertThat(options.includeHints()).isEqualTo(hints);
        assertThat(options.includeExplanations()).isEqualTo(explanations);
        assertThat(options.groupQuestionsByType()).isEqualTo(grouped);
    }

    @Test
    @DisplayName("student version: no hints or explanations")
    void studentVersion_noHintsOrExplanations() {
        // Given - student shouldn't see answers/hints during quiz
        PrintOptions student = new PrintOptions(true, true, true, false, false, false);

        // Then
        assertThat(student.includeHints()).isFalse();
        assertThat(student.includeExplanations()).isFalse();
        assertThat(student.answersOnSeparatePages()).isTrue(); // Answers separate for teacher
    }

    @Test
    @DisplayName("teacher version: includes hints and explanations")
    void teacherVersion_includesHintsAndExplanations() {
        // Given - teacher needs teaching materials
        PrintOptions teacher = PrintOptions.teacherEdition();

        // Then
        assertThat(teacher.includeHints()).isTrue();
        assertThat(teacher.includeExplanations()).isTrue();
    }

    @Test
    @DisplayName("toString: returns readable string representation")
    void toString_returnsReadableString() {
        // Given
        PrintOptions options = PrintOptions.defaults();

        // When
        String result = options.toString();

        // Then
        assertThat(result).contains("PrintOptions");
        assertThat(result).contains("includeCover");
        assertThat(result).contains("includeMetadata");
    }

    // Accessor Tests

    @Test
    @DisplayName("accessors: all fields accessible via record methods")
    void accessors_allFieldsAccessible() {
        // Given
        PrintOptions options = new PrintOptions(true, false, true, false, true, false);

        // When & Then - all accessors work
        assertThat(options.includeCover()).isNotNull();
        assertThat(options.includeMetadata()).isNotNull();
        assertThat(options.answersOnSeparatePages()).isNotNull();
        assertThat(options.includeHints()).isNotNull();
        assertThat(options.includeExplanations()).isNotNull();
        assertThat(options.groupQuestionsByType()).isNotNull();
    }

    @Test
    @DisplayName("accessors: return exact Boolean objects (not primitives)")
    void accessors_returnBooleanObjects() {
        // Given
        PrintOptions options = PrintOptions.defaults();

        // When
        Object cover = options.includeCover();
        Object meta = options.includeMetadata();

        // Then - returns Boolean objects
        assertThat(cover).isInstanceOf(Boolean.class);
        assertThat(meta).isInstanceOf(Boolean.class);
    }

    // Edge Case Tests

    @Test
    @DisplayName("constructor: handles hints without explanations")
    void constructor_hintsWithoutExplanations_valid() {
        // Given
        PrintOptions options = new PrintOptions(true, true, true, true, false, false);

        // Then
        assertThat(options.includeHints()).isTrue();
        assertThat(options.includeExplanations()).isFalse();
    }

    @Test
    @DisplayName("constructor: handles explanations without hints")
    void constructor_explanationsWithoutHints_valid() {
        // Given
        PrintOptions options = new PrintOptions(true, true, true, false, true, false);

        // Then
        assertThat(options.includeHints()).isFalse();
        assertThat(options.includeExplanations()).isTrue();
    }

    @Test
    @DisplayName("constructor: handles grouping without metadata")
    void constructor_groupingWithoutMetadata_valid() {
        // Given
        PrintOptions options = new PrintOptions(true, false, true, false, false, true);

        // Then
        assertThat(options.includeMetadata()).isFalse();
        assertThat(options.groupQuestionsByType()).isTrue();
    }

    @Test
    @DisplayName("constructor: handles cover without metadata")
    void constructor_coverWithoutMetadata_valid() {
        // Given
        PrintOptions options = new PrintOptions(true, false, true, false, false, false);

        // Then
        assertThat(options.includeCover()).isTrue();
        assertThat(options.includeMetadata()).isFalse();
    }

    @Test
    @DisplayName("constructor: handles metadata without cover")
    void constructor_metadataWithoutCover_valid() {
        // Given
        PrintOptions options = new PrintOptions(false, true, true, false, false, false);

        // Then
        assertThat(options.includeCover()).isFalse();
        assertThat(options.includeMetadata()).isTrue();
    }

    @Test
    @DisplayName("constructor: handles answers inline (not separate)")
    void constructor_answersInline_valid() {
        // Given
        PrintOptions options = new PrintOptions(true, true, false, false, false, false);

        // Then
        assertThat(options.answersOnSeparatePages()).isFalse();
    }

    // Preset Uniqueness Tests

    @Test
    @DisplayName("presets: all three presets are different")
    void presets_allThreeAreDifferent() {
        // Given
        PrintOptions defaults = PrintOptions.defaults();
        PrintOptions compact = PrintOptions.compact();
        PrintOptions teacher = PrintOptions.teacherEdition();

        // Then - all different
        assertThat(defaults).isNotEqualTo(compact);
        assertThat(defaults).isNotEqualTo(teacher);
        assertThat(compact).isNotEqualTo(teacher);
    }

    @Test
    @DisplayName("presets: compact is subset of defaults")
    void presets_compactIsSubsetOfDefaults() {
        // Given
        PrintOptions defaults = PrintOptions.defaults();
        PrintOptions compact = PrintOptions.compact();

        // Then - compact has fewer features enabled
        int defaultsCount = countTrueValues(defaults);
        int compactCount = countTrueValues(compact);
        
        assertThat(compactCount).isLessThan(defaultsCount);
    }

    @Test
    @DisplayName("presets: teacherEdition has most features enabled")
    void presets_teacherEditionHasMostFeatures() {
        // Given
        PrintOptions defaults = PrintOptions.defaults();
        PrintOptions compact = PrintOptions.compact();
        PrintOptions teacher = PrintOptions.teacherEdition();

        // Then
        int teacherCount = countTrueValues(teacher);
        int defaultsCount = countTrueValues(defaults);
        int compactCount = countTrueValues(compact);
        
        assertThat(teacherCount).isGreaterThanOrEqualTo(defaultsCount);
        assertThat(teacherCount).isGreaterThan(compactCount);
    }

    // Combination Tests

    @Test
    @DisplayName("constructor: only hints enabled is valid")
    void constructor_onlyHints_valid() {
        // Given - enable only hints
        PrintOptions options = new PrintOptions(false, false, false, true, false, false);

        // Then
        assertThat(options.includeHints()).isTrue();
        assertThat(countTrueValues(options)).isEqualTo(1);
    }

    @Test
    @DisplayName("constructor: only grouping enabled is valid")
    void constructor_onlyGrouping_valid() {
        // Given - enable only grouping
        PrintOptions options = new PrintOptions(false, false, false, false, false, true);

        // Then
        assertThat(options.groupQuestionsByType()).isTrue();
        assertThat(countTrueValues(options)).isEqualTo(1);
    }

    @Test
    @DisplayName("constructor: enables everything except grouping")
    void constructor_everythingExceptGrouping_valid() {
        // Given
        PrintOptions options = new PrintOptions(true, true, true, true, true, false);

        // Then
        assertThat(options.groupQuestionsByType()).isFalse();
        assertThat(countTrueValues(options)).isEqualTo(5);
    }

    // Realistic Scenario Tests

    @Test
    @DisplayName("scenario: exam paper for students")
    void scenario_examPaperForStudents() {
        // Given - exam: no hints, no explanations, no cover, answers separate for teacher
        PrintOptions exam = new PrintOptions(false, true, true, false, false, false);

        // Then
        assertThat(exam.includeCover()).isFalse(); // No cover page needed
        assertThat(exam.includeMetadata()).isTrue(); // Show quiz info
        assertThat(exam.answersOnSeparatePages()).isTrue(); // Teacher can detach answers
        assertThat(exam.includeHints()).isFalse(); // No help during exam
        assertThat(exam.includeExplanations()).isFalse(); // No explanations during exam
    }

    @Test
    @DisplayName("scenario: practice quiz with learning support")
    void scenario_practiceQuizWithLearning() {
        // Given - practice: include hints and explanations to help learning
        PrintOptions practice = new PrintOptions(true, true, true, true, true, false);

        // Then
        assertThat(practice.includeHints()).isTrue(); // Help when stuck
        assertThat(practice.includeExplanations()).isTrue(); // Learn from mistakes
        assertThat(practice.answersOnSeparatePages()).isTrue(); // Can check after
    }

    @Test
    @DisplayName("scenario: quick reference sheet")
    void scenario_quickReferenceSheet() {
        // Given - reference: minimal, just questions and answers together
        PrintOptions reference = new PrintOptions(false, false, false, false, false, false);

        // Then
        assertThat(reference.includeCover()).isFalse();
        assertThat(reference.includeMetadata()).isFalse();
        assertThat(reference.answersOnSeparatePages()).isFalse(); // Answers inline for quick lookup
    }

    @Test
    @DisplayName("scenario: organized study guide by question type")
    void scenario_organizedStudyGuide() {
        // Given - study guide: organized by type with explanations
        PrintOptions studyGuide = new PrintOptions(true, true, true, true, true, true);

        // Then
        assertThat(studyGuide.groupQuestionsByType()).isTrue(); // Organized sections
        assertThat(studyGuide.includeHints()).isTrue(); // Learning support
        assertThat(studyGuide.includeExplanations()).isTrue(); // Deep learning
    }

    // Null Safety Tests

    @Test
    @DisplayName("null values: can be checked with Boolean methods")
    void nullValues_canBeCheckedSafely() {
        // Given
        PrintOptions options = new PrintOptions(null, true, null, false, null, true);

        // When & Then - safe to check with Boolean.TRUE.equals()
        assertThat(Boolean.TRUE.equals(options.includeCover())).isFalse();
        assertThat(Boolean.TRUE.equals(options.includeMetadata())).isTrue();
        assertThat(Boolean.FALSE.equals(options.includeHints())).isTrue();
    }

    @Test
    @DisplayName("hashCode: consistent for same values")
    void hashCode_consistentForSameValues() {
        // Given
        PrintOptions options = PrintOptions.defaults();

        // When
        int hash1 = options.hashCode();
        int hash2 = options.hashCode();

        // Then
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("hashCode: different for different values")
    void hashCode_differentForDifferentValues() {
        // Given
        PrintOptions options1 = PrintOptions.defaults();
        PrintOptions options2 = PrintOptions.compact();

        // When
        int hash1 = options1.hashCode();
        int hash2 = options2.hashCode();

        // Then - likely different (not guaranteed by contract, but very likely)
        assertThat(hash1).isNotEqualTo(hash2);
    }

    // Helper Methods

    private int countTrueValues(PrintOptions options) {
        int count = 0;
        if (Boolean.TRUE.equals(options.includeCover())) count++;
        if (Boolean.TRUE.equals(options.includeMetadata())) count++;
        if (Boolean.TRUE.equals(options.answersOnSeparatePages())) count++;
        if (Boolean.TRUE.equals(options.includeHints())) count++;
        if (Boolean.TRUE.equals(options.includeExplanations())) count++;
        if (Boolean.TRUE.equals(options.groupQuestionsByType())) count++;
        return count;
    }
}

