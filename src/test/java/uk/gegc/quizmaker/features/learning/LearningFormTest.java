package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.LearningFormServiceImpl;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningFormRepository;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningFormException.Reason.*;

class LearningFormTest {
    static final Instant TIME = Instant.parse("2026-11-19T10:00:00.123456Z");
    static LearningSession session() {
        return new LearningSession(UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(),
                new LearningSession.Source(1, UUID.randomUUID(), UUID.randomUUID(), 1), FsrsManifest.approved(),
                LearningSession.Status.CREATED, 1, TIME, TIME);
    }
    static LearningForm.Item item() {
        return new LearningForm.Item(UUID.randomUUID(), UUID.randomUUID(), 1, new ContractVersion("question.true-false.response", 1), List.of());
    }
    static LearningFormContent.PresentedItem shown(LearningForm.Item item) {
        return new LearningFormContent.PresentedItem(item, "Saved question", new ParticipantContent.TrueFalse());
    }
    static void rejected(Runnable action, LearningFormException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LearningFormException.class, e -> {
            assertThat(e.reason()).isEqualTo(reason); assertThat(e.getCause()).isNull();
            assertThat(e.getSuppressed()).isEmpty();
        });
    }

    private final LearningSession session = session();
    private final LearningSessionService sessions = mock(LearningSessionService.class);
    private final LearningFormRepository repository = mock(LearningFormRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final List<LearningForm.Item> selected = List.of(item(), item());
    private LearningFormService forms(List<LearningFormContent> content) {
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(sessions.read(session.id())).thenReturn(session);
        when(repository.matchesSourceItems(eq(session), anyList())).thenReturn(true);
        return new LearningFormServiceImpl(sessions, repository, content, Clock.fixed(TIME.plusNanos(789), ZoneOffset.UTC), metrics, manager);
    }
    private LearningFormService forms() { return forms(List.of((s, items) -> items.stream().map(LearningFormTest::shown).toList())); }
    private LearningFormService.Materialize command() { return new LearningFormService.Materialize(1, session.id(), selected); }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); metrics.close(); }

    @Test void optionalQuestionShufflePermutesWholeItemsAndPreservesDueOrderAndOptions() {
        var first = item();
        var second = item();
        var third = new LearningForm.Item(UUID.randomUUID(), UUID.randomUUID(), 1,
                new ContractVersion("question.mcq-single.response", 1), List.of(UUID.randomUUID(), UUID.randomUUID()));
        var due = List.of(first, second, third);
        assertThat(LearningForm.presentationOrder(due, false, new Random(0))).containsExactly(first, second, third);
        assertThat(LearningForm.presentationOrder(due, true, new Random(0))).containsExactly(third, second, first);
        assertThat(due).containsExactly(first, second, third);
        assertThat(LearningForm.presentationOrder(List.of(), true, new Random(0))).isEmpty();
    }

    @Test void exactSavedOrderIsImmutableAndMetadataMutationFailsFingerprintValidation() {
        var supplied = new ArrayList<>(selected);
        var form = LearningForm.create(UUID.randomUUID(), session, supplied, TIME);
        supplied.clear();
        assertThat(form.items()).containsExactlyElementsOf(selected);
        assertThatThrownBy(() -> form.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        var reversed = List.of(selected.get(1), selected.get(0));
        rejected(() -> new LearningForm(form.id(), 1, form.sessionId(), form.source(), reversed,
                form.outcome(), 1, form.fingerprint(), TIME), CORRUPT);
        assertThat(LearningForm.create(form.id(), session, reversed, TIME).fingerprint()).isNotEqualTo(form.fingerprint());
        assertThat(LearningForm.create(form.id(), session, selected, TIME).fingerprint()).isEqualTo(form.fingerprint());
    }

    @Test void duplicateNullAndUnsupportedIdentitiesNeverBecomeAForm() {
        rejected(() -> LearningForm.selection(null), INVALID_INPUT);
        rejected(() -> LearningForm.selection(Arrays.asList(selected.get(0), null)), INVALID_INPUT);
        rejected(() -> LearningForm.selection(List.of(selected.get(0), selected.get(0))), INVALID_INPUT);
        rejected(() -> new LearningForm.Item(UUID.randomUUID(), UUID.randomUUID(), 2, new ContractVersion("open", 1), List.of()), UNSUPPORTED_VERSION);
        UUID option = UUID.randomUUID();
        rejected(() -> new LearningForm.Item(UUID.randomUUID(), UUID.randomUUID(), 1, new ContractVersion("open", 1), List.of(option, option)), INVALID_INPUT);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 2})
    void unsupportedFormAndFingerprintVersionsFailClosed(int version) {
        var f = LearningForm.create(UUID.randomUUID(), session, selected, TIME);
        rejected(() -> new LearningForm(f.id(), version, f.sessionId(), f.source(), f.items(), f.outcome(), 1, f.fingerprint(), TIME), UNSUPPORTED_VERSION);
        rejected(() -> new LearningForm(f.id(), 1, f.sessionId(), f.source(), f.items(), f.outcome(), version, f.fingerprint(), TIME), UNSUPPORTED_VERSION);
    }

    @Test void createEmptyAndReplayHaveExplicitOutcomesAndNoLifecycleMutation() {
        var forms = forms();
        var first = forms.materialize(command());
        assertThat(first.replayed()).isFalse();
        assertThat(first.view().form().items()).isEqualTo(selected);
        assertThat(first.view().form().createdAt()).isEqualTo(TIME);
        assertThat(first.view().items()).extracting(LearningFormContent.PresentedItem::questionText).containsOnly("Saved question");
        verify(repository).insert(first.view().form());
        when(repository.find(session)).thenReturn(Optional.of(first.view().form()));
        assertThat(forms.materialize(command())).isEqualTo(new LearningFormService.Result(first.view(), true));
        assertThat(forms.read(session.id())).isEqualTo(first.view());
        rejected(() -> forms.materialize(new LearningFormService.Materialize(1, session.id(), List.of())), CONFLICT);
        verify(repository, times(1)).insert(any());
        when(repository.find(session)).thenReturn(Optional.empty());
        var empty = forms.materialize(new LearningFormService.Materialize(1, session.id(), List.of()));
        assertThat(empty.view().form().outcome()).isEqualTo(LearningForm.Outcome.EMPTY_COMPLETE);
        assertThat(empty.view().items()).isEmpty();
        assertThat(session.status()).isEqualTo(LearningSession.Status.CREATED);
    }

    @Test void missingAmbiguousUnknownAndMalformedContentNeverWrite() {
        for (var providers : List.<List<LearningFormContent>>of(List.of(), List.of((s, i) -> List.of(), (s, i) -> List.of()),
                List.of((s, i) -> null), List.of((s, i) -> List.of()),
                List.of((s, i) -> List.of(shown(i.get(1)), shown(i.get(0)))),
                List.of((s, i) -> { throw new IllegalArgumentException("private-answer-diagnostic"); }))) {
            rejected(() -> forms(providers).materialize(command()), SOURCE_UNAVAILABLE);
        }
        verify(repository, never()).insert(any());
        assertThat(forms().materialize(command()).replayed()).isFalse();
    }

    @Test void optionOrderCannotBeChangedByTheContentAdapter() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var item = new LearningForm.Item(UUID.randomUUID(), UUID.randomUUID(), 1, new ContractVersion("question.mcq-single.response", 1), List.of(b, a));
        LearningFormContent adapter = (s, i) -> List.of(new LearningFormContent.PresentedItem(item, "Options",
                new ParticipantContent.Choices(List.of(new ParticipantContent.Option(a, "A"), new ParticipantContent.Option(b, "B")))));
        rejected(() -> forms(List.of(adapter)).materialize(new LearningFormService.Materialize(1, session.id(), List.of(item))), SOURCE_UNAVAILABLE);
        verify(repository, never()).insert(any());
    }

    @Test void invalidAndIncompatibleCallsRejectBeforeCollaborators() {
        var forms = forms();
        rejected(() -> forms.materialize(null), INVALID_INPUT);
        rejected(() -> forms.materialize(new LearningFormService.Materialize(2, session.id(), selected)), UNSUPPORTED_VERSION);
        rejected(() -> forms.read(null), INVALID_INPUT);
        for (Integer isolation : Arrays.asList(null, TransactionDefinition.ISOLATION_REPEATABLE_READ)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
            rejected(() -> forms.materialize(command()), UNSUPPORTED_TRANSACTION);
            rejected(() -> forms.read(session.id()), UNSUPPORTED_TRANSACTION);
        }
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        rejected(() -> forms.materialize(command()), UNSUPPORTED_TRANSACTION);
        verifyNoInteractions(sessions, repository);
    }

    @Test void missingFormIsNotRepairedAndTelemetryFailureCannotUndoSuccess() {
        var forms = forms();
        rejected(() -> forms.read(session.id()), UNAVAILABLE);
        verify(repository, never()).insert(any());
        var broken = mock(io.micrometer.core.instrument.MeterRegistry.class);
        when(broken.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("telemetry"));
        var service = new LearningFormServiceImpl(sessions, repository, List.of((s, i) -> i.stream().map(LearningFormTest::shown).toList()),
                Clock.fixed(TIME, ZoneOffset.UTC), broken, manager);
        assertThat(service.materialize(command()).replayed()).isFalse();
    }
}
