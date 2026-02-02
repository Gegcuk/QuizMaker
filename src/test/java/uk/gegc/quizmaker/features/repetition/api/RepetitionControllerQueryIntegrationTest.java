package uk.gegc.quizmaker.features.repetition.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionContentType;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionReviewLog;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionReviewSourceType;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.repetition.domain.repository.RepetitionReviewLogRepository;
import uk.gegc.quizmaker.features.repetition.domain.repository.SpacedRepetitionEntryRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("RepetitionController Query Integration Tests")
class RepetitionControllerQueryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SpacedRepetitionEntryRepository entryRepository;

    @Autowired
    private RepetitionReviewLogRepository logRepository;

    private User user;
    private User otherUser;
    private Question activeQuestion;
    private Question futureQuestion;
    private Question reminderDisabledQuestion;
    private Question deletedQuestion;
    private SpacedRepetitionEntry dueEntry;
    private SpacedRepetitionEntry futureEntry;
    private SpacedRepetitionEntry reminderDisabledEntry;
    private RepetitionReviewLog historyLog1;
    private RepetitionReviewLog historyLog2;

    @BeforeEach
    void setUp() {
        user = persistUser("rep_user");
        otherUser = persistUser("rep_other");

        activeQuestion = persistQuestion(false);
        futureQuestion = persistQuestion(false);
        reminderDisabledQuestion = persistQuestion(false);
        deletedQuestion = persistQuestion(true);

        dueEntry = persistEntry(user, activeQuestion, Instant.now().minusSeconds(120), true, RepetitionEntryGrade.GOOD);
        futureEntry = persistEntry(user, futureQuestion, Instant.now().plusSeconds(3600), true, RepetitionEntryGrade.GOOD);
        reminderDisabledEntry = persistEntry(user, reminderDisabledQuestion, Instant.now().minusSeconds(60), false, RepetitionEntryGrade.AGAIN);

        historyLog1 = persistLog(user, dueEntry, Instant.now().minusSeconds(300));
        historyLog2 = persistLog(user, dueEntry, Instant.now().minusSeconds(60));

        // Entry for other user to validate ownership filtering in service layer
        persistEntry(otherUser, deletedQuestion, Instant.now().minusSeconds(120), true, RepetitionEntryGrade.GOOD);

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: when authorized then returns 200")
    void getDue_authorized_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/due").with(user(user.getUsername())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: returns expected JSON shape")
    void getDue_returnsExpectedJsonShape() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/due").with(user(user.getUsername())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].entryId").value(dueEntry.getId().toString()))
                .andExpect(jsonPath("$.content[0].questionId").value(activeQuestion.getId().toString()))
                .andExpect(jsonPath("$.content[0].nextReviewAt").exists())
                .andExpect(jsonPath("$.content[0].lastReviewedAt").exists())
                .andExpect(jsonPath("$.content[0].lastGrade").value("GOOD"))
                .andExpect(jsonPath("$.content[0].intervalDays").exists())
                .andExpect(jsonPath("$.content[0].repetitionCount").exists())
                .andExpect(jsonPath("$.content[0].easeFactor").exists())
                .andExpect(jsonPath("$.content[0].reminderEnabled").value(true))
                .andExpect(jsonPath("$.content[0].priorityScore").exists())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").exists());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: filters reminderEnabled=false and future entries")
    void getDue_filtersNonDueAndReminderDisabled() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/due").with(user(user.getUsername())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].entryId").value(dueEntry.getId().toString()));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: without authentication then returns 401")
    void getDue_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/due"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: unknown principal returns 401")
    void getDue_unknownPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/due").with(user("unknown")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/priority: when authorized then returns 200")
    void getPriority_authorized_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/priority").with(user(user.getUsername())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/priority: returns expected JSON shape")
    void getPriority_returnsExpectedJsonShape() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/priority").with(user(user.getUsername())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].entryId").exists())
                .andExpect(jsonPath("$.content[0].questionId").exists())
                .andExpect(jsonPath("$.content[0].priorityScore").exists())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/priority: excludes reminderEnabled=false but includes future entries")
    void getPriority_filtersReminderDisabledButIncludesFuture() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/priority").with(user(user.getUsername())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].entryId").value(dueEntry.getId().toString()))
                .andExpect(jsonPath("$.content[1].entryId").value(futureEntry.getId().toString()));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/priority: without authentication then returns 401")
    void getPriority_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/priority"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/priority: unknown principal returns 401")
    void getPriority_unknownPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/priority").with(user("unknown")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/history: when authorized then returns 200")
    void getHistory_authorized_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/history").with(user(user.getUsername())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/history: returns expected JSON shape and ordering")
    void getHistory_returnsExpectedJsonShapeAndOrder() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/history").with(user(user.getUsername())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].reviewId").value(historyLog2.getId().toString()))
                .andExpect(jsonPath("$.content[0].entryId").value(dueEntry.getId().toString()))
                .andExpect(jsonPath("$.content[0].contentType").value("QUESTION"))
                .andExpect(jsonPath("$.content[0].grade").value("GOOD"))
                .andExpect(jsonPath("$.content[0].reviewedAt").exists())
                .andExpect(jsonPath("$.content[0].sourceType").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.content[1].reviewId").value(historyLog1.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/history: without authentication then returns 401")
    void getHistory_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/history: unknown principal returns 401")
    void getHistory_unknownPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/history").with(user("unknown")))
                .andExpect(status().isUnauthorized());
    }

    private User persistUser(String baseName) {
        User u = new User();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        u.setUsername(baseName + "_" + suffix);
        u.setEmail(baseName + "_" + suffix + "@example.com");
        u.setHashedPassword("hashed");
        u.setActive(true);
        u.setDeleted(false);
        u.setEmailVerified(true);
        return userRepository.save(u);
    }

    private Question persistQuestion(boolean deleted) {
        Question q = new Question();
        q.setType(QuestionType.MCQ_SINGLE);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("Q " + UUID.randomUUID());
        q.setContent("{\"options\":[{\"id\":\"o1\",\"text\":\"A\",\"correct\":true}]}");
        q.setQuizId(new ArrayList<>());
        q.setIsDeleted(deleted);
        if (deleted) {
            q.setDeletedAt(Instant.now());
        }
        entityManager.persist(q);
        return q;
    }

    private SpacedRepetitionEntry persistEntry(
            User owner,
            Question question,
            Instant nextReviewAt,
            boolean reminderEnabled,
            RepetitionEntryGrade lastGrade
    ) {
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(owner);
        entry.setQuestion(question);
        entry.setNextReviewAt(nextReviewAt);
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        entry.setEaseFactor(2.5);
        entry.setLastReviewedAt(Instant.now().minusSeconds(300));
        entry.setLastGrade(lastGrade);
        entry.setReminderEnabled(reminderEnabled);
        entityManager.persist(entry);
        return entry;
    }

    private RepetitionReviewLog persistLog(User owner, SpacedRepetitionEntry entry, Instant reviewedAt) {
        RepetitionReviewLog log = new RepetitionReviewLog();
        log.setUser(owner);
        log.setEntry(entry);
        log.setContentType(RepetitionContentType.QUESTION);
        log.setContentId(entry.getQuestion().getId());
        log.setGrade(RepetitionEntryGrade.GOOD);
        log.setReviewedAt(reviewedAt);
        log.setIntervalDays(1);
        log.setEaseFactor(2.5);
        log.setRepetitionCount(1);
        log.setSourceType(RepetitionReviewSourceType.MANUAL_REVIEW);
        log.setSourceId(UUID.randomUUID());
        entityManager.persist(log);
        return log;
    }
}
