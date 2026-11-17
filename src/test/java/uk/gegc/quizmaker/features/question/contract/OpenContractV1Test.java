package uk.gegc.quizmaker.features.question.contract;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.application.StableResponseNormalizer;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl;
import uk.gegc.quizmaker.features.question.config.*;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.question.infra.handler.OpenQuestionHandler;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.question.contract.BaselineFamilyFixtures.question;

@Isolated("Characterizes the approved OPEN ROOT exception against unchanged legacy locale behavior")
class OpenContractV1Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenResponseContractV1 response = new OpenResponseContractV1();
    private final OpenScoringContractV1 scoring = new OpenScoringContractV1();

    static Stream<Arguments> golden() {
        return Stream.of(
                Arguments.of("The correct answer", "The correct answer", 1),
                Arguments.of("The Correct Answer", "the correct answer", 1),
                Arguments.of("The correct answer", "  The correct answer  ", 1),
                Arguments.of("The correct answer", "correct answer", 0),
                Arguments.of("Paris", "Rome", 0), Arguments.of("Paris", "", 0),
                Arguments.of("Paris", "   ", 0), Arguments.of("a  b", "a b", 0),
                Arguments.of("Москва", " мОсква ", 1), Arguments.of("café 😀", " CAFÉ 😀 ", 1),
                Arguments.of("x".repeat(10000), "x".repeat(10000), 1),
                Arguments.of("😀".repeat(4096), "😀".repeat(4096), 1));
    }

    @ParameterizedTest @MethodSource("golden")
    void currentPublicHandlerAndVersionedAdapterKeepValidationAndExactBinaryPoints(String key, String given, int earned) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            var content = JSON.createObjectNode().put("answer", key);
            var payload = JSON.createObjectNode().put("answer", given);
            var q = question(QuestionType.OPEN, content);
            var mutable = new Question(); mutable.setId(q.questionId()); mutable.setType(QuestionType.OPEN); mutable.setContent(q.content());
            var attempt = new Attempt();
            var before = new OpenQuestionHandler().handle(attempt, mutable,
                    new AnswerSubmissionRequest(q.questionId(), payload, false, false, false));
            response.validate(q, payload);
            assertThat(scoring.score(q, payload).earned()).isEqualByComparingTo(Integer.toString(earned))
                    .isEqualByComparingTo(Double.toString(before.getScore()));
            assertThat(scoring.score(q, payload).possible()).isEqualByComparingTo("1");
            assertThat(attempt.getAnswers()).hasSize(1);
            assertThat(StableResponseNormalizer.canonical(QuestionType.OPEN, payload).get("answer").textValue()).isEqualTo(given);
        } finally { Locale.setDefault(previous); }
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"answer\":null}", "{\"answer\":12}", "{\"answer\":true}", "[]", "null"})
    void malformedResponsesRejectAtThePublicLegacyAndNewBoundariesWithoutAnAnswer(String raw) throws Exception {
        var q = question(QuestionType.OPEN, JSON.createObjectNode().put("answer", "Paris"));
        var mutable = new Question(); mutable.setId(q.questionId()); mutable.setType(QuestionType.OPEN); mutable.setContent(q.content());
        var attempt = new Attempt(); var payload = JSON.readTree(raw);
        assertThatThrownBy(() -> new OpenQuestionHandler().handle(attempt, mutable,
                new AnswerSubmissionRequest(q.questionId(), payload, false, false, false))).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> response.validate(q, payload)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> scoring.score(q, payload)).isInstanceOf(QuestionContractException.class);
        assertThat(attempt.getAnswers()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"answer\":\"\"}", "{\"answer\":\"   \"}", "{\"answer\":null}", "{\"answer\":42}"})
    void invalidAuthorContentIsNotTreatedAsAWrongParticipantAnswer(String raw) throws Exception {
        var q = question(QuestionType.OPEN, JSON.readTree(raw));
        var payload = JSON.createObjectNode().put("answer", "Paris");
        assertThatThrownBy(() -> response.validate(q, payload)).isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> scoring.score(q, payload)).isInstanceOf(QuestionContractException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"en", "tr-TR", "lt-LT"})
    void rootComparisonIsStableAndDoesNotChangeTheLegacyHandler(String language) {
        var previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(language));
            var q = question(QuestionType.OPEN, JSON.createObjectNode().put("answer", "I"));
            var payload = JSON.createObjectNode().put("answer", " i ");
            assertThat(scoring.score(q, payload).earned()).isEqualByComparingTo("1");
            var mutable = new Question(); mutable.setId(q.questionId()); mutable.setType(QuestionType.OPEN); mutable.setContent(q.content());
            var before = new OpenQuestionHandler().handle(new Attempt(), mutable,
                    new AnswerSubmissionRequest(q.questionId(), payload, false, false, false));
            assertThat(before.getScore()).isEqualTo(language.equals("tr-TR") ? 0.0 : 1.0);
        } finally { Locale.setDefault(previous); }
    }

    @Test void realCatalogueIsAdditiveAndUnknownVersionsNeverFallBack() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.register(BaselineQuestionContractConfig.class, OpenQuestionContractConfig.class, QuestionContractRegistryImpl.class);
            context.refresh();
            var registry = context.getBean(QuestionContractRegistry.class);
            var q = question(QuestionType.OPEN, JSON.createObjectNode().put("answer", "Paris"));
            var binding = SupportedQuestionContracts.binding(q);
            assertThat(registry.resolve(binding).scoring().score(q, JSON.createObjectNode().put("answer", "Paris")).earned()).isEqualByComparingTo("1");
            assertThatThrownBy(() -> BaselineQuestionContracts.binding(q)).isInstanceOf(ContractResolutionException.class);
            assertThatThrownBy(() -> registry.resolve(new QuestionContractBinding(q.id(), binding.response(),
                    new ContractVersion(binding.scoring().id(), 2)))).isInstanceOf(ContractResolutionException.class);
        }
    }

    @Test void safeFormCannotExposeTheAnswerOrPrivateExtensionsAndHasNoOptionIdentity() throws Exception {
        var content = JSON.createObjectNode().put("answer", "private-answer").put("provenance", "private-source");
        var q = question(QuestionType.OPEN, content);
        assertThat(response.optionLayout(q).blocks()).isEmpty();
        assertThat(response.participantContent(q)).isEqualTo(new ParticipantContent.Open());
        assertThat(JSON.writeValueAsString(response.participantContent(q))).doesNotContain("private", "answer", "source", "hint", "explanation");
        var malformed = BaselineFamilyFixtures.options(q, List.of(new QuestionRevision.Option(UUID.randomUUID(), "foreign", 0)));
        assertThatThrownBy(() -> response.optionLayout(malformed)).isInstanceOf(QuestionContractException.class);
        var attached = new QuestionRevision(q.id(), q.questionId(), q.schemaVersion(), q.type(), q.difficulty(), q.questionText(),
                q.content(), q.hint(), q.explanation(), "https://example.invalid/private", UUID.randomUUID(), q.createdAt(), q.options());
        assertThatThrownBy(() -> response.participantContent(attached)).isInstanceOf(ContractResolutionException.class);
        assertThat(response.participantContentWithSeparateAttachment(attached)).isEqualTo(new ParticipantContent.Open());
        content.putObject("media").put("assetId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> response.participantContentWithSeparateAttachment(question(QuestionType.OPEN, content)))
                .isInstanceOf(ContractResolutionException.class);
    }

    @Test void boundsApplyToUnknownExtensionsAsWellAsAnswers() {
        var q = question(QuestionType.OPEN, JSON.createObjectNode().put("answer", "Paris"));
        var longAnswer = JSON.createObjectNode().put("answer", "x".repeat(10001));
        assertThatThrownBy(() -> response.validate(q, longAnswer)).isInstanceOf(QuestionContractException.class);
        var unknown = JSON.createObjectNode().put("answer", "Paris");
        var values = unknown.putArray("unknown"); for (int i = 0; i < 101; i++) values.add(i);
        assertThatThrownBy(() -> response.validate(q, unknown)).isInstanceOf(QuestionContractException.class);
        var tooManyBytes = JSON.createObjectNode().put("answer", "Paris");
        for (int i = 0; i < 8; i++) tooManyBytes.put("unknown" + i, "x".repeat(10000));
        assertThatThrownBy(() -> response.validate(q, tooManyBytes)).isInstanceOf(QuestionContractException.class);
        assertThat(new AcceptResponseRequest.Open("private-answer").toString()).doesNotContain("private-answer");
    }
}
