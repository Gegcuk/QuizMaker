package uk.gegc.quizmaker.features.attempt.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.api.AttemptController;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommandException;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.AnswerCollection;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.CommandIdentity;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.Execution;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.command.AttemptCommandsTest.assertDecision;
import static uk.gegc.quizmaker.features.attempt.command.CommandFixtures.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.evaluate;

class CommandBoundaryTest {
    @Test
    void missingOrDeniedCurrentAuthorityAlwaysWinsIncludingVerifiedReplayAndConflicts() {
        var denied = new Acknowledgement(Outcome.DENIED);
        for (Access access : new Access[]{null, Access.MISSING, Access.DENIED}) {
            for (Type type : Type.values()) for (var identity : CommandIdentity.values()) {
                var c = active();
                var present = new Context(access, c.policy(), c.state(), 7, c.item(), c.completion());
                var absent = new Context(access, null, null, -1, null, null);
                for (var context : List.of(present, absent)) {
                    var result = evaluate(new Command(1, type), context, new Metadata(0, OPERATION, identity));
                    assertDecision(result, Reason.ACCESS_DENIED, "-");
                    assertThat(result.acknowledgement()).isEqualTo(denied);
                    assertThat(AttemptCommands.allowedActions(context).actions()).isEmpty();
                }
            }
        }
        // Positive control through the same command path, with currently verified access.
        assertDecision(evaluate(new Command(1, Type.SUBMIT_ANSWER), active(), fresh()), Reason.ALLOWED, "-");
        var revoked = new Context(Access.DENIED, null, null, -1, null, null);
        assertThat(evaluate(new Command(Integer.MAX_VALUE, null), revoked, null).acknowledgement()).isEqualTo(denied);
    }

    @Test
    void publicDenialHasNoProtectedExistenceVersionOrDiagnosticReason() {
        var c = active();
        var safeDenials = List.of(
                evaluate(null, c, fresh()),
                evaluate(new Command(2, Type.START), c, fresh()),
                evaluate(new Command(1, Type.START), null, fresh()),
                evaluate(new Command(1, Type.START), c, fresh()),
                evaluate(new Command(1, Type.SUBMIT_ANSWER), c, null),
                evaluate(new Command(1, Type.SUBMIT_ANSWER), withItem(c, null), fresh()));
        assertThat(safeDenials).extracting(Decision::acknowledgement)
                .containsOnly(new Acknowledgement(Outcome.DENIED));
        assertThat(Acknowledgement.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("outcome");
        // Stale/conflict classifications are visible only after successful current access validation.
        assertThat(evaluate(new Command(1, Type.SUBMIT_ANSWER), c,
                new Metadata(0, OPERATION, CommandIdentity.FRESH)).acknowledgement())
                .isEqualTo(new Acknowledgement(Outcome.STALE));
        assertThat(evaluate(new Command(1, Type.SUBMIT_ANSWER), c,
                new Metadata(7, OPERATION, CommandIdentity.CONFLICTING)).acknowledgement())
                .isEqualTo(new Acknowledgement(Outcome.CONFLICT));
    }

    @Test
    void fabricatedAllowedActionsAndLegacyModesCannotGrantCommandAuthority() {
        var forged = new AllowedActions(Arrays.asList(Type.values()));
        var terminal = context(Execution.SUBMITTED, AnswerCollection.COMPLETE);
        assertThat(forged.contains(Type.SUBMIT_ANSWER)).isTrue();
        assertDecision(evaluate(new Command(1, Type.SUBMIT_ANSWER), terminal, fresh()),
                Reason.LIFECYCLE_DENIED, "TERMINAL_STATE");
        assertThat(Command.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("version", "type");
        for (String claim : List.of("ALL_AT_ONCE", "ONE_BY_ONE", "TIMED", "IN_PROGRESS", "canSubmitCurrent=true")) {
            rejected(1, claim, Reason.UNKNOWN_COMMAND);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "start", " START", "START ", "1", "SAVE_DRAFT", "REQUEST_PAUSE", "RESUME_ATTEMPT",
            "SUBMIT_SECTION", "EXPIRE_ATTEMPT", "RELEASE_RESULT", "REQUEST_HINT", "private-response-secret"})
    void unknownNamesAndDeferredCapabilitiesNeverSelectAPermissiveDefault(String value) {
        rejected(1, value, Reason.UNKNOWN_COMMAND);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unknownContractVersionsNeverSelectV1(int version) {
        rejected(version, "START", Reason.UNSUPPORTED_VERSION);
        assertThatThrownBy(() -> new State(version, Execution.CREATED, AnswerCollection.NOT_STARTED))
                .isInstanceOfSatisfying(LifecycleException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(LifecycleException.Reason.UNSUPPORTED_VERSION));
    }

    @Test
    void exactVocabularyRoundTripsAndOversizedOrUnknownStateInputStaysOutOfErrors() {
        for (Type type : Type.values()) assertThat(AttemptCommands.parseType(1, type.name())).isSameAs(type);
        rejected(1, null, Reason.INVALID_INPUT);
        rejected(1, "private-response-".repeat(100_000), Reason.UNKNOWN_COMMAND);
        assertThatThrownBy(() -> AttemptLifecycle.parseValue(1, Execution.class, "private-unknown-state"))
                .isInstanceOfSatisfying(LifecycleException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(LifecycleException.Reason.UNKNOWN_VALUE);
                    assertThat(ex).hasMessage("Attempt lifecycle rejected: UNKNOWN_VALUE");
                });
    }

    @Test
    void valuesAreImmutableAndInvalidDecisionCombinationsCannotClaimSuccess() {
        var source = new ArrayList<>(List.of(Type.VIEW_ATTEMPT));
        var actions = new AllowedActions(source);
        source.add(Type.SUBMIT_ANSWER);
        assertThat(actions.actions()).containsExactly(Type.VIEW_ATTEMPT);
        assertThatThrownBy(() -> new Decision(Reason.ALLOWED, LifecycleException.Reason.TERMINAL_STATE))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid command decision");
        assertThatThrownBy(() -> new Decision(Reason.LIFECYCLE_DENIED, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid command decision");
        assertThatThrownBy(() -> new Decision(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Acknowledgement(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AllowedActions(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void contractHasNoFrameworkPersistenceTransportClockOrRuntimeWiringDependency() throws Exception {
        var pending = new ArrayDeque<>(List.of(AttemptCommands.class, AttemptCommandException.class));
        while (!pending.isEmpty()) {
            var type = pending.remove();
            pending.addAll(List.of(type.getDeclaredClasses()));
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1)).as(type.getName())
                        .doesNotContain("org/springframework/", "jakarta/persistence/", "com/fasterxml/jackson/",
                                "java/sql/", "java/net/", "java/io/", "java/time/", "SecurityContext",
                                "AttemptStatus", "AttemptMode", "/application/", "/infra/", "/api/");
            }
        }
        for (var legacy : List.of(AttemptController.class, AttemptServiceImpl.class)) {
            try (var bytes = legacy.getResourceAsStream("/" + legacy.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1)).doesNotContain("/model/command/");
            }
        }
    }

    private static void rejected(int version, String value, Reason reason) {
        assertThatThrownBy(() -> AttemptCommands.parseType(version, value))
                .isInstanceOfSatisfying(AttemptCommandException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(reason);
                    assertThat(ex).hasMessage("Attempt command rejected: " + reason).hasNoCause();
                });
    }
}
