package uk.gegc.quizmaker.features.attempt.compatibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.attempt.application.LegacyCompatibilityService;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionRequest;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionDto;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility.*;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Existing HTTP writers/readers remain authoritative. No target adapter double participates. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(LegacyCompatibilityHttpIntegrationTest.Config.class)
class LegacyCompatibilityHttpIntegrationTest extends BaseIntegrationTest {
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired LegacyCompatibilityService inventory;
    @Autowired AttemptService attempts;
    @Autowired LockGate lockGate;

    @ParameterizedTest @EnumSource(QuestionType.class)
    void allNineFamiliesKeepAcceptedFeedbackCompletedReviewAndExplicitMutableHistory(QuestionType type) throws Exception {
        Fixture fixture = fixture(type);
        UUID attempt = start(fixture, "ALL_AT_ONCE");
        String path = "/api/v1/attempts/" + attempt;
        mockMvc.perform(get(path + "/review").with(user(fixture.username())))
                .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        var request = json.createObjectNode().put("questionId", fixture.question().toString())
                .put("includeCorrectness", true).put("includeCorrectAnswer", true).put("includeExplanation", true);
        request.set("response", QuestionContracts.example(type, QuestionContracts.View.RESPONSE));
        mockMvc.perform(post(path + "/answers").with(user(fixture.username())).contentType("application/json")
                        .content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.questionId").value(fixture.question().toString()))
                .andExpect(jsonPath("$.isCorrect").value(true)).andExpect(jsonPath("$.score").value(1.0))
                .andExpect(jsonPath("$.correctAnswer").exists()).andExpect(jsonPath("$.explanation").value("Synthetic explanation"));
        mockMvc.perform(post(path + "/answers").with(user(fixture.username())).contentType("application/json")
                        .content(json.writeValueAsBytes(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(post(path + "/complete").with(user(fixture.username())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalScore").value(1.0))
                .andExpect(jsonPath("$.correctCount").value(1));
        mockMvc.perform(get(path + "/review").with(user(fixture.username())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answers[0].questionText").value("Original synthetic question"));
        // Characterize the current lower guarantee; do not silently replace it with today's target snapshot.
        new TransactionTemplate(transactions).executeWithoutResult(status -> jdbcTemplate.update(
                "UPDATE questions SET question='Edited synthetic question' WHERE id=UUID_TO_BIN(?)", fixture.question().toString()));
        mockMvc.perform(get(path + "/review").with(user(fixture.username())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answers[0].questionText").value("Edited synthetic question"));
        var report = inventory.inventory(new Limits(20, 1000, 30));
        assertThat(report.complete()).isTrue();
        assertThat(report.families().stream().filter(f -> f.family() == Family.ATTEMPT).findFirst().orElseThrow()
                .findings().getOrDefault(Finding.LEGACY_HISTORY, 0L)).isPositive();
        assertThat(json.writeValueAsString(report)).doesNotContain("Edited synthetic question", "Synthetic explanation", attempt.toString());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers WHERE attempt_id=UUID_TO_BIN(?)", Integer.class, attempt.toString())).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(AttemptMode.class)
    void disabledTimerModeDefaultAndPauseResumeKeepExistingEnvelopes(AttemptMode mode) throws Exception {
        Fixture fixture = fixture(QuestionType.TRUE_FALSE);
        UUID attempt = start(fixture, mode.name());
        String path = "/api/v1/attempts/" + attempt;
        mockMvc.perform(get(path + "/current-question").with(user(fixture.username())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.questionNumber").value(1));
        mockMvc.perform(post(path + "/pause").with(user(fixture.username())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"));
        mockMvc.perform(post(path + "/resume").with(user(fixture.username())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        var raw = mockMvc.perform(post("/api/v1/attempts/quizzes/{quiz}", fixture.quiz()).with(user(fixture.username())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(raw).path("mode").asText()).isEqualTo("ALL_AT_ONCE");
    }

    @Test void unauthenticatedWrongOwnerAndPrivateQuizDenialsDoNotGrantAuthorityOrWriteAnswers() throws Exception {
        Fixture fixture = fixture(QuestionType.TRUE_FALSE); Fixture other = fixture(QuestionType.TRUE_FALSE);
        UUID attempt = start(fixture, "ALL_AT_ONCE");
        String path = "/api/v1/attempts/" + attempt;
        var answer = new AnswerSubmissionRequest(fixture.question(), json.createObjectNode().put("answer", true), true, true, true);
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).with(user(other.username()))).andExpect(status().isForbidden());
        mockMvc.perform(post(path + "/complete").with(user(other.username()))).andExpect(status().isForbidden());
        mockMvc.perform(post(path + "/answers").contentType("application/json").content(json.writeValueAsBytes(answer)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(path + "/answers").with(user(other.username()))
                        .contentType("application/json").content(json.writeValueAsBytes(answer)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path + "/answers/batch").with(user(other.username()))
                        .contentType("application/json").content(json.writeValueAsBytes(java.util.Map.of("answers", List.of(answer)))))
                .andExpect(status().isForbidden());
        new TransactionTemplate(transactions).executeWithoutResult(status -> jdbcTemplate.update(
                "UPDATE quizzes SET visibility='PRIVATE' WHERE quiz_id=UUID_TO_BIN(?)", fixture.quiz().toString()));
        mockMvc.perform(post("/api/v1/attempts/quizzes/{quiz}", fixture.quiz()).with(user(other.username())))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attempts WHERE quiz_id=UUID_TO_BIN(?)", Integer.class, fixture.quiz().toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers WHERE attempt_id=UUID_TO_BIN(?)", Integer.class, attempt.toString())).isZero();
        mockMvc.perform(post(path + "/answers").with(user(fixture.username()))
                        .contentType("application/json").content(json.writeValueAsBytes(answer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(1.0));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers WHERE attempt_id=UUID_TO_BIN(?)", Integer.class, attempt.toString())).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(AttemptMode.class)
    void competingRetainedWritersKeepOneImmutableAnswerAndOneScoreContribution(AttemptMode mode) throws Exception {
        // Both contenders reach their real SELECT FOR UPDATE in independent Spring/MySQL transactions.
        for (boolean differentPayload : List.of(false, true)) {
            Fixture f = fixture(QuestionType.TRUE_FALSE);
            if (mode == AttemptMode.TIMED) new TransactionTemplate(transactions).executeWithoutResult(status ->
                    jdbcTemplate.update("UPDATE quizzes SET is_timer_enabled=1,timer_duration_min=60 WHERE quiz_id=UUID_TO_BIN(?)", f.quiz().toString()));
            UUID id = attempts.startAttempt(f.username(), f.quiz(), mode).attemptId();
            var first = new AnswerSubmissionRequest(f.question(), json.createObjectNode().put("answer", true), false, false, false);
            var second = new AnswerSubmissionRequest(f.question(), json.createObjectNode().put("answer", !differentPayload), false, false, false);
            var executor = Executors.newFixedThreadPool(2);
            lockGate.barrier = new CyclicBarrier(2);
            List<Object> outcomes;
            try {
                var a = executor.submit(() -> submit(f.username(), id, first));
                var b = executor.submit(() -> submit(f.username(), id, second));
                outcomes = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            } finally {
                lockGate.barrier = null; executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(outcomes.stream().filter(AnswerSubmissionDto.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(IllegalStateException.class::isInstance)).hasSize(1);
            var winner = (AnswerSubmissionDto) outcomes.stream().filter(AnswerSubmissionDto.class::isInstance).findFirst().orElseThrow();
            var stored = jdbcTemplate.queryForMap("SELECT BIN_TO_UUID(id) AS id,response,score FROM answers WHERE attempt_id=UUID_TO_BIN(?)", id.toString());
            assertThat(stored.get("id")).isEqualTo(winner.answerId().toString());
            for (var request : List.of(first, second))
                assertThatThrownBy(() -> attempts.submitAnswer(f.username(), id, request)).isInstanceOf(IllegalStateException.class);
            assertThat(attempts.completeAttempt(f.username(), id).totalScore()).isEqualTo(winner.score());
            assertThat(jdbcTemplate.queryForMap("SELECT BIN_TO_UUID(id) AS id,response,score FROM answers WHERE attempt_id=UUID_TO_BIN(?)", id.toString()))
                    .isEqualTo(stored);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void duplicateQuestionWithinBatchUsesExisting422AndRollsBackEarlierInsert(boolean conflicting) throws Exception {
        Fixture f = fixture(QuestionType.TRUE_FALSE);
        UUID id = start(f, "ALL_AT_ONCE");
        var first = new AnswerSubmissionRequest(f.question(), json.createObjectNode().put("answer", true), true, true, true);
        var second = new AnswerSubmissionRequest(f.question(), json.createObjectNode().put("answer", !conflicting), true, true, true);
        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", id).with(user(f.username()))
                        .contentType("application/json").content(json.writeValueAsBytes(java.util.Map.of("answers", List.of(first, second)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers WHERE attempt_id=UUID_TO_BIN(?)",
                Integer.class, id.toString())).isZero();
        assertThat(attempts.submitAnswer(f.username(), id, first).isCorrect()).isTrue();
        assertThat(attempts.completeAttempt(f.username(), id).totalScore()).isEqualTo(1.0);
    }

    private Object submit(String username, UUID id, AnswerSubmissionRequest request) {
        try { return attempts.submitAnswer(username, id, request); }
        catch (IllegalStateException rejection) { return rejection; }
    }

    @ParameterizedTest @EnumSource(AttemptMode.class)
    void concurrentHttpSubmissionsReturnOne200AndOne422WithAnImmutableWinner(AttemptMode mode) throws Exception {
        for (boolean conflicting : List.of(false, true)) {
            Fixture f = fixture(QuestionType.TRUE_FALSE);
            if (mode == AttemptMode.TIMED) new TransactionTemplate(transactions).executeWithoutResult(status ->
                    jdbcTemplate.update("UPDATE quizzes SET is_timer_enabled=1,timer_duration_min=60 WHERE quiz_id=UUID_TO_BIN(?)", f.quiz().toString()));
            UUID id = attempts.startAttempt(f.username(), f.quiz(), mode).attemptId();
            var first = new AnswerSubmissionRequest(f.question(), json.createObjectNode().put("answer", true), true, true, true);
            var second = new AnswerSubmissionRequest(f.question(), json.createObjectNode().put("answer", !conflicting), true, true, true);
            String path = "/api/v1/attempts/" + id + "/answers";
            var executor = Executors.newFixedThreadPool(2);
            lockGate.barrier = new CyclicBarrier(2);
            List<org.springframework.test.web.servlet.MvcResult> outcomes;
            try {
                var a = executor.submit(() -> mockMvc.perform(post(path).with(user(f.username()))
                        .contentType("application/json").content(json.writeValueAsBytes(first))).andReturn());
                var b = executor.submit(() -> mockMvc.perform(post(path).with(user(f.username()))
                        .contentType("application/json").content(json.writeValueAsBytes(second))).andReturn());
                outcomes = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            } finally {
                lockGate.barrier = null; executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(outcomes).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(200, 422);
            var winner = json.readTree(outcomes.stream().filter(result -> result.getResponse().getStatus() == 200)
                    .findFirst().orElseThrow().getResponse().getContentAsString());
            var loser = outcomes.stream().filter(result -> result.getResponse().getStatus() == 422)
                    .findFirst().orElseThrow().getResponse();
            assertThat(loser.getContentType()).startsWith("application/problem+json");
            var stored = jdbcTemplate.queryForMap("SELECT BIN_TO_UUID(id) AS id,response,score FROM answers WHERE attempt_id=UUID_TO_BIN(?)", id.toString());
            assertThat(stored.get("id")).isEqualTo(winner.path("answerId").asText());
            for (var request : List.of(first, second)) mockMvc.perform(post(path).with(user(f.username()))
                            .contentType("application/json").content(json.writeValueAsBytes(request)))
                    .andExpect(status().isUnprocessableEntity());
            assertThat(attempts.completeAttempt(f.username(), id).totalScore()).isEqualTo(winner.path("score").asDouble());
            assertThat(jdbcTemplate.queryForMap("SELECT BIN_TO_UUID(id) AS id,response,score FROM answers WHERE attempt_id=UUID_TO_BIN(?)", id.toString()))
                    .isEqualTo(stored);
        }
    }

    @Test
    void hibernateSchemaEnforcesTheSameDatabaseInvariantAsFlyway() {
        Fixture f = fixture(QuestionType.TRUE_FALSE);
        UUID id = attempts.startAttempt(f.username(), f.quiz(), AttemptMode.ALL_AT_ONCE).attemptId();
        var request = new AnswerSubmissionRequest(f.question(), json.createObjectNode().put("answer", true), false, false, false);
        var accepted = attempts.submitAnswer(f.username(), id, request);
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE()
                AND table_name='answers' AND index_name='uq_answers_attempt_question' AND non_unique=0 ORDER BY seq_in_index
                """, String.class)).containsExactly("attempt_id", "question_id");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO answers(id,attempt_id,question_id,response,is_correct,score,answered_at)
                SELECT UUID_TO_BIN(?),attempt_id,question_id,response,is_correct,score,answered_at FROM answers WHERE id=UUID_TO_BIN(?)
                """, UUID.randomUUID().toString(), accepted.answerId().toString()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("uq_answers_attempt_question");
        assertThat(attempts.completeAttempt(f.username(), id).totalScore()).isEqualTo(1.0);
    }

    static class LockGate implements org.hibernate.resource.jdbc.spi.StatementInspector {
        volatile CyclicBarrier barrier;
        @Override public String inspect(String sql) {
            var gate = barrier;
            if (gate != null && sql.contains("from attempts") && sql.contains("for update")) {
                try { gate.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError("Concurrent legacy writers did not meet at the row lock", failure); }
            }
            return sql;
        }
    }

    @TestConfiguration static class Config {
        @Bean LockGate compatibilityLockGate() { return new LockGate(); }
        @Bean HibernatePropertiesCustomizer compatibilitySqlInspector(LockGate gate) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", gate);
        }
    }

    private UUID start(Fixture fixture, String mode) throws Exception {
        String raw = mockMvc.perform(post("/api/v1/attempts/quizzes/{quiz}", fixture.quiz()).with(user(fixture.username()))
                        .contentType("application/json").content(json.writeValueAsBytes(json.createObjectNode().put("mode", mode))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mode").value(mode))
                .andExpect(jsonPath("$.quizId").value(fixture.quiz().toString()))
                .andExpect(jsonPath("$.totalQuestions").value(1)).andExpect(jsonPath("$.timeLimitMinutes").isEmpty())
                .andExpect(jsonPath("$.startedAt").exists()).andReturn().getResponse().getContentAsString();
        JsonNode response = json.readTree(raw);
        assertThat(response.size()).isEqualTo(6);
        return UUID.fromString(response.path("attemptId").asText());
    }

    private Fixture fixture(QuestionType type) {
        return new TransactionTemplate(transactions).execute(status -> {
            var owner = new User(); owner.setUsername("compatibility-" + UUID.randomUUID());
            owner.setEmail(owner.getUsername() + "@example.invalid"); owner.setHashedPassword("fixture-only");
            owner.setActive(true); entityManager.persist(owner);
            var category = new Category(); category.setName("compatibility-" + UUID.randomUUID()); entityManager.persist(category);
            var quiz = new Quiz(); quiz.setCreator(owner); quiz.setCategory(category); quiz.setTitle("Compatibility fixture");
            quiz.setStatus(QuizStatus.PUBLISHED); quiz.setVisibility(Visibility.PUBLIC); quiz.setDifficulty(Difficulty.EASY);
            quiz.setEstimatedTime(1); quiz.setIsRepetitionEnabled(false); quiz.setIsTimerEnabled(false);
            quiz.setTimerDuration(1); quiz.setIsDeleted(false); entityManager.persist(quiz);
            var question = new Question(); question.setType(type); question.setDifficulty(Difficulty.EASY);
            question.setQuestionText("Original synthetic question"); question.setExplanation("Synthetic explanation");
            question.setContent(QuestionContracts.example(type, QuestionContracts.View.AUTHOR).toString());
            question.getQuizId().add(quiz); entityManager.persist(question); entityManager.flush();
            return new Fixture(owner.getUsername(), quiz.getId(), question.getId());
        });
    }
    private record Fixture(String username, UUID quiz, UUID question) { }
}
