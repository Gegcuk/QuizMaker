package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionRequest;
import uk.gegc.quizmaker.features.attempt.api.dto.BatchAnswerSubmissionRequest;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.attempt.domain.model.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.media.domain.model.*;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentAvailability;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.repository.AnswerRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.mapping.SafeQuestionMapper;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // Never drop shared schemas while another test's Spring context remains cached.
        "spring.jpa.hibernate.ddl-auto=update"
})
class QuestionContractsIntegrationTest extends BaseIntegrationTest {
    @Autowired AttemptService attempts;
    @Autowired AttemptRepository attemptRepository;
    @Autowired QuestionService questions;
    @Autowired QuestionRepository questionRepository;
    @Autowired AnswerRepository answerRepository;
    @Autowired SafeQuestionMapper safeMapper;
    @Autowired ObjectMapper json;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void authorPageFormAndReviewHaveBoundedQueriesWithMultipleDistinctRelationships(org.junit.jupiter.api.TestReporter reporter) {
        Fixture small = fixture(2, AttemptStatus.COMPLETED);
        Fixture large = fixture(10, AttemptStatus.COMPLETED);
        entityManager.flush();
        long smallPage = authorQueries(small);
        long largePage = authorQueries(large);
        assertThat(largePage).isEqualTo(smallPage).isLessThanOrEqualTo(9);
        long smallForm = formQueries(small);
        long largeForm = formQueries(large);
        assertThat(largeForm).isEqualTo(smallForm).isLessThanOrEqualTo(2);
        long smallReview = reviewQueries(small);
        long largeReview = reviewQueries(large);
        assertThat(largeReview).isEqualTo(smallReview).isLessThanOrEqualTo(5);
        reporter.publishEntry("queries_for_2_and_10_questions", "author=" + smallPage + "/" + largePage
                + ", form=" + smallForm + "/" + largeForm + ", review=" + smallReview + "/" + largeReview);
    }

    @Test
    void singleQuestionMediaQueriesStayBoundedForTwoAndTenDistinctOptions(org.junit.jupiter.api.TestReporter reporter) {
        Question small = questionWithImageOptions(2);
        Question large = questionWithImageOptions(10);
        entityManager.flush();

        long smallQueries = singleQuestionQueries(small.getId(), 2);
        long largeQueries = singleQuestionQueries(large.getId(), 10);

        assertThat(largeQueries).isEqualTo(smallQueries).isLessThanOrEqualTo(3);
        reporter.publishEntry("queries_for_2_and_10_image_options", smallQueries + "/" + largeQueries);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void malformedSecondBatchAnswerRollsBackTheFirstAndUnavailableContentCannotComplete() {
        var tx = new TransactionTemplate(transactions);
        Fixture fixture = tx.execute(status -> fixture(2, AttemptStatus.IN_PROGRESS));
        try {
            var good = QuestionContracts.example(QuestionType.MCQ_SINGLE, QuestionContracts.View.RESPONSE);
            var bad = json.createObjectNode().put("selectedOptionId", 42);
            var batch = new BatchAnswerSubmissionRequest(List.of(
                    new AnswerSubmissionRequest(fixture.questions().get(0).getId(), good, false, false, false),
                    new AnswerSubmissionRequest(fixture.questions().get(1).getId(), bad, false, false, false)));
            assertThatThrownBy(() -> attempts.submitBatch(fixture.owner().getUsername(), fixture.attempt().getId(), batch))
                    .isInstanceOf(QuestionContractException.class);
            tx.executeWithoutResult(status -> {
                assertThat(answerRepository.countByAttemptId(fixture.attempt().getId())).isZero();
                Question question = questionRepository.findById(fixture.questions().get(1).getId()).orElseThrow();
                question.setContent("{\"options\":null}");
            });
            assertThatThrownBy(() -> attempts.completeAttempt(fixture.owner().getUsername(), fixture.attempt().getId()))
                    .isInstanceOf(QuestionContentUnavailableException.class);
            tx.executeWithoutResult(status -> {
                var stored = attemptRepository.findById(fixture.attempt().getId()).orElseThrow();
                assertThat(stored.getStatus()).isEqualTo(AttemptStatus.IN_PROGRESS);
                assertThat(stored.getCompletedAt()).isNull();
                assertThat(answerRepository.countByAttemptId(stored.getId())).isZero();
            });
        } finally {
            tx.executeWithoutResult(status -> deleteCommittedFixture(fixture));
        }
    }

    @Test
    void corruptReviewKeepsHealthyQuestionsAndRecordedScoresWhileWrongOwnerRemainsDenied() {
        Fixture fixture = fixture(3, AttemptStatus.COMPLETED);
        fixture.questions().get(1).setContent("{\"options\":null}");
        entityManager.flush(); entityManager.clear();
        var review = attempts.getAttemptReview(fixture.owner().getUsername(), fixture.attempt().getId(), true, true, true);
        assertThat(review.answers()).hasSize(3);
        assertThat(review.totalScore()).isEqualTo(3.0);
        assertThat(review.answers().stream().filter(a -> a.contentAvailability() == QuestionContentAvailability.UNAVAILABLE)).hasSize(1);
        var unavailable = review.answers().stream().filter(a -> a.contentAvailability() == QuestionContentAvailability.UNAVAILABLE).findFirst().orElseThrow();
        assertThat(unavailable.questionSafeContent()).isNull();
        assertThat(unavailable.correctAnswer()).isNull();
        assertThat(unavailable.isCorrect()).isTrue();
        assertThat(unavailable.score()).isEqualTo(1.0);
        assertThat(unavailable.contentProblem().getStatus()).isEqualTo(409);
        assertThatThrownBy(() -> attempts.getAttemptReview(fixture.other().getUsername(), fixture.attempt().getId(), true, true, true))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unauthenticatedHttpSubmissionRemainsUnauthorizedBeforeParsing() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/attempts/{id}/answers", UUID.randomUUID())
                        .accept("application/json").contentType("application/json").content("{private-canary"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    private void deleteCommittedFixture(Fixture fixture) {
        // This one test crosses transaction boundaries. Delete only its synthetic rows;
        // ordinary tests still use BaseIntegrationTest's transactional rollback.
        jdbcTemplate.update("DELETE FROM answers WHERE attempt_id=UUID_TO_BIN(?)", fixture.attempt().getId().toString());
        jdbcTemplate.update("DELETE FROM attempts WHERE id=UUID_TO_BIN(?)", fixture.attempt().getId().toString());
        Map<UUID, Quiz> quizzes = new LinkedHashMap<>();
        for (Question question : fixture.questions()) {
            String id = question.getId().toString();
            jdbcTemplate.update("DELETE FROM question_tags WHERE question_id=UUID_TO_BIN(?)", id);
            jdbcTemplate.update("DELETE FROM quiz_questions WHERE question_id=UUID_TO_BIN(?)", id);
            jdbcTemplate.update("DELETE FROM questions WHERE id=UUID_TO_BIN(?)", id);
            jdbcTemplate.update("DELETE FROM media_assets WHERE asset_id=UUID_TO_BIN(?)", question.getAttachmentAssetId().toString());
            question.getTags().forEach(tag -> jdbcTemplate.update("DELETE FROM tags WHERE tag_id=UUID_TO_BIN(?)", tag.getId().toString()));
            question.getQuizId().forEach(quiz -> quizzes.put(quiz.getId(), quiz));
        }
        for (Quiz quiz : quizzes.values()) {
            jdbcTemplate.update("DELETE FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)", quiz.getId().toString());
            jdbcTemplate.update("DELETE FROM categories WHERE category_id=UUID_TO_BIN(?)", quiz.getCategory().getId().toString());
        }
        for (User user : List.of(fixture.owner(), fixture.other())) {
            jdbcTemplate.update("DELETE FROM users WHERE user_id=UUID_TO_BIN(?)", user.getId().toString());
        }
    }

    private long authorQueries(Fixture fixture) {
        clearStatistics();
        var page = questions.listQuestions(fixture.quiz().getId(), PageRequest.of(0, 100),
                new UsernamePasswordAuthenticationToken(fixture.owner().getUsername(), null, List.of()));
        assertThat(page.getContent()).hasSize(fixture.questions().size());
        for (var question : page) {
            assertThat(question.getQuizIds()).hasSize(2);
            assertThat(question.getTagIds()).hasSize(1);
            assertThat(question.getContentAvailability()).isEqualTo(QuestionContentAvailability.AVAILABLE);
            assertThat(question.getContent().at("/options/0/media/cdnUrl").asText()).isNotBlank();
        }
        json.valueToTree(page);
        return statistics();
    }

    private Question questionWithImageOptions(int count) {
        Question question = fixture(1, AttemptStatus.COMPLETED).questions().get(0);
        var content = json.createObjectNode();
        var options = content.putArray("options");
        for (int i = 0; i < count; i++) {
            MediaAsset media = new MediaAsset(); media.setId(UUID.randomUUID());
            media.setType(MediaAssetType.IMAGE); media.setStatus(MediaAssetStatus.READY);
            media.setKey(media.getId() + ".png"); media.setMimeType("image/png");
            entityManager.persist(media);
            options.addObject().put("id", "option-" + i).put("text", "Option " + i)
                    .put("correct", i == 0).putObject("media").put("assetId", media.getId().toString());
        }
        question.setContent(content.toString());
        return question;
    }

    private long singleQuestionQueries(UUID id, int expectedOptions) {
        clearStatistics();
        var dto = safeMapper.toSafeDto(questionRepository.findById(id).orElseThrow());
        assertThat(dto.getContentAvailability()).isEqualTo(QuestionContentAvailability.AVAILABLE);
        assertThat(dto.getAttachment()).isNotNull();
        assertThat(dto.getSafeContent().path("options")).hasSize(expectedOptions);
        dto.getSafeContent().path("options").forEach(option ->
                assertThat(option.at("/media/cdnUrl").asText()).isNotBlank());
        json.valueToTree(dto);
        return statistics();
    }

    private long formQueries(Fixture fixture) {
        clearStatistics();
        var form = safeMapper.toSafeDtoList(questionRepository.findAllByQuizId_IdOrderById(fixture.quiz().getId()));
        assertThat(form).hasSize(fixture.questions().size());
        form.forEach(question -> {
            assertThat(question.getAttachment()).isNotNull();
            assertThat(question.getSafeContent().at("/options/0/media/cdnUrl").asText()).isNotBlank();
            QuestionContracts.validate(question.getType(), QuestionContracts.View.SAFE, question.getSafeContent());
        });
        json.valueToTree(form);
        return statistics();
    }

    private long reviewQueries(Fixture fixture) {
        clearStatistics();
        var review = attempts.getAttemptReview(fixture.owner().getUsername(), fixture.attempt().getId(), true, true, true);
        assertThat(review.answers()).hasSize(fixture.questions().size());
        review.answers().forEach(answer -> {
            assertThat(answer.contentAvailability()).isEqualTo(QuestionContentAvailability.AVAILABLE);
            assertThat(answer.correctAnswerAvailability()).isEqualTo(QuestionContentAvailability.AVAILABLE);
            assertThat(answer.attachment()).isNotNull();
        });
        json.valueToTree(review);
        return statistics();
    }

    private void clearStatistics() {
        entityManager.flush(); entityManager.clear();
        emf.unwrap(SessionFactory.class).getStatistics().clear();
    }

    private long statistics() { return emf.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount(); }

    private Fixture fixture(int count, AttemptStatus status) {
        User owner = user(); User other = user();
        Quiz quiz = quiz(owner);
        var questions = new ArrayList<Question>();
        Attempt attempt = new Attempt(); attempt.setUser(owner); attempt.setQuiz(quiz);
        attempt.setStatus(status); attempt.setMode(AttemptMode.ALL_AT_ONCE);
        if (status == AttemptStatus.COMPLETED) attempt.setCompletedAt(Instant.parse("2026-10-19T12:00:00Z"));
        entityManager.persist(attempt);
        for (int i = 0; i < count; i++) {
            MediaAsset media = new MediaAsset(); media.setId(UUID.randomUUID());
            media.setType(MediaAssetType.IMAGE); media.setStatus(MediaAssetStatus.READY);
            media.setKey(media.getId() + ".png"); media.setMimeType("image/png");
            entityManager.persist(media);
            Tag tag = new Tag(); tag.setName("contract-" + UUID.randomUUID()); entityManager.persist(tag);
            Question question = new Question(); question.setType(QuestionType.MCQ_SINGLE); question.setDifficulty(Difficulty.EASY);
            question.setQuestionText("Example question");
            var content = (com.fasterxml.jackson.databind.node.ObjectNode) QuestionContracts.example(QuestionType.MCQ_SINGLE, QuestionContracts.View.AUTHOR);
            ((com.fasterxml.jackson.databind.node.ObjectNode) content.path("options").get(0)).putObject("media").put("assetId", media.getId().toString());
            question.setContent(content.toString()); question.setAttachmentAssetId(media.getId());
            question.getQuizId().add(quiz); question.getQuizId().add(quiz(owner)); question.getTags().add(tag);
            entityManager.persist(question); questions.add(question);
            if (status == AttemptStatus.COMPLETED) {
                Answer answer = new Answer(); answer.setAttempt(attempt); answer.setQuestion(question);
                answer.setResponse("{\"selectedOptionId\":\"a\"}"); answer.setIsCorrect(true); answer.setScore(1.0);
                answer.setAnsweredAt(Instant.parse("2026-10-19T11:00:00Z").plusSeconds(i));
                entityManager.persist(answer); attempt.getAnswers().add(answer);
            }
        }
        entityManager.flush();
        return new Fixture(owner, other, quiz, questions, attempt);
    }

    private User user() {
        User user = new User(); user.setUsername("contract-" + UUID.randomUUID());
        user.setEmail(user.getUsername() + "@example.com"); user.setHashedPassword("fixture-only");
        user.setActive(true); user.setEmailVerified(true); user.setRoles(new HashSet<>());
        entityManager.persist(user); return user;
    }

    private Quiz quiz(User owner) {
        Category category = new Category(); category.setName("contract-" + UUID.randomUUID()); entityManager.persist(category);
        Quiz quiz = new Quiz(); quiz.setCreator(owner); quiz.setCategory(category); quiz.setTitle("Example quiz");
        quiz.setVisibility(Visibility.PRIVATE); quiz.setStatus(QuizStatus.PUBLISHED); quiz.setDifficulty(Difficulty.EASY);
        quiz.setEstimatedTime(5); quiz.setIsRepetitionEnabled(false); quiz.setIsTimerEnabled(false);
        entityManager.persist(quiz); return quiz;
    }

    private record Fixture(User owner, User other, Quiz quiz, List<Question> questions, Attempt attempt) { }
}
