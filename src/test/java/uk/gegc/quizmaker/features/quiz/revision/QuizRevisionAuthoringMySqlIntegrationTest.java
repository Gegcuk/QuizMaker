package uk.gegc.quizmaker.features.quiz.revision;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gegc.quizmaker.features.quiz.revision.RevisionFixtures.*;

/** Real authoring repositories and committed transactions; the JPA slice loads no external providers. */
@Tag("db-serial")
@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(JdbcQuizRevisionRepository.class)
class QuizRevisionAuthoringMySqlIntegrationTest {
    private static final String HISTORY = "flyway_revision_authoring_test_history";
    private static final String CHANGED_CONTENT = """
            {"options":[{"id":"a","text":"Paris","correct":false},{"id":"b","text":"London","correct":true}]}
            """;
    @Autowired private UserRepository users;
    @Autowired private CategoryRepository categories;
    @Autowired private QuizRepository quizzes;
    @Autowired private QuestionRepository questions;
    @Autowired private QuizRevisionRepository revisions;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate sql;
    private TransactionTemplate transaction;
    private DraftFixture draft;

    @BeforeEach
    void fixture() {
        transaction = new TransactionTemplate(transactionManager);
        dropRevisionTables();
        Flyway.configure().dataSource(sql.getDataSource()).table(HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("80").target("82").load().migrate();
        draft = transaction.execute(status -> persistAuthoringFixture());
        revisions.insert(draft.snapshot());
    }

    @AfterEach
    void cleanUp() {
        dropRevisionTables();
        if (draft == null) return;
        // Physical cleanup is test-only; scenario deletions below use the production soft-delete mapping.
        transaction.executeWithoutResult(status -> {
            sql.update("DELETE FROM quiz_questions WHERE quiz_id=UUID_TO_BIN(?)", draft.snapshot().quizId().toString());
            sql.update("DELETE FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)", draft.snapshot().quizId().toString());
            draft.snapshot().items().forEach(item -> sql.update("DELETE FROM questions WHERE id=UUID_TO_BIN(?)",
                    item.question().questionId().toString()));
            sql.update("DELETE FROM categories WHERE category_id=UUID_TO_BIN(?)", draft.categoryId().toString());
            sql.update("DELETE FROM users WHERE user_id=UUID_TO_BIN(?)", draft.snapshot().ownerId().toString());
        });
    }

    @Test
    void reloadKeepsExactContentAfterAuthoringChangesAndPreservesStableIdentities() {
        var snapshot = draft.snapshot();
        var question = snapshot.items().get(0).question();
        transaction.executeWithoutResult(status -> {
            var mutable = questions.findById(question.questionId()).orElseThrow();
            mutable.setQuestionText("Changed draft question");
            mutable.setContent(CHANGED_CONTENT);
            mutable.setExplanation("Changed draft explanation");
            quizzes.findById(snapshot.quizId()).orElseThrow().setTitle("Changed draft title");
        });

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        // These fresh JDBC reads occur after commit, without a JPA first-level cache or test transaction.
        var stored = sql.queryForMap("SELECT question, content, explanation FROM questions WHERE id=UUID_TO_BIN(?)",
                question.questionId().toString());
        assertThat(stored.get("question")).isEqualTo("Changed draft question");
        assertThat(stored.get("explanation")).isEqualTo("Changed draft explanation");
        var payload = QuestionContracts.read(question.type(), QuestionContracts.View.AUTHOR, (String) stored.get("content"));
        assertThat(payload).isEqualTo(QuestionContracts.read(question.type(), QuestionContracts.View.AUTHOR, CHANGED_CONTENT));
        assertThat(payload.path("options").get(0).path("correct").booleanValue()).isFalse();
        assertThat(payload.path("options").get(1).path("correct").booleanValue()).isTrue();
        assertThat(sql.queryForObject("SELECT title FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)",
                String.class, snapshot.quizId().toString())).isEqualTo("Changed draft title");

        // Record equality covers every field, both items, option IDs, positions and list order.
        assertThat(revisions.findById(snapshot.ownerId(), snapshot.id())).contains(snapshot);
    }

    @Test
    void reloadKeepsSnapshotAfterRepositorySoftDeletionOfAuthoringQuizAndQuestions() {
        var snapshot = draft.snapshot();
        transaction.executeWithoutResult(status -> {
            quizzes.deleteById(snapshot.quizId());
            quizzes.flush();
            snapshot.items().forEach(item -> questions.deleteById(item.question().questionId()));
        });

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(quizzes.findById(snapshot.quizId())).isEmpty();
        assertSoftDeletedRow("quizzes", "quiz_id", snapshot.quizId());
        for (var item : snapshot.items()) {
            UUID questionId = item.question().questionId();
            assertThat(questions.findById(questionId)).isEmpty();
            assertSoftDeletedRow("questions", "id", questionId);
        }
        assertThat(revisions.findById(snapshot.ownerId(), snapshot.id())).contains(snapshot);
    }

    private void assertSoftDeletedRow(String table, String idColumn, UUID id) {
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + idColumn
                + "=UUID_TO_BIN(?) AND is_deleted=true AND deleted_at IS NOT NULL", Integer.class, id.toString()))
                .as("Soft-deleted %s row remains physically stored", table).isEqualTo(1);
    }

    private DraftFixture persistAuthoringFixture() {
        var owner = new User();
        owner.setUsername("revision-" + UUID.randomUUID());
        owner.setEmail(owner.getUsername() + "@example.test");
        owner.setHashedPassword("fixture-only");
        users.saveAndFlush(owner);
        var category = new Category();
        category.setName("Revision fixture " + UUID.randomUUID());
        categories.saveAndFlush(category);
        var mutableQuestions = new ArrayList<Question>();
        var snapshots = new ArrayList<QuestionRevision>();
        for (int index = 0; index < 2; index++) {
            var values = question("authoring-" + index);
            var mutable = new Question();
            mutable.setType(values.type());
            mutable.setDifficulty(values.difficulty());
            mutable.setQuestionText(values.questionText() + " " + index);
            mutable.setContent(values.content());
            mutable.setHint(values.hint());
            mutable.setExplanation(values.explanation());
            mutable.setAttachmentUrl("https://example.invalid/question-" + index + ".png");
            questions.saveAndFlush(mutable);
            mutableQuestions.add(mutable);
            snapshots.add(new QuestionRevision(id("revision-" + mutable.getId()), mutable.getId(), 1,
                    mutable.getType(), mutable.getDifficulty(), mutable.getQuestionText(), mutable.getContent(),
                    mutable.getHint(), mutable.getExplanation(), mutable.getAttachmentUrl(), null, CREATED,
                    List.of(new QuestionRevision.Option(values.options().get(1).id(), "b", 7),
                            new QuestionRevision.Option(values.options().get(0).id(), "a", 2))));
        }
        var quiz = new Quiz();
        quiz.setCreator(owner);
        quiz.setCategory(category);
        quiz.setTitle("Original draft title");
        quiz.setDescription("Original draft description");
        quiz.setDifficulty(Difficulty.EASY);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setEstimatedTime(10);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        quiz.setQuestions(new HashSet<>(mutableQuestions));
        quizzes.saveAndFlush(quiz);
        var snapshot = new QuizRevision(id("revision-" + quiz.getId()), quiz.getId(), owner.getId(), 1,
                quiz.getTitle(), quiz.getDescription(), CREATED,
                List.of(new QuizRevision.Item(id("second-item"), 4, snapshots.get(1)),
                        new QuizRevision.Item(id("first-item"), 1, snapshots.get(0))));
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM quiz_questions WHERE quiz_id=UUID_TO_BIN(?)",
                Integer.class, quiz.getId().toString())).isEqualTo(2);
        return new DraftFixture(snapshot, category.getId());
    }

    private void dropRevisionTables() {
        for (String table : List.of("question_revision_contract_bindings", "quiz_revision_items", "question_option_revisions", "quiz_revisions", "question_revisions", HISTORY)) {
            sql.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private record DraftFixture(QuizRevision snapshot, UUID categoryId) { }
}
