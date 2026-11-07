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

/** Real legacy writers, mapper and HTTP adapter; generated Flyway catalog includes both generations. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "quizmaker.attempts.participant-view-enabled=true", "spring.jpa.properties.hibernate.generate_statistics=true"})
class ParticipantViewLegacyHttpIntegrationTest extends BaseIntegrationTest {
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AttemptService attempts;
    @Autowired ParticipantAttemptViewService views;
    @Autowired AttemptSubjectService subjects;

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

    @Test void foreignMissingAndAmbiguousRootsDoNotFallBackToLegacy() throws Exception {
        var f = fixture(QuestionType.TRUE_FALSE, 1); var other = fixture(QuestionType.TRUE_FALSE, 1);
        var id = attempts.startAttempt(f.username(), f.quiz(), AttemptMode.ALL_AT_ONCE).attemptId(); assertDetails(f, id, 0);
        mockMvc.perform(get("/api/v1/attempts/{id}", id)).andExpect(status().isUnauthorized());
        for (var candidate : List.of(id, UUID.randomUUID()))
            mockMvc.perform(get("/api/v1/attempts/{id}", candidate).with(user(other.username())))
                    .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        // Partial target evidence is deliberately ambiguous, never permission to reinterpret as legacy.
        subjects.bindOrReplay(f.userId(), uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject.authenticated(id, f.userId()));
        mockMvc.perform(get("/api/v1/attempts/{id}", id).with(user(f.username())))
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
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
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
        return new TransactionTemplate(transactions).execute(status -> {
            var owner = new User(); owner.setUsername("compatibility-" + UUID.randomUUID());
            owner.setEmail(owner.getUsername() + "@example.invalid"); owner.setHashedPassword("fixture-only");
            owner.setActive(true); entityManager.persist(owner);
            var category = new Category(); category.setName("compatibility-" + UUID.randomUUID()); entityManager.persist(category);
            var quiz = new Quiz(); quiz.setCreator(owner); quiz.setCategory(category); quiz.setTitle("Compatibility fixture");
            quiz.setStatus(QuizStatus.PUBLISHED); quiz.setVisibility(Visibility.PUBLIC); quiz.setDifficulty(Difficulty.EASY);
            quiz.setEstimatedTime(1); quiz.setIsRepetitionEnabled(false); quiz.setIsTimerEnabled(false);
            quiz.setTimerDuration(1); quiz.setIsDeleted(false); entityManager.persist(quiz);
            var ids = new java.util.ArrayList<UUID>();
            for (int i = 0; i < size; i++) {
            var question = new Question(); question.setType(type); question.setDifficulty(Difficulty.EASY);
            question.setQuestionText("Original synthetic question"); question.setExplanation("Synthetic explanation");
            question.setContent(QuestionContracts.example(type, QuestionContracts.View.AUTHOR).toString());
            question.getQuizId().add(quiz); entityManager.persist(question); entityManager.flush();
            ids.add(question.getId());
            }
            return new Fixture(owner.getUsername(), owner.getId(), quiz.getId(), ids);
        });
    }
    private record Fixture(String username, UUID userId, UUID quiz, List<UUID> questions) { }
}
