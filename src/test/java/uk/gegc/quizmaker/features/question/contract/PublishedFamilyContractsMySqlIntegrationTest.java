package uk.gegc.quizmaker.features.question.contract;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.FixedFormMaterializer;
import uk.gegc.quizmaker.features.attempt.application.impl.FixedFormMaterializerImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.FormOptionOrderServiceImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.QuestionSelectionServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.AuthoredQuestionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcFixedFormRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcFormOptionOrderRepository;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.media.application.SavedImageStorage;
import uk.gegc.quizmaker.features.media.application.impl.RetainedImageServiceImpl;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.SavedImageVersion;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.impl.*;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;
import uk.gegc.quizmaker.features.question.config.BaselineQuestionContractConfig;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.*;
import uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionPublicationService;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizRevisionPublicationServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.Command;
import uk.gegc.quizmaker.features.quiz.domain.repository.*;
import uk.gegc.quizmaker.features.quiz.infra.publication.PublicationContent;
import uk.gegc.quizmaker.features.quiz.infra.repository.*;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.question.contract.BaselineFamilyFixtures.*;

/** Real publication, retained-media boundary, binding, registry and MySQL. Only external storage is fake. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({QuizRevisionPublicationServiceImpl.class, JdbcQuizPublicationRepository.class, JdbcQuizRevisionRepository.class,
        JdbcQuestionSourceScopeRepository.class, PublicationContent.class, RetainedImageServiceImpl.class,
        AppPermissionEvaluator.class, QuestionHandlerFactory.class,
        McqSingleHandler.class, McqMultiHandler.class, TrueFalseHandler.class, FillGapHandler.class, OrderingHandler.class,
        HotspotHandler.class, OpenQuestionHandler.class, MatchingHandler.class, ComplianceHandler.class,
        QuestionContractRegistryImpl.class, QuestionRevisionContractServiceImpl.class, JdbcQuestionContractBindingRepository.class,
        PublishedQuestionContractServiceImpl.class, BaselineQuestionContractConfig.class,
        FixedFormMaterializerImpl.class, FormOptionOrderServiceImpl.class,
        JdbcFixedFormRepository.class, JdbcFormOptionOrderRepository.class,
        PublishedFamilyContractsMySqlIntegrationTest.Config.class})
class PublishedFamilyContractsMySqlIntegrationTest {
    static final String SCHEMA = "family_contracts_" + UUID.randomUUID().toString().replace("-", "");
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

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean MediaStorageProperties mediaProperties() { return new MediaStorageProperties(); }
        @Bean SavedImageStorage storage() {
            return new SavedImageStorage() {
                public Source inspect(String key) { throw new AssertionError("Text fixture must not call storage"); }
                public void retain(SavedImageVersion v) { throw new AssertionError("Text fixture must not call storage"); }
                public void verify(SavedImageVersion v) { throw new AssertionError("Text fixture must not call storage"); }
            };
        }
        @Bean Faults faults(JdbcTemplate sql) { return new Faults(sql); }
        @Bean static BeanPostProcessor measuredSql() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ds && !(bean instanceof MeasuredSource) ? new MeasuredSource(ds) : bean;
                }
            };
        }
    }
    @Autowired PublishedQuestionContractService binding;
    @Autowired FixedFormMaterializer materializer;
    @Autowired QuizRevisionPublicationService publications;
    @Autowired QuestionRevisionContractService contracts;
    @Autowired QuestionContractRegistry registry;
    @Autowired QuizRevisionRepository revisions;
    @Autowired QuizRepository quizzes;
    @Autowired QuestionRepository questions;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate sql;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    @Autowired Faults faults;
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager); faults.reset();
        username = "family-" + UUID.randomUUID().toString().substring(0, 12);
        ownerId = user(username, true);
        quizId = tx.execute(s -> {
            var category = new Category(); category.setName(username); categories.saveAndFlush(category);
            var quiz = new Quiz(); quiz.setCreator(users.findById(ownerId).orElseThrow()); quiz.setCategory(category);
            quiz.setTitle("Family fixture"); quiz.setDescription("Synthetic source"); quiz.setDifficulty(Difficulty.EASY);
            quiz.setStatus(QuizStatus.DRAFT); quiz.setVisibility(Visibility.PRIVATE); quiz.setEstimatedTime(5);
            quiz.setIsTimerEnabled(false); quiz.setIsRepetitionEnabled(false);
            return quizzes.saveAndFlush(quiz).getId();
        });
        authenticate(username);
    }
    @AfterEach void cleanup() {
        faults.reset(); MeasuredSource.enabled = false; SecurityContextHolder.clearContext();
        tx.executeWithoutResult(s -> {
            for (String table : List.of("attempt_form_option", "attempt_form_item_option_order", "attempt_form_option_order",
                    "attempt_form_item", "attempt_form", "question_revision_contract_bindings", "quiz_publication_receipts", "quiz_publication_heads",
                    "quiz_revision_images", "quiz_publications", "quiz_revision_items", "question_option_revisions", "question_revisions",
                    "quiz_revisions", "quiz_questions", "question_tags", "question_source_confirmations", "questions", "quizzes",
                    "categories", "user_roles", "users")) sql.update("DELETE FROM " + table);
            sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id WHERE r.role_name LIKE 'family-%'");
            sql.update("DELETE FROM roles WHERE role_name LIKE 'family-%'");
        });
    }

    @Test void publishedFiveFamiliesBindAndGradeAcrossDraftEditsAndRestart() throws Exception {
        var ids = FAMILIES.stream().map(this::draft).toList();
        var first = publish(0, ids);
        var original = publications.read(quizId, first);
        var bound = binding.bind(quizId, first);
        assertThat(bound).hasSize(5);
        assertThat(binding.bind(quizId, first)).isEqualTo(bound);
        for (var item : original.content().items()) {
            var pair = contracts.resolve(ownerId, first).get(item.question().id());
            var response = QuestionContracts.example(item.question().type(), QuestionContracts.View.RESPONSE);
            pair.response().validate(item.question(), response);
            assertThat(pair.scoring().score(item.question(), response).earned()).isEqualByComparingTo("1");
        }
        tx.executeWithoutResult(s -> {
            var q = questions.findById(ids.get(0)).orElseThrow();
            var edited = (ObjectNode) QuestionContracts.example(QuestionType.MCQ_SINGLE, QuestionContracts.View.AUTHOR);
            ((ObjectNode) edited.path("options").get(0)).put("correct", false);
            ((ObjectNode) edited.path("options").get(1)).put("correct", true);
            q.setContent(edited.toString()); questions.saveAndFlush(q);
        });
        var second = publish(1, ids); binding.bind(quizId, second);
        assertThat(second).isNotEqualTo(first);
        var restarted = restartedContracts();
        var old = revisions.findById(ownerId, first).orElseThrow().items().get(0).question();
        var next = revisions.findById(ownerId, second).orElseThrow().items().get(0).question();
        var answer = QuestionContracts.example(QuestionType.MCQ_SINGLE, QuestionContracts.View.RESPONSE);
        assertThat(restarted.resolve(ownerId, first).get(old.id()).scoring().score(old, answer).earned()).isEqualByComparingTo("1");
        assertThat(restarted.resolve(ownerId, second).get(next.id()).scoring().score(next, answer).earned()).isEqualByComparingTo("0");
        assertThat(publications.read(quizId, first)).isEqualTo(original);
        assertNoAttemptEffects();
    }

    @ParameterizedTest
    @EnumSource(value = AnswerOrder.class, names = {"AUTHORED", "SHUFFLED_PER_ATTEMPT"})
    void realPublishedAdaptersMaterializeAndReplaySafeFiveFamilyForms(AnswerOrder answerOrder) throws Exception {
        UUID revisionId = publish(0, FAMILIES.stream().map(this::draft).toList());
        binding.bind(quizId, revisionId);
        var revision = publications.read(quizId, revisionId).content();
        var selection = new QuestionSelectionServiceImpl(request -> {
            throw new AssertionError("ALL materialization must not request a random seed");
        }).select(new QuestionSelection.Input(1, revisionId, 1,
                QuestionSelection.Configuration.all(), revision.items().stream().map(item ->
                new QuestionSelection.Item(item.id(), item.authoredPosition())).toList(), List.of()));
        var ordering = AuthoredQuestionOrder.order(new AuthoredQuestionOrder.Input(1, revisionId, 1,
                new QuestionOrderingPolicy(QuestionOrder.AUTHORED, 1), revision.items().stream().map(item ->
                new AuthoredQuestionOrder.Item(item.id(), item.authoredPosition())).toList(), selection.itemIds()));
        var form = new FixedForm(UUID.randomUUID(), 1, revisionId, java.util.stream.IntStream.range(0, ordering.itemIds().size())
                .mapToObj(n -> new FixedForm.Item(UUID.randomUUID(), ordering.itemIds().get(n), n)).toList());
        var command = new FixedFormMaterializer.Command(form, selection, ordering, new AnswerOrderingPolicy(answerOrder, 1));
        var result = materializer.materialize(ownerId, command);
        assertThat(result.items()).extracting(ParticipantForm.Item::type).containsExactlyElementsOf(FAMILIES);
        assertThat(materializer.materialize(ownerId, command)).isEqualTo(result);
        var named = new NamedParameterJdbcTemplate(source);
        var restartedContracts = restartedContracts();
        var restarted = new FixedFormMaterializerImpl(revisions, restartedContracts,
                new FormOptionOrderServiceImpl(revisions, restartedContracts, new JdbcFixedFormRepository(named),
                        new JdbcFormOptionOrderRepository(named), manager, new SimpleMeterRegistry()), manager, new SimpleMeterRegistry(), new JdbcFixedFormRepository(named));
        assertThat(restarted.materialize(ownerId, command)).isEqualTo(result);
        assertThat(JSON.writeValueAsString(result)).doesNotContain("correct", "answer", "sourceKey", "ownerId", "questionRevisionId");
        assertThat(count("attempt_form")).isEqualTo(1);
        assertThat(count("attempt_form_item")).isEqualTo(5);
        assertNoAttemptEffects(); // A materialized form is not an accepted or charged attempt.
    }

    @Test void unknownVersionRollsBackTheWholeSetAndConflictingRetryCannotReplaceCommittedBindings() {
        UUID revision = publish(0, FAMILIES.stream().map(this::draft).toList());
        var expected = publications.read(quizId, revision).content().items().stream()
                .map(item -> BaselineQuestionContracts.binding(item.question()))
                .sorted(Comparator.comparing(b -> b.questionRevisionId().toString())).toList();
        var unsupported = new ArrayList<>(expected);
        var last = unsupported.get(unsupported.size() - 1);
        unsupported.set(unsupported.size() - 1, new QuestionContractBinding(last.questionRevisionId(), last.response(),
                new ContractVersion(last.scoring().id(), 99)));
        assertThatThrownBy(() -> contracts.bind(ownerId, revision, unsupported)).isInstanceOfSatisfying(ContractResolutionException.class,
                e -> assertThat(e.reason()).isEqualTo(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT));
        assertThat(count("question_revision_contract_bindings")).isZero();
        binding.bind(quizId, revision);
        var conflicting = new ArrayList<>(expected);
        conflicting.set(0, new QuestionContractBinding(expected.get(0).questionRevisionId(), expected.get(1).response(), expected.get(1).scoring()));
        assertThatThrownBy(() -> contracts.bind(ownerId, revision, conflicting)).isInstanceOfSatisfying(ContractResolutionException.class,
                e -> assertThat(e.reason()).isEqualTo(ContractResolutionException.Reason.BINDING_CONFLICT));
        assertThat(restartedContracts().resolve(ownerId, revision).values().stream().map(c -> c.binding()).toList())
                .containsExactlyInAnyOrderElementsOf(expected);
        assertNoAttemptEffects();
    }

    @Test void fillGapWithAndWithoutPoolKeepsItsCapturedAnswerAfterAnAuthorEdit() throws Exception {
        UUID id = draft(QuestionType.FILL_GAP);
        var oldContent = (ObjectNode) QuestionContracts.example(QuestionType.FILL_GAP, QuestionContracts.View.AUTHOR);
        oldContent.remove("options"); edit(id, oldContent);
        UUID first = publish(0, List.of(id)); binding.bind(quizId, first);
        var withPool = (ObjectNode) QuestionContracts.example(QuestionType.FILL_GAP, QuestionContracts.View.AUTHOR);
        withPool.putArray("options").add("Paris").add("Rome").add("London").add("Berlin").add("Madrid").add("Lisbon").add("Vienna");
        edit(id, withPool);
        UUID second = publish(1, List.of(id)); binding.bind(quizId, second);
        assertThat(second).isNotEqualTo(first);
        ((ObjectNode) withPool.path("gaps").get(0)).put("answer", "Rome");
        withPool.remove("options"); edit(id, withPool);
        UUID third = publish(2, List.of(id)); binding.bind(quizId, third);
        var response = QuestionContracts.example(QuestionType.FILL_GAP, QuestionContracts.View.RESPONSE);
        for (var revision : List.of(first, second, third)) {
            var q = publications.read(quizId, revision).content().items().get(0).question();
            var score = restartedContracts().resolve(ownerId, revision).get(q.id()).scoring().score(q, response);
            assertThat(score.earned()).isEqualByComparingTo(revision.equals(third) ? "0" : "1");
        }
    }

    @ParameterizedTest
    @EnumSource(value = QuestionType.class, names = {"OPEN", "MATCHING", "COMPLIANCE"})
    void unsupportedFamilyCannotPartiallyBindAPublishedMixedQuiz(QuestionType unsupported) {
        UUID revision = publish(0, List.of(draft(QuestionType.TRUE_FALSE), draft(unsupported)));
        assertThatThrownBy(() -> binding.bind(quizId, revision)).isInstanceOfSatisfying(ContractResolutionException.class,
                e -> assertThat(e.reason()).isEqualTo(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT));
        assertThat(count("question_revision_contract_bindings")).isZero(); assertNoAttemptEffects();
        assertThat(publications.read(quizId, revision).content().items()).hasSize(2);
    }

    @Test void aReachedBindingWriteRollsBackAndFreshRetryCommitsTheCompleteSet() {
        UUID revision = publish(0, FAMILIES.stream().map(this::draft).toList());
        faults.failAfterInsert = true;
        assertThatThrownBy(() -> binding.bind(quizId, revision)).isInstanceOf(ContractResolutionException.class);
        assertThat(faults.reached.get()).isTrue();
        assertThat(count("question_revision_contract_bindings")).isZero();
        assertThat(publications.read(quizId, revision).content().items()).hasSize(5);
        faults.failAfterInsert = false;
        assertThat(binding.bind(quizId, revision)).hasSize(5);
        assertThat(restartedContracts().resolve(ownerId, revision)).hasSize(5);
        assertNoAttemptEffects();
    }

    @Test void authenticatedAuthorAccessIsRequiredIncludingOnReplay() {
        UUID revision = publish(0, List.of(draft(QuestionType.TRUE_FALSE)));
        binding.bind(quizId, revision); // Positive control through the exact boundary.
        SecurityContextHolder.clearContext(); assertPublicationReason(() -> binding.bind(quizId, revision), PublicationException.Reason.UNAUTHENTICATED);
        String other = "family-other-" + UUID.randomUUID().toString().substring(0, 8);
        user(other, true); authenticate(other);
        assertPublicationReason(() -> binding.bind(quizId, revision), PublicationException.Reason.UNAVAILABLE);
        authenticate(username);
        tx.executeWithoutResult(s -> sql.update("DELETE FROM user_roles WHERE user_id=UUID_TO_BIN(?)", ownerId.toString()));
        assertPublicationReason(() -> binding.bind(quizId, revision), PublicationException.Reason.UNAVAILABLE);
        assertThat(count("question_revision_contract_bindings")).isEqualTo(1); assertNoAttemptEffects();
    }

    @Test void unpublishedAndForeignRevisionReferencesCannotBind() {
        draft(QuestionType.TRUE_FALSE);
        assertPublicationReason(() -> binding.bind(quizId, UUID.randomUUID()), PublicationException.Reason.UNAVAILABLE);
        assertThat(count("question_revision_contract_bindings")).isZero();
        UUID revision = publish(0, questions.findAll().stream().map(Question::getId).toList());
        assertPublicationReason(() -> binding.bind(UUID.randomUUID(), revision), PublicationException.Reason.UNAVAILABLE);
        assertThat(binding.bind(quizId, revision)).hasSize(1);
    }

    @Test void concurrentIdenticalBindingsBothSucceedWithOneDurableSet() throws Exception {
        UUID revision = publish(0, FAMILIES.stream().map(this::draft).toList());
        faults.beforeScope = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> asOwner(() -> binding.bind(quizId, revision)));
            var b = pool.submit(() -> asOwner(() -> binding.bind(quizId, revision)));
            assertThat(a.get(20, TimeUnit.SECONDS)).containsExactlyInAnyOrderElementsOf(b.get(20, TimeUnit.SECONDS));
            assertThat(count("question_revision_contract_bindings")).isEqualTo(5);
            assertThat(restartedContracts().resolve(ownerId, revision)).hasSize(5);
        } finally { faults.beforeScope = null; pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void loadingMultiplePublishedRevisionsAndTheirOptionsUsesBoundedQueries(TestReporter report) {
        var ids = new ArrayList<UUID>(); ids.add(draft(QuestionType.MCQ_SINGLE));
        UUID small = publish(0, ids); binding.bind(quizId, small);
        int single = measure(() -> revisions.findByIds(ownerId, List.of(small)));
        assertThat(single).as("The SQL observer must record actual snapshot reads").isPositive();
        var revisionsToRead = new ArrayList<>(List.of(small));
        for (int v = 1; v <= 3; v++) {
            for (int i = 0; i < 5; i++) ids.add(draft(QuestionType.MCQ_MULTI));
            UUID revision = publish(v, ids); binding.bind(quizId, revision); revisionsToRead.add(revision);
        }
        int multiple = measure(() -> {
            var rows = revisions.findByIds(ownerId, revisionsToRead);
            assertThat(rows).hasSize(4);
            assertThat(rows.stream().flatMap(q -> q.items().stream()).flatMap(i -> i.question().options().stream()).count()).isGreaterThan(50);
        });
        assertThat(multiple).isEqualTo(single).isLessThanOrEqualTo(5);
        report.publishEntry(Map.of("singleRevisionSelects", Integer.toString(single),
                "fourRevisionSelects", Integer.toString(multiple)));
        // The new binding operation reads through the same bulk snapshot repository. Its per-identity
        // locks and inserts are deliberate persistence operations, not lazy relationship fetches.
    }

    private UUID draft(QuestionType type) {
        return tx.execute(s -> {
            var q = new Question(); q.setType(type); q.setDifficulty(Difficulty.EASY); q.setQuestionText("Frozen " + type);
            var content = (ObjectNode) QuestionContracts.example(type, QuestionContracts.View.AUTHOR);
            if (type == QuestionType.FILL_GAP)
                content.putArray("options").add("Paris").add("Rome").add("London").add("Berlin").add("Madrid").add("Lisbon").add("Vienna");
            q.setContent(content.toString());
            q.establishSourceOwner(ownerId); q.setQuizId(new ArrayList<>(List.of(quizzes.findById(quizId).orElseThrow())));
            return questions.saveAndFlush(q).getId();
        });
    }
    private void edit(UUID id, ObjectNode content) { tx.executeWithoutResult(s -> { var q = questions.findById(id).orElseThrow(); q.setContent(content.toString()); questions.saveAndFlush(q); }); }
    private UUID publish(long version, List<UUID> order) { return publications.publish(new Command(1, quizId, UUID.randomUUID(), version, order)).publication().revision().id(); }
    private UUID user(String name, boolean permission) {
        UUID id = tx.execute(s -> {
            var u = new User(); u.setUsername(name); u.setEmail(name + "@example.invalid"); u.setHashedPassword("test-only");
            u.setActive(true); u.setRoles(new HashSet<>()); return users.saveAndFlush(u).getId();
        });
        if (permission) tx.executeWithoutResult(s -> {
            String role = "family-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name='QUIZ_UPDATE'", role);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", id.toString(), role);
        });
        return id;
    }
    private QuestionRevisionContractService restartedContracts() {
        var restartedRegistry = new QuestionContractRegistryImpl(FAMILIES.stream().map(FamilyResponseContractV1::new)
                .map(x -> (uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract) x).toList(),
                FAMILIES.stream().map(FamilyScoringContractV1::new)
                        .map(x -> (uk.gegc.quizmaker.features.question.application.contract.VersionedScoringContract) x).toList(), new SimpleMeterRegistry());
        return new QuestionRevisionContractServiceImpl(new JdbcQuestionContractBindingRepository(new NamedParameterJdbcTemplate(source)), restartedRegistry, manager);
    }
    private int count(String table) { return tx.execute(s -> sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)); }
    private void assertNoAttemptEffects() { assertThat(count("attempts")).isZero(); assertThat(count("answers")).isZero(); }
    private static void authenticate(String name) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, null, List.of())); }
    private <T> T asOwner(Supplier<T> action) { authenticate(username); try { return action.get(); } finally { SecurityContextHolder.clearContext(); } }
    private static void assertPublicationReason(Runnable action, PublicationException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PublicationException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
    private int measure(Runnable action) {
        MeasuredSource.reads.set(0); MeasuredSource.enabled = true;
        try { action.run(); return MeasuredSource.reads.get(); } finally { MeasuredSource.enabled = false; }
    }
    @org.aspectj.lang.annotation.Aspect
    static class Faults {
        final JdbcTemplate sql; volatile boolean failAfterInsert; final AtomicBoolean reached = new AtomicBoolean();
        volatile CyclicBarrier beforeScope;
        Faults(JdbcTemplate sql) { this.sql = sql; }
        void reset() { failAfterInsert = false; reached.set(false); beforeScope = null; }
        @org.aspectj.lang.annotation.Around("execution(* uk.gegc.quizmaker.features.question.domain.repository.QuestionContractBindingRepository.insert(..))")
        Object insert(org.aspectj.lang.ProceedingJoinPoint call) throws Throwable {
            Object result = call.proceed();
            if (failAfterInsert) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM question_revision_contract_bindings", Integer.class)).isPositive();
                reached.set(true); throw new DataAccessResourceFailureException("Reached binding write fault");
            }
            return result;
        }
        @org.aspectj.lang.annotation.Before("execution(* uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService.declareScope(..))")
        void scope() throws Exception { if (beforeScope != null) beforeScope.await(10, TimeUnit.SECONDS); }
    }
    static class MeasuredSource extends DelegatingDataSource {
        static final AtomicInteger reads = new AtomicInteger(); static volatile boolean enabled;
        MeasuredSource(DataSource target) { super(target); }
        @Override public Connection getConnection() throws SQLException { return observed(super.getConnection()); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return observed(super.getConnection(u, p)); }
        private Connection observed(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, args) -> {
                if (enabled && m.getName().equals("prepareStatement") && args[0] instanceof String sql && sql.stripLeading().startsWith("SELECT")) reads.incrementAndGet();
                try { return m.invoke(connection, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
    }
}
