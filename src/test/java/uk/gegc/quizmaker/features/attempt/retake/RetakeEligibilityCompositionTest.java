package uk.gegc.quizmaker.features.attempt.retake;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.attempt.application.impl.RetakeEligibilityServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.features.result.domain.model.AutomaticResult;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.retake.RetakeFixtures.*;
import static uk.gegc.quizmaker.features.attempt.retake.RetakeEligibilityTest.*;

class RetakeEligibilityCompositionTest {
    @Test void submittedLifecycleConsumesBeforeResultExistsAndResultDoesNotRestartCooldown() {
        // Real delivered value contracts; this is not proof of durable #549 start consumption.
        var id = UUID.randomUUID();
        var submitted = new AttemptLifecycle.State(1, AttemptLifecycle.Execution.SUBMITTED, AttemptLifecycle.AnswerCollection.COMPLETE);
        var fixture = new Fixture(); fixture.maximum = 1L;
        var pending = StartFact.of(id, SCOPE, NOW.minusSeconds(120), NOW.minusSeconds(60), submitted,
                new ResultFact(1, id, ResultState.PENDING));
        fixture.starts = List.of(pending); denied(fixture, Reason.LIMIT_EXHAUSTED);

        var item = new AutomaticResult.Item(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                new ContractVersion("response", 1), new ContractVersion("scoring", 1), 1);
        var result = new AutomaticResult(UUID.randomUUID(), 1, UUID.randomUUID(), id, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), AutomaticResult.Score.calculate(1, 1, 7000), NOW, List.of(item));
        fixture.starts = List.of(StartFact.of(id, SCOPE, pending.startedAt(), pending.terminalAt(), submitted, ResultFact.of(result)));
        denied(fixture, Reason.LIMIT_EXHAUSTED);
        fixture.maximum = 2L; fixture.cooldown = Duration.ofSeconds(60);
        allowed(fixture, NOW); // Grading at NOW cannot move terminalAt + cooldown beyond NOW.
    }

    @Test void lifecycleCreationIsNotACommittedStartAndActiveLifecyclePreventsAnother() {
        var fixture = new Fixture();
        fixture.starts = List.of(StartFact.of(UUID.randomUUID(), SCOPE, NOW, null,
                new AttemptLifecycle.State(1, AttemptLifecycle.Execution.CREATED, AttemptLifecycle.AnswerCollection.NOT_STARTED), NO_RESULT));
        denied(fixture, Reason.INCONSISTENT_FACTS);
        fixture.starts = List.of(StartFact.of(UUID.randomUUID(), SCOPE, NOW, null,
                new AttemptLifecycle.State(1, AttemptLifecycle.Execution.IN_PROGRESS, AttemptLifecycle.AnswerCollection.NOT_STARTED), NO_RESULT));
        denied(fixture, Reason.ACTIVE_ATTEMPT);
        assertThatThrownBy(() -> StartFact.of(UUID.randomUUID(), SCOPE, NOW, null, null, NO_RESULT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ResultFact.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test void identityNamespacesDoNotInventAGlobalUniquenessRestriction() {
        var start = terminal(Execution.COMPLETED, NOW);
        var fixture = new Fixture(); fixture.maximum = 1L; fixture.starts = List.of(start);
        fixture.adjustments = List.of(new Adjustment(1, start.id(), SCOPE, UUID.randomUUID(), UUID.randomUUID(), 1, NOW));
        allowed(fixture, NOW);
    }

    @Test void calculationAndFacadeCannotReadOrWriteJpaInvokeProvidersOrInstallHttpRoutes() throws Exception {
        var pending = new ArrayDeque<Class<?>>(List.of(RetakeEligibility.class));
        while (!pending.isEmpty()) {
            var type = pending.remove(); pending.addAll(List.of(type.getDeclaredClasses()));
            assertNoDependencies(type, "org/springframework/", "jakarta/persistence/", "javax/persistence/",
                    "com/fasterxml/jackson/", "java/sql/", "java/net/", "java/io/", "/application/", "/infra/", "/api/", "/repository/");
        }
        assertNoDependencies(RetakeEligibilityServiceImpl.class, "java/sql/", "java/net/", "java/io/", "/repository/",
                "/infra/", "/api/", "org/springframework/transaction/", "SecurityContext", "java/time/Instant.now");
    }

    private void assertNoDependencies(Class<?> type, String... forbidden) throws Exception {
        try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertThat(bytes).isNotNull();
            assertThat(new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1)).as(type.getName()).doesNotContain(forbidden);
        }
    }
}
