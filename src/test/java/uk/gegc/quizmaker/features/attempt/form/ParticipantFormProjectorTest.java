package uk.gegc.quizmaker.features.attempt.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gegc.quizmaker.features.attempt.application.ParticipantFormProjector;
import uk.gegc.quizmaker.features.attempt.domain.model.form.*;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.*;
import uk.gegc.quizmaker.features.question.application.contract.*;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;

import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.form.FixedFormFixtures.*;
import static uk.gegc.quizmaker.features.attempt.form.MaterializationFixtures.*;

class ParticipantFormProjectorTest {
    @ParameterizedTest
    @EnumSource(value = QuestionType.class, names = {"MCQ_SINGLE", "MCQ_MULTI", "TRUE_FALSE", "FILL_GAP", "ORDERING"})
    void supportedPresentationsContainOnlySafeFieldsAndExactStableIds(QuestionType type) throws Exception {
        var revision = quiz("safe", question("visible", type), question("hidden", QuestionType.MCQ_SINGLE));
        var command = command("form", revision, SHUFFLED, List.of(revision.items().get(0).id()));
        var result = project(command.form(), revision, MaterializationFixtures::presentation);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(command.form().items().get(0).id());
        assertThat(result.items().get(0).questionText()).isEqualTo("Published visible");
        assertThat(result.items().get(0).type()).isEqualTo(type);
        String serialized = new ObjectMapper().writeValueAsString(result);
        assertThat(serialized).doesNotContain("correct", "explanation", "hint", "answer", "sourceKey", "privateProvenance",
                "protected-source-marker", "protected-hint", "protected-explanation", "hidden", "ownerId", "quizRevisionId",
                "questionRevisionId", "sourceItemId", "binding", revision.items().get(1).id().toString());
        assertThat(result.toString() + result.items().get(0)).doesNotContain("Published visible", "protected");
        var original = revision.items().get(0).question();
        List<ParticipantContent.Option> actual = options(result.items().get(0).content());
        assertThat(actual).extracting(ParticipantContent.Option::id)
                .containsExactlyElementsOf(AnswerOptionOrder.order(command.form().id(), command.form().items().get(0).id(),
                        SHUFFLED, original, OptionOrderFixtures.layout(original)));
        assertThat(actual).allSatisfy(option -> assertThat(option.text()).isNotBlank());
    }

    @Test
    void fillGapWithoutOptionsKeepsGapIdsAndTextWithoutAnswers() {
        var content = (com.fasterxml.jackson.databind.node.ObjectNode) uk.gegc.quizmaker.features.question.application.QuestionContracts
                .example(QuestionType.FILL_GAP, uk.gegc.quizmaker.features.question.application.QuestionContracts.View.AUTHOR);
        content.remove("options");
        var revision = quiz("typing", question("gap", QuestionType.FILL_GAP, content));
        var form = command("typing-form", revision, AUTHORED).form();
        var result = project(form, revision, MaterializationFixtures::presentation);
        var gap = (ParticipantContent.FillGap) result.items().get(0).content();
        assertThat(gap.text()).isEqualTo(content.path("text").asText());
        assertThat(gap.gaps()).extracting(ParticipantContent.Gap::id).containsExactly(content.path("gaps").get(0).path("id").asInt());
        assertThat(gap.options()).isEmpty();
    }

    @Test
    void missingExtraAndWrongFamilyMaterialFailRatherThanReturningPartialOptions() {
        var revision = quiz("invalid", question("choice", QuestionType.MCQ_SINGLE));
        var form = command("invalid-form", revision, AUTHORED).form();
        List<Function<QuestionRevision, ParticipantContent>> invalid = List.of(q -> null,
                q -> new ParticipantContent.TrueFalse(), q -> new ParticipantContent.Choices(List.of()),
                q -> new ParticipantContent.Choices(List.of(new ParticipantContent.Option(id("foreign-option"), "Foreign"))));
        for (var projection : invalid) assertThatThrownBy(() -> project(form, revision, projection))
                .isInstanceOf(FormMaterializationException.class).hasMessage("Form materialization rejected: INVALID_MATERIAL").hasNoCause();
        assertThat(project(form, revision, MaterializationFixtures::presentation).items()).hasSize(1);
    }

    @Test
    void adapterFailureDiagnosticsCannotEscapeAndOldAdapterHasNoImplicitProjection() {
        var revision = quiz("failure", question("failure", QuestionType.TRUE_FALSE));
        var form = command("failure-form", revision, AUTHORED).form();
        assertThatThrownBy(() -> project(form, revision, q -> { throw new IllegalArgumentException("protected answer payload"); }))
                .hasMessage("Form materialization rejected: INVALID_MATERIAL").hasNoCause();
        VersionedResponseContract old = new VersionedResponseContract() {
            public ContractVersion version() { return new ContractVersion("fixture.old", 1); }
            public void validate(QuestionRevision q, com.fasterxml.jackson.databind.JsonNode response) { }
        };
        assertThatThrownBy(() -> old.participantContent(revision.items().get(0).question()))
                .isInstanceOf(ContractResolutionException.class).hasMessage("Question contract rejected: UNSUPPORTED_CONTRACT");
    }

    @Test
    void defensiveCopiesPreventMutationAndRepeatedQuestionOccurrencesRemainDistinct() {
        var q = question("repeated", QuestionType.MCQ_SINGLE);
        var revision = quiz("repeat", q, q);
        var form = command("repeat-form", revision, AUTHORED).form();
        var result = project(form, revision, MaterializationFixtures::presentation);
        assertThat(result.items()).hasSize(2).extracting(ParticipantForm.Item::id).doesNotHaveDuplicates();
        assertThatThrownBy(() -> result.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> options(result.items().get(0).content()).clear()).isInstanceOf(UnsupportedOperationException.class);
        var values = new ArrayList<>(List.of(new ParticipantContent.Option(id("a"), "Text")));
        var safe = new ParticipantContent.Choices(values); values.clear();
        assertThat(safe.options()).hasSize(1);
        assertThatThrownBy(() -> new ParticipantContent.Choices(Arrays.asList((ParticipantContent.Option) null)))
                .hasMessage("Invalid participant content");
        assertThatThrownBy(() -> new ParticipantContent.Choices(List.of(safe.options().get(0), safe.options().get(0))))
                .hasMessage("Invalid participant content");
    }

    @Test
    void foreignFormOrderAndUnretainedAttachmentAreRejected() {
        var revision = quiz("media", question("media", QuestionType.TRUE_FALSE));
        var original = revision.items().get(0).question();
        var attached = new QuestionRevision(original.id(), original.questionId(), 1, original.type(), original.difficulty(),
                original.questionText(), original.content(), original.hint(), original.explanation(), "https://example.invalid/mutable",
                null, original.createdAt(), original.options());
        var withMedia = quiz("media", attached);
        var form = command("media-form", withMedia, AUTHORED).form();
        assertThatThrownBy(() -> project(form, withMedia, MaterializationFixtures::presentation))
                .hasMessage("Form materialization rejected: UNSUPPORTED_MEDIA");
        assertThatThrownBy(() -> ParticipantFormProjector.project(form, quiz("foreign", original),
                new FormOptionOrder(form.id(), 1, AUTHORED, List.of()), Map.of()))
                .hasMessage("Form materialization rejected: INPUT_MISMATCH");
    }

    static ParticipantForm project(FixedForm form, QuizRevision revision, Function<QuestionRevision, ParticipantContent> projection) {
        var registry = registry(projection);
        var resolved = new HashMap<UUID, ResolvedQuestionContracts>();
        revision.items().forEach(item -> resolved.put(item.question().id(), registry.resolve(binding(item.question()))));
        var orders = new FormOptionOrder(form.id(), 1, SHUFFLED, form.items().stream().map(item -> {
            var q = revision.items().stream().filter(source -> source.id().equals(item.sourceItemId())).findFirst().orElseThrow().question();
            return new FormOptionOrder.Item(item.id(), item.sourceItemId(), binding(q),
                    AnswerOptionOrder.order(form.id(), item.id(), SHUFFLED, q, OptionOrderFixtures.layout(q)));
        }).toList());
        return ParticipantFormProjector.project(form, revision, orders, resolved);
    }

    static List<ParticipantContent.Option> options(ParticipantContent content) {
        if (content instanceof ParticipantContent.Choices choices) return choices.options();
        if (content instanceof ParticipantContent.FillGap gap) return gap.options();
        if (content instanceof ParticipantContent.Ordering ordering) return ordering.items();
        return List.of();
    }
}
