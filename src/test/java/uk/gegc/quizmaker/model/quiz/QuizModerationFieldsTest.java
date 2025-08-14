package uk.gegc.quizmaker.model.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.model.user.User;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QuizModerationFieldsTest {

    @Test
    @DisplayName("New moderation/hash/version fields default to null and can be set")
    void defaultsAndSetters() {
        Quiz quiz = new Quiz();
        quiz.setTitle("T");
        quiz.setDescription("D");
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setEstimatedTime(5);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setTags(Set.of());
        quiz.setQuestions(Set.of());

        // New fields initially null
        assertThat(quiz.getReviewedAt()).isNull();
        assertThat(quiz.getReviewedBy()).isNull();
        assertThat(quiz.getRejectionReason()).isNull();
        assertThat(quiz.getContentHash()).isNull();
        assertThat(quiz.getPresentationHash()).isNull();
        assertThat(quiz.getVersion()).isNull();

        // Set moderation fields
        Instant now = Instant.now();
        User reviewer = new User();
        quiz.setReviewedAt(now);
        quiz.setReviewedBy(reviewer);
        quiz.setRejectionReason("Not adequate");

        // Set hash fields
        quiz.setContentHash("abc123");
        quiz.setPresentationHash("def456");

        // Set version
        quiz.setVersion(3);

        assertThat(quiz.getReviewedAt()).isEqualTo(now);
        assertThat(quiz.getReviewedBy()).isEqualTo(reviewer);
        assertThat(quiz.getRejectionReason()).isEqualTo("Not adequate");
        assertThat(quiz.getContentHash()).isEqualTo("abc123");
        assertThat(quiz.getPresentationHash()).isEqualTo("def456");
        assertThat(quiz.getVersion()).isEqualTo(3);
    }
}


