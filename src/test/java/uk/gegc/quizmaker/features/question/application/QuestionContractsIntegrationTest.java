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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
    @Autowired uk.gegc.quizmaker.features.quiz.application.validation.QuizPublishValidator publishValidator;
    @Autowired AiQuizGenerationService generation;
    @MockitoBean StructuredAiClient structuredAiClient;

    @Test
    void hotspotEditErrorsAreFieldAddressableAndAccessChecksRemainEnforced() throws Exception {
        Fixture fixture = fixture(1, AttemptStatus.IN_PROGRESS);
        Question question = fixture.questions().get(0);
        question.setType(QuestionType.HOTSPOT);
        question.setContent(QuestionContracts.example(QuestionType.HOTSPOT, QuestionContracts.View.AUTHOR).toString());
        var permission = entityManager.createQuery("select p from Permission p where p.permissionName = :name",
                        uk.gegc.quizmaker.features.user.domain.model.Permission.class)
                .setParameter("name", "QUESTION_UPDATE").getResultStream().findFirst().orElseGet(() -> {
                    var value = new uk.gegc.quizmaker.features.user.domain.model.Permission();
                    value.setPermissionName("QUESTION_UPDATE"); entityManager.persist(value); return value;
                });
        var role = new uk.gegc.quizmaker.features.user.domain.model.Role();
        role.setRoleName("hotspot-editor-" + UUID.randomUUID()); role.setPermissions(Set.of(permission));
        entityManager.persist(role);
        fixture.owner().getRoles().add(role); fixture.other().getRoles().add(role);
        User withoutPermission = user(); entityManager.flush();
        var body = json.createObjectNode().put("type", "HOTSPOT").put("difficulty", "EASY").put("questionText", "Select the striped shape.");
        var content = (com.fasterxml.jackson.databind.node.ObjectNode) QuestionContracts.example(QuestionType.HOTSPOT, QuestionContracts.View.AUTHOR);
        ((com.fasterxml.jackson.databind.node.ObjectNode) content.path("regions").get(0)).put("label", "private-canary".repeat(20));
        body.set("content", content);
        String path = "/api/v1/questions/" + question.getId();
        var response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(fixture.owner().getUsername()))
                        .contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).path("field").asText()).isEqualTo("content.regions[0].label");
        assertThat(response).doesNotContain("private-canary");
        content.remove("imageDescription");
        content.path("regions").forEach(region -> ((com.fasterxml.jackson.databind.node.ObjectNode) region).remove(List.of("label", "description")));
        for (User caller : List.of(fixture.other(), withoutPermission)) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(caller.getUsername()))
                            .contentType("application/json").content(json.writeValueAsBytes(body)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path)
                        .contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void manuallyCreatedHotspotsMayOmitDescriptionsEvenWhenPublished() {
        Fixture fixture = fixture(1, AttemptStatus.IN_PROGRESS);
        var request = new uk.gegc.quizmaker.features.question.api.dto.CreateQuestionRequest();
        request.setType(QuestionType.HOTSPOT); request.setDifficulty(Difficulty.EASY);
        request.setQuestionText("Select the striped shape."); request.setQuizIds(List.of(fixture.quiz().getId()));
        request.setContent(QuestionContracts.example(QuestionType.HOTSPOT, QuestionContracts.View.AUTHOR));
        UUID id = questions.createQuestion(fixture.owner().getUsername(), request);
        var update = new uk.gegc.quizmaker.features.question.api.dto.UpdateQuestionRequest();
        update.setType(QuestionType.HOTSPOT); update.setDifficulty(Difficulty.EASY);
        update.setQuestionText(request.getQuestionText());
        var content = (com.fasterxml.jackson.databind.node.ObjectNode) request.getContent().deepCopy();
        content.remove("imageDescription");
        content.path("regions").forEach(region -> ((com.fasterxml.jackson.databind.node.ObjectNode) region)
                .remove(List.of("label", "description")));
        update.setContent(content);
        var edited = questions.updateQuestion(fixture.owner().getUsername(), id, update);
        assertThat(edited.getContentAvailability()).isEqualTo(QuestionContentAvailability.AVAILABLE);
        assertThat(edited.getContent().has("imageDescription")).isFalse();
        // The same legacy shape is also valid on initial manual creation.
        request.setContent(content.deepCopy());
        UUID manualId = questions.createQuestion(fixture.owner().getUsername(), request);
        entityManager.flush(); entityManager.clear();
        var authentication = new UsernamePasswordAuthenticationToken(fixture.owner().getUsername(), null, List.of());
        var delivered = attempts.getShuffledQuestions(fixture.quiz().getId(), authentication);
        assertThat(delivered.stream().filter(q -> Set.of(id, manualId).contains(q.getId()))).hasSize(2).allSatisfy(q -> {
            assertThat(q.getContentAvailability()).isEqualTo(QuestionContentAvailability.AVAILABLE);
            assertThat(q.getSafeContent().has("imageDescription")).isFalse();
        });
        assertThatCode(() -> publishValidator.ensurePublishable(
                entityManager.find(Quiz.class, fixture.quiz().getId()))).doesNotThrowAnyException();
    }

    @Test
    void generatedHotspotCanBeManuallyChangedAndClearedBeforePublicationAndGrading() throws Exception {
        Fixture fixture = fixture(1, AttemptStatus.IN_PROGRESS);
        var candidate = StructuredQuestion.builder()
                .questionText("Select the striped shape.").type(QuestionType.HOTSPOT)
                .difficulty(Difficulty.EASY).confidence(0.95)
                .content(QuestionContracts.example(QuestionType.HOTSPOT, QuestionContracts.View.AUTHOR).toString())
                .build();
        // Fake only the provider port; conversion, strict candidate validation and persistence are real.
        when(structuredAiClient.generateQuestions(any())).thenReturn(
                StructuredQuestionResponse.builder().questions(List.of(candidate)).build());
        List<Question> generated = generation.generateQuestionsByType(
                "The diagram contains a circle and a striped oval.", QuestionType.HOTSPOT, 1, Difficulty.EASY);
        assertThat(generated).hasSize(1);
        Question question = generated.get(0);
        question.getQuizId().add(fixture.quiz());
        questionRepository.saveAndFlush(question);
        UUID id = question.getId();
        entityManager.clear();

        var update = new uk.gegc.quizmaker.features.question.api.dto.UpdateQuestionRequest();
        update.setType(QuestionType.HOTSPOT); update.setDifficulty(Difficulty.EASY);
        update.setQuestionText(candidate.getQuestionText());
        var content = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(
                questionRepository.findById(id).orElseThrow().getContent());
        assertThat(content.path("imageDescription").asText()).isNotBlank();
        content.path("regions").forEach(region -> {
            assertThat(region.path("label").asText()).isNotBlank();
            assertThat(region.path("description").asText()).isNotBlank();
        });
        content.put("imageDescription", "My own description.");
        content.path("regions").forEach(region -> ((com.fasterxml.jackson.databind.node.ObjectNode) region)
                .put("label", "My label").put("description", "My own region description."));
        update.setContent(content);
        var changed = questions.updateQuestion(fixture.owner().getUsername(), id, update);
        assertThat(changed.getContent().path("imageDescription").asText()).isEqualTo("My own description.");
        assertThat(changed.getContent().path("regions")).allSatisfy(region ->
                assertThat(region.path("label").asText()).isEqualTo("My label"));

        // Manual duplicates are valid; explicit null/blank values also clear optional descriptions.
        content.putNull("imageDescription");
        content.path("regions").forEach(region -> ((com.fasterxml.jackson.databind.node.ObjectNode) region)
                .putNull("label").put("description", "  "));
        questions.updateQuestion(fixture.owner().getUsername(), id, update);
        entityManager.flush(); entityManager.clear();
        assertThatCode(() -> publishValidator.ensurePublishable(
                entityManager.find(Quiz.class, fixture.quiz().getId()))).doesNotThrowAnyException();
        var delivered = attempts.getShuffledQuestions(fixture.quiz().getId(),
                new UsernamePasswordAuthenticationToken(fixture.owner().getUsername(), null, List.of()));
        var safe = delivered.stream().filter(q -> q.getId().equals(id)).findFirst().orElseThrow();
        assertThat(safe.getContentAvailability()).isEqualTo(QuestionContentAvailability.AVAILABLE);
        assertThat(safe.getSafeContent().has("imageDescription")).isFalse();
        safe.getSafeContent().path("regions").forEach(region ->
                assertThat(region.has("label") || region.has("description") || region.has("correct")).isFalse());
        var answer = attempts.submitAnswer(fixture.owner().getUsername(), fixture.attempt().getId(),
                new AnswerSubmissionRequest(id, json.createObjectNode().put("selectedRegionId", 2), true, false, false));
        assertThat(answer.isCorrect()).isTrue();
        assertThat(answer.score()).isEqualTo(1.0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"changed", "omitted", "null", "blank"})
    void manualDescriptionEditsKeepRecordedAttemptDataAndCurrentContentReview(String edit) throws Exception {
        Fixture fixture = hotspotFixture(1);
        UUID questionId = fixture.questions().get(0).getId();
        UUID attemptId = fixture.attempt().getId();
        UUID answerId = fixture.attempt().getAnswers().get(0).getId();
        fixture.attempt().setTotalScore(1.0);
        entityManager.flush(); entityManager.clear();
        var before = attempts.getAttemptReview(fixture.owner().getUsername(), attemptId, true, true, true);
        var answerBefore = before.answers().get(0);
        var update = new uk.gegc.quizmaker.features.question.api.dto.UpdateQuestionRequest();
        update.setType(QuestionType.HOTSPOT); update.setDifficulty(Difficulty.EASY);
        update.setQuestionText(fixture.questions().get(0).getQuestionText());
        var content = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(
                questionRepository.findById(questionId).orElseThrow().getContent());
        switch (edit) {
            case "changed" -> {
                content.put("imageDescription", "Edited after completion.");
                content.path("regions").forEach(region -> ((com.fasterxml.jackson.databind.node.ObjectNode) region)
                        .put("label", "Edited label").put("description", "Edited region."));
            }
            case "omitted" -> {
                content.remove("imageDescription");
                content.path("regions").forEach(region -> ((com.fasterxml.jackson.databind.node.ObjectNode) region)
                        .remove(List.of("label", "description")));
            }
            case "null" -> {
                content.putNull("imageDescription");
                content.path("regions").forEach(region -> ((com.fasterxml.jackson.databind.node.ObjectNode) region)
                        .putNull("label").putNull("description"));
            }
            case "blank" -> {
                content.put("imageDescription", "  ");
                content.path("regions").forEach(region -> ((com.fasterxml.jackson.databind.node.ObjectNode) region)
                        .put("label", "  ").put("description", "  "));
            }
        }
        update.setContent(content);
        questions.updateQuestion(fixture.owner().getUsername(), questionId, update);
        entityManager.flush(); entityManager.clear();

        var after = attempts.getAttemptReview(fixture.owner().getUsername(), attemptId, true, true, true);
        var answerAfter = after.answers().get(0);
        assertThat(after).usingRecursiveComparison().ignoringFields("answers").isEqualTo(before);
        assertThat(answerAfter).usingRecursiveComparison().ignoringFields("questionSafeContent").isEqualTo(answerBefore);
        assertThat(answerAfter.contentAvailability()).isEqualTo(QuestionContentAvailability.AVAILABLE);
        // Keep the existing live-content review policy; immutable historical forms are deferred to #818.
        if (edit.equals("changed")) {
            assertThat(answerAfter.questionSafeContent().path("imageDescription").asText()).isEqualTo("Edited after completion.");
            answerAfter.questionSafeContent().path("regions").forEach(region ->
                    assertThat(region.path("label").asText()).isEqualTo("Edited label"));
        } else {
            assertThat(answerAfter.questionSafeContent().has("imageDescription")).isFalse();
            answerAfter.questionSafeContent().path("regions").forEach(region ->
                    assertThat(region.has("label") || region.has("description")).isFalse());
        }
        Attempt storedAttempt = attemptRepository.findById(attemptId).orElseThrow();
        assertThat(storedAttempt.getStatus()).isEqualTo(AttemptStatus.COMPLETED);
        assertThat(storedAttempt.getTotalScore()).isEqualTo(1.0);
        assertThat(storedAttempt.getStartedAt()).isEqualTo(before.startedAt());
        assertThat(storedAttempt.getCompletedAt()).isEqualTo(before.completedAt());
        assertThat(answerRepository.countByAttemptId(attemptId)).isEqualTo(1);
        Answer storedAfter = answerRepository.findById(answerId).orElseThrow();
        assertThat(json.readTree(storedAfter.getResponse())).isEqualTo(answerBefore.userResponse());
        assertThat(storedAfter.getIsCorrect()).isEqualTo(answerBefore.isCorrect());
        assertThat(storedAfter.getScore()).isEqualTo(answerBefore.score());
        assertThat(storedAfter.getAnsweredAt()).isEqualTo(answerBefore.answeredAt());
    }

    @Test
    void describedHotspotFormsAndReviewsUseBoundedQueriesForTwoAndTenParents(org.junit.jupiter.api.TestReporter reporter) {
        Fixture small = hotspotFixture(2);
        Fixture large = hotspotFixture(10);
        entityManager.flush();
        long smallAuthor = hotspotAuthorQueries(small);
        long largeAuthor = hotspotAuthorQueries(large);
        assertThat(largeAuthor).isEqualTo(smallAuthor).isLessThanOrEqualTo(9);
        long smallForm = hotspotFormQueries(small);
        long largeForm = hotspotFormQueries(large);
        assertThat(largeForm).isEqualTo(smallForm).isLessThanOrEqualTo(2);
        long smallReview = hotspotReviewQueries(small);
        long largeReview = hotspotReviewQueries(large);
        assertThat(largeReview).isEqualTo(smallReview).isLessThanOrEqualTo(5);
        reporter.publishEntry("hotspot_queries_for_2_and_10_parents", "author=" + smallAuthor + "/" + largeAuthor
                + ", form=" + smallForm + "/" + largeForm
                + ", review=" + smallReview + "/" + largeReview);
    }

    private long hotspotAuthorQueries(Fixture fixture) {
        clearStatistics();
        var page = questions.listQuestions(fixture.quiz().getId(), PageRequest.of(0, 100),
                new UsernamePasswordAuthenticationToken(fixture.owner().getUsername(), null, List.of()));
        assertThat(page.getContent()).hasSize(fixture.questions().size());
        page.forEach(question -> {
            assertThat(question.getContent().path("imageDescription").asText()).isNotBlank();
            assertThat(question.getQuizIds()).hasSize(2);
            assertThat(question.getTagIds()).hasSize(1);
        });
        json.valueToTree(page);
        return statistics();
    }

    private Fixture hotspotFixture(int count) {
        Fixture fixture = fixture(count, AttemptStatus.COMPLETED);
        for (Question question : fixture.questions()) {
            question.setType(QuestionType.HOTSPOT);
            question.setContent(QuestionContracts.example(QuestionType.HOTSPOT, QuestionContracts.View.AUTHOR).toString());
        }
        for (Answer answer : fixture.attempt().getAnswers()) answer.setResponse("{\"selectedRegionId\":2}");
        return fixture;
    }

    private long hotspotFormQueries(Fixture fixture) {
        clearStatistics();
        var form = safeMapper.toSafeDtoList(questionRepository.findAllByQuizId_IdOrderById(fixture.quiz().getId()));
        assertThat(form).hasSize(fixture.questions().size());
        form.forEach(question -> {
            assertThat(question.getSafeContent().path("imageDescription").asText()).isNotBlank();
            assertThat(question.getSafeContent().path("regions").get(0).path("label").asText()).isEqualTo("Circle");
            assertThat(question.getSafeContent().toString()).doesNotContain("correct");
        });
        json.valueToTree(form);
        return statistics();
    }

    private long hotspotReviewQueries(Fixture fixture) {
        clearStatistics();
        var review = attempts.getAttemptReview(fixture.owner().getUsername(), fixture.attempt().getId(), true, true, true);
        assertThat(review.answers()).hasSize(fixture.questions().size());
        review.answers().forEach(answer -> {
            assertThat(answer.questionSafeContent().path("imageDescription").asText()).isNotBlank();
            assertThat(answer.questionSafeContent().path("regions").get(1).path("label").asText()).isEqualTo("Striped oval");
            assertThat(answer.questionSafeContent().toString()).doesNotContain("correct");
        });
        json.valueToTree(review);
        return statistics();
    }

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = QuestionType.class,
            names = {"MCQ_SINGLE", "MCQ_MULTI", "OPEN", "FILL_GAP", "HOTSPOT", "COMPLIANCE", "MATCHING"})
    void unanswerableStoredQuestionBlocksCompletionAndIsUnavailableInReads(QuestionType type) {
        var fixture = fixture(1, AttemptStatus.IN_PROGRESS);
        var question = fixture.questions().get(0);
        question.setType(type);
        question.setContent(QuestionContractTestSupport.structurallyValidButUnanswerable(type).toString());
        entityManager.flush(); entityManager.clear();
        var page = questions.listQuestions(fixture.quiz().getId(), PageRequest.of(0, 10),
                new UsernamePasswordAuthenticationToken(fixture.owner().getUsername(), null, List.of()));
        assertThat(page.getContent().get(0).getContentAvailability()).isEqualTo(QuestionContentAvailability.UNAVAILABLE);
        assertThatThrownBy(() -> attempts.completeAttempt(fixture.owner().getUsername(), fixture.attempt().getId()))
                .isInstanceOf(QuestionContentUnavailableException.class);
        var stored = attemptRepository.findById(fixture.attempt().getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(stored.getCompletedAt()).isNull();
        assertThat(answerRepository.countByAttemptId(stored.getId())).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = QuestionType.class,
            names = {"MCQ_SINGLE", "MCQ_MULTI", "OPEN", "FILL_GAP", "HOTSPOT", "COMPLIANCE", "MATCHING"})
    void unanswerableStoredQuestionDoesNotRewriteRecordedGradesOrRevealWithheldFields(QuestionType type) {
        var fixture = fixture(2, AttemptStatus.COMPLETED);
        var damaged = fixture.questions().get(0);
        damaged.setType(type);
        damaged.setContent(QuestionContractTestSupport.structurallyValidButUnanswerable(type).toString());
        fixture.attempt().getAnswers().stream().filter(a -> a.getQuestion() == damaged).forEach(answer ->
                answer.setResponse(QuestionContracts.example(type, QuestionContracts.View.RESPONSE).toString()));
        entityManager.flush(); entityManager.clear();
        var review = attempts.getAttemptReview(fixture.owner().getUsername(), fixture.attempt().getId(), true, true, true);
        var unavailable = review.answers().stream().filter(a -> a.questionId().equals(damaged.getId())).findFirst().orElseThrow();
        assertThat(unavailable.contentAvailability()).isEqualTo(QuestionContentAvailability.UNAVAILABLE);
        assertThat(unavailable.correctAnswerAvailability()).isEqualTo(QuestionContentAvailability.UNAVAILABLE);
        assertThat(unavailable.questionSafeContent()).isNull();
        assertThat(unavailable.correctAnswer()).isNull();
        assertThat(unavailable.score()).isEqualTo(1.0);
        assertThat(unavailable.isCorrect()).isTrue();
        assertThat(review.totalScore()).isEqualTo(2.0);
        assertThat(review.answers().get(1).contentAvailability()).isEqualTo(QuestionContentAvailability.AVAILABLE);
        var withheld = attempts.getAttemptReview(fixture.owner().getUsername(), fixture.attempt().getId(), false, false, false);
        withheld.answers().forEach(answer -> {
            assertThat(answer.contentAvailability()).isEqualTo(QuestionContentAvailability.NOT_INCLUDED);
            assertThat(answer.responseAvailability()).isEqualTo(QuestionContentAvailability.NOT_INCLUDED);
            assertThat(answer.correctAnswerAvailability()).isEqualTo(QuestionContentAvailability.NOT_INCLUDED);
            assertThat(answer.correctAnswer()).isNull();
            assertThat(answer.explanation()).isNull();
            assertThat(answer.questionSafeContent()).isNull();
        });
    }

    @Test
    void ownershipPrivateVisibilityAndCreatePermissionRemainEnforcedWithDamagedContent() throws Exception {
        var fixture = fixture(1, AttemptStatus.IN_PROGRESS);
        fixture.questions().get(0).setContent("{\"options\":null}");
        entityManager.flush();
        var other = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(fixture.other().getUsername());
        String path = "/api/v1/attempts/" + fixture.attempt().getId();
        var body = json.createObjectNode().put("questionId", fixture.questions().get(0).getId().toString()).put("contractVersion", 1);
        body.set("response", QuestionContracts.example(QuestionType.MCQ_SINGLE, QuestionContracts.View.RESPONSE));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/answers").with(other)
                        .contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/complete").with(other))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path + "/current-question").with(other))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/questions")
                        .param("quizId", fixture.quiz().getId().toString()).with(other))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var create = json.createObjectNode().put("type", "TRUE_FALSE").put("difficulty", "EASY").put("questionText", "Example question");
        create.putObject("content").put("answer", true);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/questions").with(other)
                        .contentType("application/json").content(json.writeValueAsBytes(create)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        assertThat(answerRepository.countByAttemptId(fixture.attempt().getId())).isZero();
        assertThat(attemptRepository.findById(fixture.attempt().getId()).orElseThrow().getStatus()).isEqualTo(AttemptStatus.IN_PROGRESS);
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
        Quiz quiz = new Quiz(); quiz.setCreator(owner); quiz.setCategory(category);
        // Fixtures must respect the migration-backed unique creator/title constraint.
        quiz.setTitle("Example quiz " + UUID.randomUUID());
        quiz.setVisibility(Visibility.PRIVATE); quiz.setStatus(QuizStatus.PUBLISHED); quiz.setDifficulty(Difficulty.EASY);
        quiz.setEstimatedTime(5); quiz.setIsRepetitionEnabled(false); quiz.setIsTimerEnabled(false);
        entityManager.persist(quiz); return quiz;
    }

    private record Fixture(User owner, User other, Quiz quiz, List<Question> questions, Attempt attempt) { }
}
