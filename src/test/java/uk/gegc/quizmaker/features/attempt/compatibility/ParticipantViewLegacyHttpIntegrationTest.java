package uk.gegc.quizmaker.features.attempt.compatibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.user.domain.model.User;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real legacy writers and v1 HTTP with v2 enabled; generated Flyway catalog includes both generations. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "quizmaker.attempts.participant-view-enabled=true", "spring.jpa.properties.hibernate.generate_statistics=true"})
class ParticipantViewLegacyHttpIntegrationTest extends BaseIntegrationTest {
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AttemptService attempts;
    @Autowired ParticipantAttemptViewService views;
    @Autowired AttemptSubjectService subjects;
    @Autowired AttemptStartService starts;
    @Autowired uk.gegc.quizmaker.features.quiz.application.QuizRevisionPublicationService publications;
    @Autowired uk.gegc.quizmaker.features.question.application.PublishedQuestionContractService bindings;

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"details", "review", "answer-key"})
    void wrongVersionReadMarksTheJoinedTransactionForRollback(String operation) {
        var f = fixture(QuestionType.TRUE_FALSE, 1);
        var id = attempts.startAttempt(f.username(), f.quiz(), AttemptMode.ALL_AT_ONCE).attemptId();
        var principal = org.springframework.security.core.userdetails.User.withUsername(f.username())
                .password("test-only").authorities(List.of()).build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, List.of()));
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                assertThat(jdbcTemplate.queryForObject("SELECT title FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)",
                        String.class, f.quiz().toString())).isEqualTo("Compatibility fixture");
                jdbcTemplate.update("UPDATE quizzes SET title='Must roll back' WHERE quiz_id=UUID_TO_BIN(?)", f.quiz().toString());
                assertThatThrownBy(() -> {
                    switch (operation) {
                        case "details" -> views.read(id);
                        case "review" -> views.review(id, true, true, true);
                        case "answer-key" -> views.review(id, false, true, true);
                        default -> throw new AssertionError(operation);
                    }
                }).isInstanceOfSatisfying(uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.Reason.NOT_FOUND));
                assertThat(status.isRollbackOnly()).isTrue();
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)",
                String.class, f.quiz().toString())).isEqualTo("Compatibility fixture");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attempt_lifecycle_state WHERE attempt_id=UUID_TO_BIN(?)",
                Integer.class, id.toString())).isZero();
    }

    @Test void realCanonicalStartIsReadableOnlyThroughV2AndCannotAcquireLegacyAnswers() throws Exception {
        var f = fixture(QuestionType.TRUE_FALSE, 1, true);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String role = "api-version-" + UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            jdbcTemplate.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id "
                    + "FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name IN ('QUIZ_UPDATE','ATTEMPT_READ','ATTEMPT_CREATE')", role);
            jdbcTemplate.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?",
                    f.userId().toString(), role);
        });
        var principal = org.springframework.security.core.userdetails.User.withUsername(f.username())
                .password("test-only").authorities(List.of()).build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, List.of()));
        UUID id;
        try {
            var revision = publications.publish(new uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.Command(
                    1, f.quiz(), UUID.randomUUID(), 0, f.questions())).publication().revision().id();
            bindings.bind(f.quiz(), revision);
            var content = new uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.ContentPolicy(revision, 1,
                    uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.FormMode.PRECOMPUTED,
                    uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.SectionMode.IMPLICIT_FLAT,
                    uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.CaseMode.NOT_CONFIGURED);
            id = starts.start(new AttemptStartService.Command(1, f.quiz(), content, 1,
                    uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.Key.from("versioned-start"),
                    uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset.REFERENCE,
                    uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeRequest.LaunchContext.ORDINARY,
                    "ALL_AT_ONCE", uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrder.AUTHORED)).attemptId();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        mockMvc.perform(get("/api/v2/attempts/{id}", id).with(user(f.username())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.representation").value("POLICY_DRIVEN"))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(header().string("Cache-Control", "no-store"));
        for (String suffix : List.of("", "/review", "/answer-key"))
            mockMvc.perform(get("/api/v1/attempts/{id}" + suffix, id).with(user(f.username())))
                    .andExpect(status().isNotFound());
        var answer = new AnswerSubmissionRequest(f.questions().get(0),
                QuestionContracts.example(QuestionType.TRUE_FALSE, QuestionContracts.View.RESPONSE), true, true, true);
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", id).with(user(f.username()))
                        .contentType("application/json").content(json.writeValueAsString(answer)))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attempts WHERE id=UUID_TO_BIN(?)", Integer.class, id.toString())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers WHERE attempt_id=UUID_TO_BIN(?)", Integer.class, id.toString())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attempt_lifecycle_state WHERE attempt_id=UUID_TO_BIN(?)", Integer.class, id.toString())).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(QuestionType.class)
    void realLegacyAnswersAndCompletedDetailsKeepTheirOriginalEnvelopeForEveryFamily(QuestionType type) throws Exception {
        var f = fixture(type, 1); var id = attempts.startAttempt(f.username(), f.quiz(), AttemptMode.ALL_AT_ONCE).attemptId();
        assertDetails(f, id, 0);
        attempts.submitAnswer(f.username(), id, new AnswerSubmissionRequest(f.questions().get(0),
                QuestionContracts.example(type, QuestionContracts.View.RESPONSE), true, true, true));
        assertDetails(f, id, 1); attempts.completeAttempt(f.username(), id); assertDetails(f, id, 1);
    }

    @ParameterizedTest @EnumSource(AttemptMode.class)
    void supportedLegacyModesAndPauseResumeStillReadTheExistingState(AttemptMode mode) throws Exception {
        var f = fixture(QuestionType.TRUE_FALSE, 1); var id = attempts.startAttempt(f.username(), f.quiz(), mode).attemptId();
        assertDetails(f, id, 0); attempts.pauseAttempt(f.username(), id); assertDetails(f, id, 0);
        attempts.resumeAttempt(f.username(), id); assertDetails(f, id, 0);
    }

    @Test void enabledV2RejectsLegacyIdsWhileV1KeepsItsRealReviewAndAnswerKey() throws Exception {
        var f = fixture(QuestionType.TRUE_FALSE, 1);
        var id = attempts.startAttempt(f.username(), f.quiz(), AttemptMode.ALL_AT_ONCE).attemptId();
        attempts.submitAnswer(f.username(), id, new AnswerSubmissionRequest(f.questions().get(0),
                QuestionContracts.example(QuestionType.TRUE_FALSE, QuestionContracts.View.RESPONSE), true, true, true));
        attempts.completeAttempt(f.username(), id);
        for (String suffix : List.of("", "/review", "/answer-key")) {
            var expected = json.valueToTree(switch (suffix) {
                case "/review" -> attempts.getAttemptReview(f.username(), id, false, false, false);
                case "/answer-key" -> attempts.getAttemptAnswerKey(f.username(), id);
                default -> attempts.getAttemptDetail(f.username(), id);
            });
            var body = mockMvc.perform(get("/api/v1/attempts/{id}" + suffix, id).with(user(f.username()))
                            .param("includeUserAnswers", "false").param("includeCorrectAnswers", "false")
                            .param("includeQuestionContext", "false"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(body)).isEqualTo(expected);
            mockMvc.perform(get("/api/v2/attempts/{id}" + suffix, id).with(user(f.username())))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.answers").doesNotExist())
                    .andExpect(jsonPath("$.items").doesNotExist());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers WHERE attempt_id=UUID_TO_BIN(?)",
                Integer.class, id.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attempt_participant_subject WHERE attempt_id=UUID_TO_BIN(?)",
                Integer.class, id.toString())).isZero();
    }

    @Test void foreignMissingAndAmbiguousRootsDoNotFallBackToLegacy() throws Exception {
        var f = fixture(QuestionType.TRUE_FALSE, 1); var other = fixture(QuestionType.TRUE_FALSE, 1);
        var id = attempts.startAttempt(f.username(), f.quiz(), AttemptMode.ALL_AT_ONCE).attemptId(); assertDetails(f, id, 0);
        mockMvc.perform(get("/api/v1/attempts/{id}", id)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/attempts/{id}", id).with(user(other.username())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/attempts/{id}", UUID.randomUUID()).with(user(other.username())))
                .andExpect(status().isNotFound());
        for (var candidate : List.of(id, UUID.randomUUID()))
            mockMvc.perform(get("/api/v2/attempts/{id}", candidate).with(user(other.username())))
                    .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        // Partial target evidence is deliberately ambiguous, never permission to reinterpret as legacy.
        subjects.bindOrReplay(f.userId(), uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject.authenticated(id, f.userId()));
        mockMvc.perform(get("/api/v2/attempts/{id}", id).with(user(f.username())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.answers").doesNotExist());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers WHERE attempt_id=UUID_TO_BIN(?)", Integer.class, id.toString())).isZero();
    }

    @Test void legacyMappingIsBoundedForMultipleParentsWithOneAndTwentyAnswers() throws Exception {
        var statistics = entityManager.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        var totals = new ArrayList<Long>();
        for (int size : List.of(1, 20)) {
            var fixtures = new ArrayList<Fixture>(); var ids = new ArrayList<UUID>();
            for (int parent = 0; parent < 3; parent++) {
                var f = fixture(QuestionType.TRUE_FALSE, size); fixtures.add(f);
                var id = attempts.startAttempt(f.username(), f.quiz(), AttemptMode.ALL_AT_ONCE).attemptId(); ids.add(id);
                for (var question : f.questions()) attempts.submitAnswer(f.username(), id, new AnswerSubmissionRequest(question,
                        QuestionContracts.example(QuestionType.TRUE_FALSE, QuestionContracts.View.RESPONSE), true, true, true));
            }
            statistics.clear();
            for (int i = 0; i < ids.size(); i++) {
                var body = mockMvc.perform(get("/api/v1/attempts/{id}", ids.get(i)).with(user(fixtures.get(i).username())))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(json.readTree(body).path("answers")).hasSize(size);
            }
            totals.add(statistics.getPrepareStatementCount());
        }
        assertThat(totals.get(1)).isEqualTo(totals.get(0)); assertThat(totals.get(0)).isLessThanOrEqualTo(15);
        System.out.println("Legacy participant view JPA SQL for 3 parents with 1/20 answers: " + totals);
    }

    private void assertDetails(Fixture f, UUID id, int answerCount) throws Exception {
        var expected = json.valueToTree(attempts.getAttemptDetail(f.username(), id));
        var body = mockMvc.perform(get("/api/v1/attempts/{id}", id).with(user(f.username()))
                        .param("includeCorrectAnswer", "true").param("includeExplanation", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var actual = json.readTree(body);
        assertThat(actual).isEqualTo(expected); assertThat(actual.path("answers")).hasSize(answerCount);
        assertThat(body).doesNotContain("representation", "stateVersion", "policyVersion", "quizRevisionId", "allowedActions");
        assertThat(actual.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "attemptId", "quizId", "userId", "startedAt", "completedAt", "status", "mode", "answers");
    }
    static final String SCHEMA = "legacy_view_" + UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate admin;
    static boolean schemaCreated;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        schemaCreated = true;
        p.add("spring.datasource.url", () -> base + SCHEMA + options);
        p.add("spring.datasource.username", () -> user); p.add("spring.datasource.password", () -> password);
    }
    @AfterAll static void dropDatabase() { if (schemaCreated) admin.execute("DROP DATABASE " + SCHEMA); }

    private Fixture fixture(QuestionType type, int size) {
        return fixture(type, size, false);
    }

    private Fixture fixture(QuestionType type, int size, boolean establishSourceOwner) {
        return new TransactionTemplate(transactions).execute(status -> {
            var owner = new User(); owner.setUsername("compatibility-" + UUID.randomUUID());
            owner.setEmail(owner.getUsername() + "@example.invalid"); owner.setHashedPassword("fixture-only");
            owner.setActive(true); entityManager.persist(owner);
            var category = new Category(); category.setName("compatibility-" + UUID.randomUUID()); entityManager.persist(category);
            var quiz = new Quiz(); quiz.setCreator(owner); quiz.setCategory(category); quiz.setTitle("Compatibility fixture");
            quiz.setStatus(QuizStatus.PUBLISHED); quiz.setVisibility(establishSourceOwner ? Visibility.PRIVATE : Visibility.PUBLIC);
            quiz.setDifficulty(Difficulty.EASY);
            quiz.setEstimatedTime(1); quiz.setIsRepetitionEnabled(false); quiz.setIsTimerEnabled(false);
            quiz.setTimerDuration(1); quiz.setIsDeleted(false); entityManager.persist(quiz);
            var ids = new java.util.ArrayList<UUID>();
            for (int i = 0; i < size; i++) {
            var question = new Question(); question.setType(type); question.setDifficulty(Difficulty.EASY);
            question.setQuestionText("Original synthetic question"); question.setExplanation("Synthetic explanation");
            question.setContent(QuestionContracts.example(type, QuestionContracts.View.AUTHOR).toString());
            if (establishSourceOwner) question.establishSourceOwner(owner.getId());
            question.getQuizId().add(quiz); entityManager.persist(question); entityManager.flush();
            ids.add(question.getId());
            }
            return new Fixture(owner.getUsername(), owner.getId(), quiz.getId(), ids);
        });
    }
    private record Fixture(String username, UUID userId, UUID quiz, List<UUID> questions) { }
}
