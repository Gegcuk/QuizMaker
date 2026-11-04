package uk.gegc.quizmaker.features.attempt.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import uk.gegc.quizmaker.features.attempt.application.FixedFormMaterializer.Command;
import uk.gegc.quizmaker.features.attempt.application.impl.QuestionSelectionServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.AuthoredQuestionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.contract.*;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static uk.gegc.quizmaker.features.attempt.form.FixedFormFixtures.*;

/** Valid source shapes and explicit component adapters; not #701 family scoring or publication proof. */
final class MaterializationFixtures {
    static final List<QuestionType> FAMILIES = List.of(QuestionType.MCQ_SINGLE, QuestionType.MCQ_MULTI,
            QuestionType.TRUE_FALSE, QuestionType.FILL_GAP, QuestionType.ORDERING);
    static final AnswerOrderingPolicy AUTHORED = new AnswerOrderingPolicy(AnswerOrder.AUTHORED, 1);
    static final AnswerOrderingPolicy SHUFFLED = new AnswerOrderingPolicy(AnswerOrder.SHUFFLED_PER_ATTEMPT, 1);
    static final QuestionOrderingPolicy QUESTION_ORDER = new QuestionOrderingPolicy(QuestionOrder.AUTHORED, 1);

    static QuestionRevision question(String key, QuestionType type) {
        ObjectNode content = (ObjectNode) QuestionContracts.example(type, QuestionContracts.View.AUTHOR);
        content.put("privateProvenance", "protected-source-marker");
        return question(key, type, content);
    }

    static QuestionRevision question(String key, QuestionType type, ObjectNode content) {
        QuestionContracts.validate(type, QuestionContracts.View.AUTHOR, content);
        List<String> keys = switch (type) {
            case MCQ_SINGLE, MCQ_MULTI -> values(content.path("options"), node -> node.path("id").asText());
            case ORDERING -> values(content.path("items"), node -> node.path("id").asText());
            case FILL_GAP -> IntStream.range(0, content.path("options").size()).mapToObj(Integer::toString).toList();
            default -> List.of();
        };
        return new QuestionRevision(id(key), id("draft-" + key), 1, type, Difficulty.EASY,
                "Published " + key, content.toString(), "protected-hint", "protected-explanation", null, null,
                Instant.parse("2026-10-28T12:00:00Z"), IntStream.range(0, keys.size())
                .mapToObj(n -> new QuestionRevision.Option(id(key + "-option-" + n), keys.get(n), n * 2)).toList());
    }

    static QuizRevision quiz(String key, QuestionRevision... questions) {
        return new QuizRevision(id(key), id("mutable-quiz"), OWNER, 1, "Published quiz", "private quiz description",
                Instant.parse("2026-10-28T12:00:00Z"), IntStream.range(0, questions.length)
                .mapToObj(n -> new QuizRevision.Item(id(key + "-source-" + n), n * 3, questions[n])).toList());
    }

    static Command command(String key, QuizRevision revision, AnswerOrderingPolicy policy, List<UUID> selected) {
        var input = new QuestionSelection.Input(1, revision.id(), 1, QuestionSelection.Configuration.manualSet(selected),
                revision.items().stream().map(item -> new QuestionSelection.Item(item.id(), item.authoredPosition())).toList(), List.of());
        var selection = new QuestionSelectionServiceImpl().select(input);
        var ordering = AuthoredQuestionOrder.order(new AuthoredQuestionOrder.Input(1, revision.id(), 1,
                QUESTION_ORDER, revision.items().stream().map(item -> new AuthoredQuestionOrder.Item(item.id(), item.authoredPosition())).toList(),
                selection.itemIds()));
        var form = new FixedForm(id(key), 1, revision.id(), IntStream.range(0, ordering.itemIds().size())
                .mapToObj(n -> new FixedForm.Item(id(key + "-item-" + n), ordering.itemIds().get(n), n * 2)).toList());
        return new Command(form, selection, ordering, policy);
    }

    static Command command(String key, QuizRevision revision, AnswerOrderingPolicy policy) {
        return command(key, revision, policy, revision.items().stream().map(QuizRevision.Item::id).toList());
    }

    static ContractVersion version(QuestionType type) {
        return new ContractVersion("fixture.material." + type.name().toLowerCase(Locale.ROOT).replace('_', '-'), 1);
    }

    static QuestionContractBinding binding(QuestionRevision question) {
        return new QuestionContractBinding(question.id(), version(question.type()), new ContractVersion("fixture.material.scoring", 1));
    }

    static QuestionContractRegistry registry(Function<QuestionRevision, ParticipantContent> projection) {
        return registry(OptionOrderFixtures::layout, projection);
    }

    static QuestionContractRegistry registry(Function<QuestionRevision, OptionLayout> layout,
                                            Function<QuestionRevision, ParticipantContent> projection) {
        var responses = FAMILIES.stream().map(type -> (VersionedResponseContract) new VersionedResponseContract() {
            public ContractVersion version() { return MaterializationFixtures.version(type); }
            public void validate(QuestionRevision question, JsonNode response) {
                QuestionContracts.validate(type, QuestionContracts.View.RESPONSE, response);
            }
            public OptionLayout optionLayout(QuestionRevision question) { return layout.apply(question); }
            public ParticipantContent participantContent(QuestionRevision question) { return projection.apply(question); }
        }).toList();
        var scoring = new VersionedScoringContract() {
            public ContractVersion version() { return new ContractVersion("fixture.material.scoring", 1); }
            public ContractScore score(QuestionRevision question, JsonNode response) {
                throw new AssertionError("Materialization must not grade a response");
            }
        };
        return new QuestionContractRegistryImpl(responses, List.of(scoring), new SimpleMeterRegistry());
    }

    /** Fixture contract explicitly maps its own source keys; the production materializer never infers them. */
    static ParticipantContent presentation(QuestionRevision question) {
        var source = QuestionContracts.read(question.type(), QuestionContracts.View.AUTHOR, question.content());
        List<ParticipantContent.Option> options = question.options().stream().map(option -> {
            String text = switch (question.type()) {
                case MCQ_SINGLE, MCQ_MULTI -> values(source.path("options"), Function.identity()).stream()
                        .filter(node -> node.path("id").asText().equals(option.sourceKey())).findFirst().orElseThrow().path("text").asText();
                case ORDERING -> values(source.path("items"), Function.identity()).stream()
                        .filter(node -> node.path("id").asText().equals(option.sourceKey())).findFirst().orElseThrow().path("text").asText();
                case FILL_GAP -> source.path("options").get(Integer.parseInt(option.sourceKey())).asText();
                default -> throw new AssertionError("Unexpected fixture option");
            };
            return new ParticipantContent.Option(option.id(), text);
        }).toList();
        return switch (question.type()) {
            case MCQ_SINGLE, MCQ_MULTI -> new ParticipantContent.Choices(options);
            case TRUE_FALSE -> new ParticipantContent.TrueFalse();
            case FILL_GAP -> new ParticipantContent.FillGap(source.path("text").asText(),
                    values(source.path("gaps"), gap -> new ParticipantContent.Gap(gap.path("id").asInt())), options);
            case ORDERING -> new ParticipantContent.Ordering(options);
            default -> throw new AssertionError("Unsupported fixture family");
        };
    }

    private static <T> List<T> values(JsonNode array, Function<JsonNode, T> map) {
        var result = new ArrayList<T>();
        array.forEach(node -> result.add(map.apply(node)));
        return List.copyOf(result);
    }
}
