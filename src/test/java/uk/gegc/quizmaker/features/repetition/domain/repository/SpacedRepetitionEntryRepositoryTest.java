package uk.gegc.quizmaker.features.repetition.domain.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create"
})
public class SpacedRepetitionEntryRepositoryTest {

    @Autowired
    private SpacedRepetitionEntryRepository spacedRepetitionEntryRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User userA;
    private User userB;
    private Question activeQuestion;
    private Question activeQuestion2;
    private Question deletedQuestion;
    private SpacedRepetitionEntry activeEntry;

    @BeforeEach
    void setUp(){
        userA = persistUser("userA");
        userB = persistUser("UserB");

        activeQuestion = persistQuestion(false);
        activeQuestion2 = persistQuestion(false);
        deletedQuestion = persistQuestion(true);

        activeEntry = persistEntry(userA, activeQuestion, Instant.now().minusSeconds(60), true);
        persistEntry(userA, deletedQuestion, Instant.now().minusSeconds(60), true);
        persistEntry(userA, activeQuestion2, Instant.now().minusSeconds(60), true);

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("findDueEntries excludes soft-deleted questions")
    void findDueEntries_excludesSoftDeletedQuestions(){
        Page<SpacedRepetitionEntry> page = spacedRepetitionEntryRepository.findDueEntries(
                userA.getId(),
                Instant.now(),
                PageRequest.of(0,10)
        );

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getId()).isEqualTo(activeEntry.getId());
    }

    @Test
    @DisplayName("findByUser_Id enforces ownership")
    void findByUserIdEnforcesOwnership(){
        assertThat(spacedRepetitionEntryRepository.findByIdAndUser_Id(activeEntry.getId(), userA.getId())).isPresent();
        assertThat(spacedRepetitionEntryRepository.findByIdAndUser_Id(activeEntry.getId(), userB.getId())).isEmpty();
    }

    @Test
    @DisplayName("dueQuery excludes remainder enabled = false")
    void dueQueryExcludesRemainderEnabledFalse(){
        Page<SpacedRepetitionEntry> page = spacedRepetitionEntryRepository.findDueEntries(
                userA.getId(),
                Instant.now(),
                PageRequest.of(0, 10)
        );

        assertThat(page.getContent()).hasSize(2);
    }


    private SpacedRepetitionEntry persistEntry(User user, Question question, Instant nextReviewAt, Boolean remainder) {
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(user);
        entry.setQuestion(question);
        entry.setNextReviewAt(nextReviewAt);
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        entry.setEaseFactor(2.5);
        entry.setReminderEnabled(remainder);
        entityManager.persist(entry);
        return entry;
    }

    private Question persistQuestion(boolean deleted) {
        Question question = new Question();
        question.setType(QuestionType.MCQ_SINGLE);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("Q " + UUID.randomUUID());
        question.setContent("{\"options\":[{\"id\":\"o1\",\"text\":\"A\",\"correct\":true}]}");
        question.setQuizId(new ArrayList<>());
        question.setIsDeleted(deleted);
        if(deleted){
            question.setDeletedAt(Instant.now());
        }
        entityManager.persist(question);
        return question;
    }

    private User persistUser(String name) {
        User user = new User();
        user.setUsername(name + "_" + System.currentTimeMillis());
        user.setEmail(name + "_" + System.currentTimeMillis() + "@example.com");
        user.setHashedPassword("hashed");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        entityManager.persist(user);
        return user;
    }
}
