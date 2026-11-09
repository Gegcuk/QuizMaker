package uk.gegc.quizmaker.features.attempt.retake;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.*;

final class RetakeFixtures {
    static final Instant NOW = Instant.parse("2026-11-09T15:00:00Z");
    static final Scope SCOPE = new Scope(UUID.randomUUID(), ContextKind.ASSIGNMENT, UUID.randomUUID(), 7);
    static final ResultFact NO_RESULT = new ResultFact(1, null, ResultState.NONE);

    static final class Fixture {
        int version = 1, policyVersion = 1, factsVersion = 1;
        Scope scope = SCOPE, factsScope = SCOPE;
        Access access = Access.VERIFIED;
        Entry entry = Entry.OPEN;
        Knowledge knowledge = Knowledge.COMPLETE;
        Long maximum = 3L;
        Duration cooldown = Duration.ZERO;
        List<StartFact> starts = List.of();
        List<Adjustment> adjustments = List.of();

        Context build() {
            return new Context(version, scope, access, entry, new Policy(policyVersion, maximum, cooldown),
                    new Facts(factsVersion, factsScope, knowledge, starts, adjustments));
        }
    }

    static StartFact terminal(Execution execution, Instant at) {
        return new StartFact(1, UUID.randomUUID(), SCOPE, Commit.COMMITTED, execution,
                at.minusSeconds(60), at, NO_RESULT);
    }

    static StartFact active() {
        return new StartFact(1, UUID.randomUUID(), SCOPE, Commit.COMMITTED, Execution.ACTIVE,
                NOW.minusSeconds(60), null, NO_RESULT);
    }

    static StartFact failed() {
        return new StartFact(1, UUID.randomUUID(), SCOPE, Commit.TECHNICAL_FAILURE_BEFORE_COMMIT,
                Execution.NOT_STARTED, null, null, NO_RESULT);
    }

    static StartFact result(StartFact start, ResultState result) {
        return new StartFact(start.version(), start.id(), start.scope(), start.commit(), start.execution(),
                start.startedAt(), start.terminalAt(), new ResultFact(1, start.id(), result));
    }

    static Adjustment adjustment(long amount) {
        return new Adjustment(1, UUID.randomUUID(), SCOPE, UUID.randomUUID(), UUID.randomUUID(), amount, NOW);
    }

    private RetakeFixtures() { }
}
