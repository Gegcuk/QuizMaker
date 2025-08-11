package uk.gegc.quizmaker.service.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.dto.quiz.QuizDto;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.quiz.QuizStatus;
import uk.gegc.quizmaker.model.quiz.Visibility;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.tag.Tag;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuizHashCalculatorTest {

    private final QuizHashCalculator calculator = new QuizHashCalculator();

    private QuizDto sample(String title, String desc, List<UUID> tags) {
        return new QuizDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                title,
                desc,
                Visibility.PRIVATE,
                Difficulty.MEDIUM,
                QuizStatus.DRAFT,
                10,
                true,
                false,
                null,
                tags,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("Same content with different tag order yields same hash")
    void stableAgainstTagOrder() {
        List<UUID> tags1 = List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                   UUID.fromString("00000000-0000-0000-0000-000000000002"));
        List<UUID> tags2 = List.of(tags1.get(1), tags1.get(0));

        String h1 = calculator.calculateContentHash(sample("Title", "Desc", tags1));
        String h2 = calculator.calculateContentHash(sample("Title", "Desc", tags2));
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("Title/description normalization (spaces, case) does not change hash")
    void normalizationInsensitive() {
        List<UUID> tags = List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        String h1 = calculator.calculateContentHash(sample("  TiTle  ", "A  desc\nwith\tmixed  spaces", tags));
        String h2 = calculator.calculateContentHash(sample("title", "a desc with mixed spaces", tags));
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("Different content yields different hash")
    void contentChangesAffectHash() {
        List<UUID> tags = List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        String h1 = calculator.calculateContentHash(sample("Title", "Desc", tags));
        String h2 = calculator.calculateContentHash(sample("Title 2", "Desc", tags));
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("Presentation hash uses only title/description normalization")
    void presentationHashNormalization() {
        List<UUID> tags = List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        QuizDto a = sample("  My TITLE  ", "Some   Description\ntext", tags);
        QuizDto b = sample("my title", "some description text", List.of());

        String p1 = calculator.calculatePresentationHash(a);
        String p2 = calculator.calculatePresentationHash(b);
        assertThat(p1).isEqualTo(p2);
    }

    @Test
    @DisplayName("Presentation hash changes when title or description changes")
    void presentationHashChangesWhenPresentationChanges() {
        List<UUID> tags = List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        String p1 = calculator.calculatePresentationHash(sample("Title", "Desc", tags));
        String p2 = calculator.calculatePresentationHash(sample("Title 2", "Desc", tags));
        assertThat(p1).isNotEqualTo(p2);
    }

    @Test
    @DisplayName("hasPresentationChanged uses stored presentationHash if present, else computes from entity")
    void hasPresentationChanged_behaviour() {
        Quiz quiz = new Quiz();
        quiz.setTitle("Title");
        quiz.setDescription("Desc");

        QuizDto same = sample("Title", "Desc", List.of());
        QuizDto diff = sample("Title 2", "Desc", List.of());

        // Without stored hash
        assertThat(calculator.hasPresentationChanged(quiz, same)).isFalse();
        assertThat(calculator.hasPresentationChanged(quiz, diff)).isTrue();

        // With stored hash
        String stored = calculator.calculatePresentationHash(same);
        quiz.setPresentationHash(stored);
        assertThat(calculator.hasPresentationChanged(quiz, same)).isFalse();
        assertThat(calculator.hasPresentationChanged(quiz, diff)).isTrue();
    }

    @Test
    @DisplayName("hasContentChanged compares stored hash if present, else computes from entity")
    void hasContentChanged_behaviour() {
        // Build entity with tags and difficulty matching the DTO
        Quiz quiz = new Quiz();
        quiz.setTitle("Title");
        quiz.setDescription("Desc");
        quiz.setDifficulty(Difficulty.MEDIUM);

        Tag t = new Tag();
        t.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        java.util.HashSet<Tag> set = new java.util.HashSet<>();
        set.add(t);
        quiz.setTags(set);

        QuizDto dtoSame = sample("Title", "Desc", List.of(t.getId()));
        QuizDto dtoDifferent = sample("Title 2", "Desc", List.of(t.getId()));

        // Without stored hash, calculator should compute from entity and detect equality/changes
        boolean changedSame = calculator.hasContentChanged(quiz, dtoSame);
        boolean changedDifferent = calculator.hasContentChanged(quiz, dtoDifferent);
        assertThat(changedSame).isFalse();
        assertThat(changedDifferent).isTrue();

        // With stored hash matching dtoSame, should use stored and return false
        String stored = calculator.calculateContentHash(dtoSame);
        quiz.setContentHash(stored);
        assertThat(calculator.hasContentChanged(quiz, dtoSame)).isFalse();
        // And different when dto differs
        assertThat(calculator.hasContentChanged(quiz, dtoDifferent)).isTrue();
    }
}


