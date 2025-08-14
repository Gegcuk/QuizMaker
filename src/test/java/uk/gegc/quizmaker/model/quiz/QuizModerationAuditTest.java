package uk.gegc.quizmaker.model.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.quiz.domain.model.ModerationAction;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizModerationAudit;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuizModerationAuditTest {

    @Test
    @DisplayName("Basic property mapping holds for QuizModerationAudit")
    void basicMapping() {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());

        User moderator = new User();

        QuizModerationAudit audit = new QuizModerationAudit();
        UUID id = UUID.randomUUID();
        audit.setId(id);
        audit.setQuiz(quiz);
        audit.setModerator(moderator);
        audit.setAction(ModerationAction.REJECT);
        audit.setReason("Insufficient quality");
        audit.setCorrelationId("corr-123");

        // createdAt is managed by JPA; here we can set to mimic persistence or only assert null before save
        assertThat(audit.getCreatedAt()).isNull();

        assertThat(audit.getId()).isEqualTo(id);
        assertThat(audit.getQuiz()).isEqualTo(quiz);
        assertThat(audit.getModerator()).isEqualTo(moderator);
        assertThat(audit.getAction()).isEqualTo(ModerationAction.REJECT);
        assertThat(audit.getReason()).isEqualTo("Insufficient quality");
        assertThat(audit.getCorrelationId()).isEqualTo("corr-123");

        // simulate that persistence layer sets createdAt
        Instant now = Instant.now();
        audit.setCreatedAt(now);
        assertThat(audit.getCreatedAt()).isEqualTo(now);
    }
}


