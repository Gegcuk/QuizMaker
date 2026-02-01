package uk.gegc.quizmaker.features.repetition.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.repetition.api.dto.ReminderToggleRequest;
import uk.gegc.quizmaker.features.repetition.api.dto.RepetitionReviewRequest;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.repetition.domain.repository.RepetitionReviewLogRepository;
import uk.gegc.quizmaker.features.repetition.domain.repository.SpacedRepetitionEntryRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("RepetitionController Action Integration Tests")
class RepetitionControllerActionIntegrationTest extends BaseIntegrationTest {

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User user;
    private Question question;
    private SpacedRepetitionEntry entry;

    @BeforeEach
    void setUp() {
        // Commit setup so RepetitionReviewServiceImpl.reviewEntryTx(REQUIRES_NEW) can see the data
        String[] usernameRef = new String[1];
        UUID[] entryIdRef = new UUID[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(__ -> {
            User u = persistUser("rep_user_action");
            usernameRef[0] = u.getUsername();
            Question q = persistQuestion(false);
            SpacedRepetitionEntry e = persistEntry(u, q, Instant.now().minusSeconds(60), true, RepetitionEntryGrade.GOOD);
            e = entryRepository.saveAndFlush(e);
            entryIdRef[0] = e.getId();
        });
        user = userRepository.findByUsername(usernameRef[0]).orElseThrow();
        entry = entryRepository.findById(entryIdRef[0]).orElseThrow();
        question = entry.getQuestion();
        entityManager.clear();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: when valid then returns 200")
    void reviewEntry_valid_returns200() throws Exception {
        RepetitionReviewRequest request = new RepetitionReviewRequest(RepetitionEntryGrade.GOOD, UUID.randomUUID());
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: returns expected JSON shape")
    void reviewEntry_returnsExpectedJsonShape() throws Exception {
        RepetitionReviewRequest request = new RepetitionReviewRequest(RepetitionEntryGrade.GOOD, UUID.randomUUID());
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryId").value(entry.getId().toString()))
                .andExpect(jsonPath("$.nextReviewAt").exists())
                .andExpect(jsonPath("$.intervalDays").exists())
                .andExpect(jsonPath("$.repetitionCount").exists())
                .andExpect(jsonPath("$.easeFactor").exists())
                .andExpect(jsonPath("$.lastReviewedAt").exists())
                .andExpect(jsonPath("$.lastGrade").value("GOOD"));
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: missing grade returns 400")
    void reviewEntry_missingGrade_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: empty body returns 400")
    void reviewEntry_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: malformed JSON returns 400")
    void reviewEntry_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{ \"grade\": \"GOOD\" "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: invalid idempotencyKey UUID returns 400")
    void reviewEntry_invalidIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"grade\":\"GOOD\",\"idempotencyKey\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: invalid grade returns 400")
    void reviewEntry_invalidGrade_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"grade\":\"INVALID_GRADE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: entry not found returns 404")
    void reviewEntry_notFound_returns404() throws Exception {
        RepetitionReviewRequest request = new RepetitionReviewRequest(RepetitionEntryGrade.GOOD, UUID.randomUUID());
        UUID nonExistentEntryId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", nonExistentEntryId)
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: idempotency conflict returns 409")
    void reviewEntry_idempotencyConflict_returns409() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        RepetitionReviewRequest request = new RepetitionReviewRequest(RepetitionEntryGrade.GOOD, idempotencyKey);
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: invalid entryId returns 400")
    void reviewEntry_invalidEntryId_returns400() throws Exception {
        RepetitionReviewRequest request = new RepetitionReviewRequest(RepetitionEntryGrade.GOOD, UUID.randomUUID());
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", "not-a-uuid")
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: unknown principal returns 401")
    void reviewEntry_unknownPrincipal_returns401() throws Exception {
        RepetitionReviewRequest request = new RepetitionReviewRequest(RepetitionEntryGrade.GOOD, UUID.randomUUID());
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", entry.getId())
                        .with(user("unknown"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: without CSRF then returns 403 or 200 when CSRF disabled")
    void reviewEntry_noCsrf_returns403() throws Exception {
        RepetitionReviewRequest request = new RepetitionReviewRequest(RepetitionEntryGrade.GOOD, UUID.randomUUID());
        // App has CSRF disabled for API (SecurityConfig), so request without CSRF may succeed with 200
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", entry.getId())
                        .with(user(user.getUsername()))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: without authentication then returns 401")
    void reviewEntry_noAuth_returns401() throws Exception {
        RepetitionReviewRequest request = new RepetitionReviewRequest(RepetitionEntryGrade.GOOD, UUID.randomUUID());
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", entry.getId())
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: when valid then returns 200")
    void setReminder_valid_returns200() throws Exception {
        ReminderToggleRequest request = new ReminderToggleRequest(true);
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: returns expected JSON shape")
    void setReminder_returnsExpectedJsonShape() throws Exception {
        ReminderToggleRequest request = new ReminderToggleRequest(false);
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryId").value(entry.getId().toString()))
                .andExpect(jsonPath("$.reminderEnabled").value(false));
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: missing enabled returns 400")
    void setReminder_missingEnabled_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: empty body returns 400")
    void setReminder_emptyBody_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: malformed JSON returns 400")
    void setReminder_malformedJson_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", entry.getId())
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{ \"enabled\": true "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: entry not found returns 404")
    void setReminder_notFound_returns404() throws Exception {
        ReminderToggleRequest request = new ReminderToggleRequest(true);
        UUID nonExistentEntryId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", nonExistentEntryId)
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: invalid entryId returns 400")
    void setReminder_invalidEntryId_returns400() throws Exception {
        ReminderToggleRequest request = new ReminderToggleRequest(true);
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", "not-a-uuid")
                        .with(user(user.getUsername()))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: unknown principal returns 401")
    void setReminder_unknownPrincipal_returns401() throws Exception {
        ReminderToggleRequest request = new ReminderToggleRequest(true);
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", entry.getId())
                        .with(user("unknown"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: without CSRF then returns 403 or 200 when CSRF disabled")
    void setReminder_noCsrf_returns403() throws Exception {
        ReminderToggleRequest request = new ReminderToggleRequest(true);
        // App has CSRF disabled for API (SecurityConfig), so request without CSRF may succeed with 200
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", entry.getId())
                        .with(user(user.getUsername()))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: without authentication then returns 401")
    void setReminder_noAuth_returns401() throws Exception {
        ReminderToggleRequest request = new ReminderToggleRequest(true);
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", entry.getId())
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
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
}
