package uk.gegc.quizmaker.features.question.contract;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import uk.gegc.quizmaker.features.question.application.QuestionContractRegistry;
import uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract;
import uk.gegc.quizmaker.features.question.application.contract.VersionedScoringContract;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Registry contract doubles only. These are not the family algorithms owned by #701. */
final class ContractFixtures {
    static final UUID OWNER = id(100);
    static final Instant CREATED = Instant.parse("2026-10-24T00:00:00Z");

    static UUID id(long number) { return new UUID(0, number); }

    static QuestionContractBinding binding(long question, int version) {
        return new QuestionContractBinding(id(question), new ContractVersion("fixture.response", version),
                new ContractVersion("fixture.scoring", version));
    }

    static QuestionRevision question(long revision, String content) {
        // Multiple revisions intentionally share one logical question identity.
        return new QuestionRevision(id(revision), id(200), 1, QuestionType.TRUE_FALSE, Difficulty.EASY,
                "Registry contract fixture", content, null, null, null, null, CREATED, List.of());
    }

    static QuizRevision quiz(long revision, QuestionRevision... questions) {
        var items = new java.util.ArrayList<QuizRevision.Item>();
        for (int index = 0; index < questions.length; index++) {
            items.add(new QuizRevision.Item(id(1000 + index), index, questions[index]));
        }
        return new QuizRevision(id(revision), id(300), OWNER, 1, "Contract fixture", null, CREATED, items);
    }

    static QuestionContractRegistry registry() {
        return new QuestionContractRegistryImpl(List.of(response(1), response(2)),
                List.of(scoring(1), scoring(2)), new SimpleMeterRegistry());
    }

    static VersionedResponseContract response(int version) {
        return new VersionedResponseContract() {
            public ContractVersion version() { return new ContractVersion("fixture.response", version); }
            public void validate(QuestionRevision question, JsonNode response) {
                if (question == null || response == null || !response.isBoolean()) {
                    throw new IllegalArgumentException("Invalid fixture response");
                }
            }
        };
    }

    static VersionedScoringContract scoring(int version) {
        return new VersionedScoringContract() {
            public ContractVersion version() { return new ContractVersion("fixture.scoring", version); }
            public ContractScore score(QuestionRevision question, JsonNode response) {
                boolean match = response.booleanValue() == (version == 1);
                return new ContractScore(match ? BigDecimal.ONE : BigDecimal.ZERO, BigDecimal.ONE);
            }
        };
    }
}
