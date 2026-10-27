package uk.gegc.quizmaker.features.attempt.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionRequest;
import uk.gegc.quizmaker.features.attempt.application.AttemptLifecycleService;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.user.domain.model.User;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleStateException.Reason.STATE_UNAVAILABLE;
import static uk.gegc.quizmaker.features.attempt.lifecycle.LifecycleStateTest.rejected;

/** Real committed legacy commands, fresh reads and owner denial; no outer test transaction. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LegacyLifecycleIsolationIntegrationTest extends BaseIntegrationTest {
    @Autowired AttemptService legacy;
    @Autowired AttemptLifecycleService target;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    private boolean ownsTable;

    @BeforeAll
    void prepareUnmappedTargetStorage() {
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='attempt_lifecycle_state'", Integer.class) == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V88__create_authoritative_attempt_lifecycle.sql")).execute(dataSource);
            ownsTable = true;
        }
    }

    @AfterAll
    void removeOwnedTargetStorage() {
        if (ownsTable) jdbcTemplate.execute("DROP TABLE attempt_lifecycle_state");
    }

    @ParameterizedTest
    @EnumSource(AttemptMode.class)
    void legacyStartReadAnswerCompletionAndOwnerDenialDoNotInventTargetState(AttemptMode mode) {
        var fixture = new TransactionTemplate(transactions).execute(status -> fixture());
        UUID id = legacy.startAttempt(fixture.owner(), fixture.quiz(), mode).attemptId();
        assertThat(legacy.getAttemptDetail(fixture.owner(), id).status()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThatThrownBy(() -> legacy.getAttemptDetail("wrong-owner", id)).isInstanceOf(AccessDeniedException.class);
        assertThat(targetRows(id)).isZero();
        assertThat(target.read(LifecycleReference.Legacy.NO_TARGET_STATE)).isEqualTo(LifecycleRead.Legacy.NO_TARGET_STATE);

        var answer = legacy.submitAnswer(fixture.owner(), id,
                new AnswerSubmissionRequest(fixture.question(), json.createObjectNode().put("answer", true), false, false, false));
        assertThat(answer.answerId()).isNotNull();
        assertThat(answer.questionId()).isEqualTo(fixture.question());
        assertThat(answer.score()).isEqualTo(1.0);
        assertThat(answer.isCorrect()).isNull();
        assertThat(answer.correctAnswer()).isNull();
        var result = legacy.completeAttempt(fixture.owner(), id);
        assertThat(result.totalScore()).isEqualTo(1.0);
        assertThat(result.correctCount()).isEqualTo(1);
        assertThat(legacy.getAttemptDetail(fixture.owner(), id).status()).isEqualTo(AttemptStatus.COMPLETED);
        assertThat(targetRows(id)).isZero();
        rejected(() -> target.read(new LifecycleReference.Target(id)), STATE_UNAVAILABLE);
        assertThat(targetRows(id)).isZero();
    }

    private Fixture fixture() {
        var owner = new User(); owner.setUsername("lifecycle-" + UUID.randomUUID());
        owner.setEmail(owner.getUsername() + "@example.invalid"); owner.setHashedPassword("fixture-only");
        owner.setActive(true); entityManager.persist(owner);
        var category = new Category(); category.setName("lifecycle-" + UUID.randomUUID()); entityManager.persist(category);
        var quiz = new Quiz(); quiz.setCreator(owner); quiz.setCategory(category); quiz.setTitle("Legacy lifecycle isolation");
        quiz.setStatus(QuizStatus.PUBLISHED); quiz.setVisibility(Visibility.PUBLIC); quiz.setDifficulty(Difficulty.EASY);
        quiz.setEstimatedTime(1); quiz.setIsRepetitionEnabled(false); quiz.setIsTimerEnabled(false); quiz.setIsDeleted(false);
        entityManager.persist(quiz);
        var question = new Question(); question.setType(QuestionType.TRUE_FALSE); question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("Synthetic legacy statement"); question.setContent("{\"answer\":true}");
        question.getQuizId().add(quiz); entityManager.persist(question); entityManager.flush();
        return new Fixture(owner.getUsername(), quiz.getId(), question.getId());
    }

    private int targetRows(UUID id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attempt_lifecycle_state WHERE attempt_id=UUID_TO_BIN(?)", Integer.class, id.toString());
    }

    private record Fixture(String owner, UUID quiz, UUID question) { }
}
