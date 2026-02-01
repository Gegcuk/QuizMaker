package uk.gegc.quizmaker.features.repetition.domain.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.repetition.domain.model.*;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@DataJpaTest
@ActiveProfiles("test-mysql")
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(
        replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE
)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create"
})
class RepetitionReviewLogRepositoryTest {

    @Autowired
    private RepetitionReviewLogRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private User user;
    private SpacedRepetitionEntry entry;
    private Question question;

    @BeforeEach
    void setUp() {
        user = persistUser("user");
        question = persistQuestion();
        entry = persistEntry(user, question);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("findByUser_IdOrderByReviewedAtDesc returns latest first")
    void historyByUser_ordersByReviewedAtDesc() {
        RepetitionReviewLog older = persistLog(user, entry, question, RepetitionEntryGrade.GOOD);
        RepetitionReviewLog newer = persistLog(user, entry, question, RepetitionEntryGrade.EASY);

        entityManager.flush();

        // Force deterministic reviewed_at ordering
        updateReviewedAt(older.getId(), Instant.parse("2024-01-01T00:00:00Z"));
        updateReviewedAt(newer.getId(), Instant.parse("2024-01-02T00:00:00Z"));

        entityManager.clear();

        Page<RepetitionReviewLog> page = repository.findByUser_IdOrderByReviewedAtDesc(
                user.getId(), PageRequest.of(0, 10)
        );

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getId()).isEqualTo(newer.getId());
        assertThat(page.getContent().get(1).getId()).isEqualTo(older.getId());
    }

    @Test
    @DisplayName("findByEntry_IdOrderByReviewedAtDesc filters by entry")
    void historyByEntry_filtersByEntry() {
        SpacedRepetitionEntry otherEntry = persistEntry(user, persistQuestion());

        persistLog(user, entry, question, RepetitionEntryGrade.GOOD);
        persistLog(user, otherEntry, otherEntry.getQuestion(), RepetitionEntryGrade.HARD);

        entityManager.flush();
        entityManager.clear();

        Page<RepetitionReviewLog> page = repository.findByEntry_IdOrderByReviewedAtDesc(
                entry.getId(), PageRequest.of(0, 10)
        );

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getEntry().getId()).isEqualTo(entry.getId());
    }

    @Test
    @DisplayName("findByUser_IdOrderByReviewedAtDesc respects pagination")
    void historyByUser_pagination() {
        persistLog(user, entry, question, RepetitionEntryGrade.GOOD);
        persistLog(user, entry, question, RepetitionEntryGrade.EASY);
        entityManager.flush();
        entityManager.clear();

        Page<RepetitionReviewLog> page = repository.findByUser_IdOrderByReviewedAtDesc(
                user.getId(), PageRequest.of(0, 1)
        );

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("findByUser_IdOrderByReviewedAtDesc returns only that user's logs")
    void historyByUser_excludesOtherUsers() {
        User otherUser = persistUser("other");
        SpacedRepetitionEntry otherEntry = persistEntry(otherUser, persistQuestion());
        persistLog(user, entry, question, RepetitionEntryGrade.GOOD);
        persistLog(otherUser, otherEntry, otherEntry.getQuestion(), RepetitionEntryGrade.EASY);
        entityManager.flush();
        entityManager.clear();

        Page<RepetitionReviewLog> page = repository.findByUser_IdOrderByReviewedAtDesc(
                user.getId(), PageRequest.of(0, 10)
        );

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getUser().getId()).isEqualTo(user.getId());
    }

    private User persistUser(String name) {
        User u = new User();
        u.setUsername(name + "_" + System.currentTimeMillis());
        u.setEmail(name + "_" + System.currentTimeMillis() + "@example.com");
        u.setHashedPassword("hashed");
        u.setActive(true);
        u.setDeleted(false);
        u.setEmailVerified(true);
        entityManager.persist(u);
        return u;
    }

    private Question persistQuestion() {
        Question q = new Question();
        q.setType(QuestionType.MCQ_SINGLE);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("Q " + UUID.randomUUID());
        q.setContent("{\"options\":[{\"id\":\"o1\",\"text\":\"A\",\"correct\":true}]}");
        q.setQuizId(new ArrayList<>());
        entityManager.persist(q);
        return q;
    }

    private SpacedRepetitionEntry persistEntry(User user, Question question) {
        SpacedRepetitionEntry e = new SpacedRepetitionEntry();
        e.setUser(user);
        e.setQuestion(question);
        e.setNextReviewAt(Instant.now().minusSeconds(10));
        e.setIntervalDays(1);
        e.setRepetitionCount(0);
        e.setEaseFactor(2.5);
        e.setReminderEnabled(true);
        entityManager.persist(e);
        return e;
    }

    private RepetitionReviewLog persistLog(User user, SpacedRepetitionEntry entry, Question question, RepetitionEntryGrade grade) {
        RepetitionReviewLog log = new RepetitionReviewLog();
        log.setUser(user);
        log.setEntry(entry);
        log.setContentType(RepetitionContentType.QUESTION);
        log.setContentId(question.getId());
        log.setGrade(grade);
        log.setIntervalDays(1);
        log.setEaseFactor(2.5);
        log.setRepetitionCount(1);
        log.setSourceType(RepetitionReviewSourceType.MANUAL_REVIEW);
        log.setSourceId(UUID.randomUUID());
        entityManager.persist(log);
        return log;
    }

    private void updateReviewedAt(UUID id, Instant instant) {
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE repetition_review_log SET reviewed_at = ? WHERE repetition_review_id = ?")
                .setParameter(1, Timestamp.from(instant))
                .setParameter(2, id)
                .executeUpdate();
    }
}
