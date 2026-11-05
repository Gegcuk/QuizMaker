package uk.gegc.quizmaker.features.quiz.revision;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.media.application.RetainedImageService.ImageReference;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.*;
import uk.gegc.quizmaker.features.quiz.infra.publication.PublicationContent;

import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PublicationContentTest {
    final PublicationContent format = new PublicationContent(new QuestionHandlerFactory(List.of(new TrueFalseHandler(), new McqSingleHandler())), new MediaStorageProperties());
    final UUID quiz = UUID.randomUUID(), owner = UUID.randomUUID(), question = UUID.randomUUID();
    final Draft draft = new Draft(quiz, owner, "Quiz", null, "PRIVATE", 5);
    final Instant now = Instant.parse("2026-11-05T12:00:00Z");

    @Test void whitespaceAndObjectKeyOrderDoNotRepublishUnchangedContent() {
        var a = q("{\"answer\":true,\"extension\":{\"a\":1,\"b\":2}}", null);
        var b = q("{ \"extension\" : { \"b\":2,\"a\":1 }, \"answer\" : true }", null);
        var first = format.materialize(draft, List.of(a), Map.of(), now);
        var second = format.materialize(draft, List.of(b), Map.of(), now.plusSeconds(60));
        assertThat(second.hash()).isEqualTo(first.hash());
        assertThat(second.revision().id()).isNotEqualTo(first.revision().id());
        assertThat(second.revision().items().get(0).question().content()).isEqualTo(first.revision().items().get(0).question().content());
    }

    @Test void exactNumbersAndAuthoredArrayOrderAreContent() {
        var first = format.materialize(draft, List.of(q("{\"answer\":true,\"n\":1.234567890123456789,\"order\":[1,2]}", null)), Map.of(), now);
        var changed = format.materialize(draft, List.of(q("{\"answer\":true,\"n\":1.234567890123456788,\"order\":[1,2]}", null)), Map.of(), now);
        var reordered = format.materialize(draft, List.of(q("{\"answer\":true,\"n\":1.234567890123456789,\"order\":[2,1]}", null)), Map.of(), now);
        assertThat(first.revision().items().get(0).question().content()).contains("1.234567890123456789");
        assertThat(first.hash()).isNotIn(changed.hash(), reordered.hash());
    }

    @Test void savedVersionIdentityAndExactObjectReplaceMutableImageUrls() {
        UUID asset = UUID.randomUUID();
        var q = q("{\"answer\":true,\"media\":{\"assetId\":\"" + asset + "\",\"cdnUrl\":\"https://example.invalid/mutable\"}}", asset);
        var retained = new ImageReference(UUID.randomUUID(), UUID.randomUUID(), asset, "retained-images/A", "image/png", 10, 640, 480);
        var result = format.materialize(draft, List.of(q), Map.of(question, Map.of(asset, retained)), now);
        assertThat(format.references(List.of(q))).containsEntry(question, Set.of(asset));
        assertThat(result.revision().items().get(0).question().content()).contains("retained-images/A").doesNotContain("example.invalid");
        assertThat(result.images()).singleElement().satisfies(i -> assertThat(i.versionId()).isEqualTo(retained.versionId()));
        UUID nextAsset = UUID.randomUUID();
        var replacement = new ImageReference(UUID.randomUUID(), UUID.randomUUID(), nextAsset, "retained-images/B", "image/png", 10, 640, 480);
        assertThat(format.materialize(draft, List.of(q("{\"answer\":true}", nextAsset)),
                Map.of(question, Map.of(nextAsset, replacement)), now).hash()).isNotEqualTo(result.hash());
    }

    @Test void malformedOrUnboundMediaAndInvalidQuestionsFailWithoutFetching() {
        for (String raw : List.of("{\"answer\":true,\"media\":{\"cdnUrl\":\"https://example.invalid\"}}",
                "{\"answer\":true,\"media\":{\"assetId\":\"bad-id\"}}", "{\"answer\":\"not-boolean\"}"))
            assertThatThrownBy(() -> format.references(List.of(q(raw, null)))).isInstanceOf(PublicationException.class);
        assertThatThrownBy(() -> format.materialize(draft, List.of(q("{\"answer\":true,\"imageUrl\":\"https://example.invalid\"}", null)), Map.of(), now))
                .isInstanceOf(PublicationException.class);
    }

    @Test void optionIdentitiesDoNotComeFromArrayPositions() {
        var value = new QuizPublication.Question(question, QuestionType.MCQ_SINGLE, Difficulty.EASY, "Question",
                "{\"options\":[{\"id\":\"b\",\"text\":\"B\",\"correct\":true},{\"id\":\"a\",\"text\":\"A\"}]}", null, null, null, null);
        var snapshot = format.materialize(draft, List.of(value), Map.of(), now).revision().items().get(0).question();
        assertThat(snapshot.options()).extracting(QuestionRevision.Option::sourceKey).containsExactly("b", "a");
        assertThat(snapshot.options()).extracting(QuestionRevision.Option::id).doesNotHaveDuplicates();
    }

    @Test void commandRejectsMissingDuplicateUnknownAndNegativeInputsAndCopiesOrder() {
        var order = new ArrayList<>(List.of(question)); UUID key = UUID.randomUUID();
        var command = new Command(1, quiz, key, 0, order); order.clear();
        assertThat(command.questionOrder()).containsExactly(question);
        assertThatThrownBy(() -> new Command(2, quiz, key, 0, List.of(question))).isInstanceOf(PublicationException.class);
        assertThatThrownBy(() -> new Command(1, quiz, key, -1, List.of(question))).isInstanceOf(PublicationException.class);
        assertThatThrownBy(() -> new Command(1, quiz, key, 0, List.of(question, question))).isInstanceOf(PublicationException.class);
        assertThatThrownBy(() -> new Command(1, quiz, key, 0, List.of())).isInstanceOf(PublicationException.class);
        assertThatThrownBy(() -> new Command(1, null, key, 0, List.of(question))).isInstanceOf(PublicationException.class);
        assertThat(PublicationContent.requestHash(command)).isNotEqualTo(PublicationContent.requestHash(new Command(1, quiz, key, 1, List.of(question))));
    }
    private QuizPublication.Question q(String content, UUID image) {
        return new QuizPublication.Question(question, QuestionType.TRUE_FALSE, Difficulty.EASY, "Question", content, null, null, null, image);
    }
}
