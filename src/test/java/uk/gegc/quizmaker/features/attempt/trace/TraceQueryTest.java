package uk.gegc.quizmaker.features.attempt.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.impl.TraceQueryServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQuery.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException;
import uk.gegc.quizmaker.features.attempt.domain.repository.TraceQueryRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.trace.TraceFixtures.*;

/** Local contract tests; context doubles do not implement operator grants, holds or cleanup. */
class TraceQueryTest {
    final Scope scope = new Scope(id("query-attempt"), id("query-policy"), id("query-form"));
    TraceQueryRepository repository;
    TraceQueryContextReader contexts;
    PlatformTransactionManager manager;
    SimpleMeterRegistry metrics;
    TraceQueryService service;

    @BeforeEach void setup() {
        repository = mock(TraceQueryRepository.class); contexts = mock(TraceQueryContextReader.class);
        manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        metrics = new SimpleMeterRegistry();
        grant(Availability.AVAILABLE);
        when(repository.head(scope.attemptId())).thenReturn(Optional.of(new TraceQueryRepository.Head(1, scope, 3, 3)));
        when(repository.page(eq(scope.attemptId()), anyLong(), anyLong(), anyInt())).thenAnswer(call -> {
            long after = call.getArgument(1), through = call.getArgument(2); int limit = call.getArgument(3);
            return java.util.stream.LongStream.rangeClosed(after + 1, Math.min(through, after + limit))
                    .mapToObj(n -> new Entry(n, NOW, Type.ANSWER_SUBMITTED, Actor.PARTICIPANT, ReasonCode.ACCEPTED, null, null)).toList();
        });
        service = reader(List.of(contexts), metrics);
    }

    void grant(Availability availability) {
        doReturn(Optional.of(new Context(scope, Purpose.DISPUTE, Operation.READ_PAGE, availability)))
                .when(contexts).authorize(scope.attemptId(), Purpose.DISPUTE, Operation.READ_PAGE);
    }
    TraceQueryService reader(List<TraceQueryContextReader> readers, MeterRegistry meter) {
        return new TraceQueryServiceImpl(repository, readers, manager, meter);
    }
    Request request(String cursor, int limit) { return new Request(1, scope.attemptId(), Purpose.DISPUTE, cursor, limit); }
    static void denied(Runnable action, TraceQueryException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TraceQueryException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure).hasNoCause(); assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.getMessage()).doesNotContain("private", "SELECT", "@example", "credential");
        });
    }

    @Test void pagesContainOnlyExplicitMetadataAndCollectionsAndDiagnosticsStayPrivate() throws Exception {
        var first = service.read(request(null, 2));
        assertThat(first.events()).extracting(Entry::sequence).containsExactly(1L, 2L);
        assertThat(first.nextCursor()).hasSize(66);
        var second = service.read(request(first.nextCursor(), 2));
        assertThat(second.events()).extracting(Entry::sequence).containsExactly(3L);
        assertThat(second.nextCursor()).isNull();
        var mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.readTree(mapper.writeValueAsString(first));
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("version", "events", "nextCursor");
        assertThat(json.path("events").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("sequence", "recordedAt", "type", "actor", "reason", "from", "to");
        assertThat(json.toString()).doesNotContain(scope.attemptId().toString(), scope.formId().toString(), scope.policySnapshotId().toString(),
                "commandId", "eventId", "itemId", "userId", "content", "answer");
        assertThatThrownBy(() -> first.events().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(first.toString() + request(first.nextCursor(), 2) + new Context(scope, Purpose.DISPUTE, Operation.READ_PAGE, Availability.AVAILABLE))
                .doesNotContain(first.nextCursor(), scope.attemptId().toString());
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 101, Integer.MAX_VALUE})
    void broadOrUnboundedQueriesNeverReachContextOrStorage(int limit) {
        denied(() -> service.read(request(null, limit)), INVALID_QUERY);
        verifyNoInteractions(repository); verify(contexts, never()).authorize(any(), any(), any());
        assertThat(service.read(request(null, 100)).events()).hasSize(3);
    }

    @Test void missingInputsAndUnknownContractVersionsFailBeforeAuthorization() {
        denied(() -> service.read(null), INVALID_QUERY);
        denied(() -> service.read(new Request(1, null, Purpose.DISPUTE, null, 1)), INVALID_QUERY);
        denied(() -> service.read(new Request(1, scope.attemptId(), null, null, 1)), INVALID_QUERY);
        denied(() -> service.read(new Request(2, scope.attemptId(), Purpose.DISPUTE, null, 1)), UNSUPPORTED_VERSION);
        verifyNoInteractions(repository); verify(contexts, never()).authorize(any(), any(), any());
    }

    @Test void absentAmbiguousDeniedAndMismatchedContextNeverReadEvidence() {
        denied(() -> reader(List.of(), metrics).read(request(null, 1)), ACCESS_UNAVAILABLE);
        denied(() -> reader(List.of(contexts, contexts), metrics).read(request(null, 1)), ACCESS_UNAVAILABLE);
        when(contexts.authorize(any(), any(), any())).thenReturn(Optional.empty());
        denied(() -> service.read(request(null, 1)), ACCESS_UNAVAILABLE);
        for (var context : List.of(new Context(scope, Purpose.OPERATIONS, Operation.READ_PAGE, Availability.AVAILABLE),
                new Context(new Scope(id("other"), scope.policySnapshotId(), scope.formId()), Purpose.DISPUTE, Operation.READ_PAGE, Availability.AVAILABLE),
                new Context(scope, Purpose.DISPUTE, null, Availability.AVAILABLE))) {
            when(contexts.authorize(any(), any(), any())).thenReturn(Optional.of(context));
            denied(() -> service.read(request(null, 1)), ACCESS_UNAVAILABLE);
        }
        verifyNoInteractions(repository);
        grant(Availability.AVAILABLE); assertThat(service.read(request(null, 1)).events()).hasSize(1);
    }

    @ParameterizedTest @EnumSource(value = Availability.class, names = "AVAILABLE", mode = EnumSource.Mode.EXCLUDE)
    void currentExplicitAvailabilityFactsAreNotInferredFromAnEmptyPage(Availability availability) {
        grant(availability);
        var reason = switch (availability) {
            case EXPIRED -> EVIDENCE_EXPIRED; case RETAINED_AWAY -> EVIDENCE_RETAINED_AWAY;
            default -> EVIDENCE_UNAVAILABLE;
        };
        denied(() -> service.read(request(null, 1)), reason);
        verifyNoInteractions(repository);
        grant(Availability.AVAILABLE); assertThat(service.read(request(null, 1)).events()).hasSize(1);
    }

    @Test void cursorsHaveBoundedCanonicalEncodingVersionAndScopeAndAreNotCredentials() {
        var cursor = service.read(request(null, 1)).nextCursor();
        for (var invalid : List.of("", "private".repeat(100_000), "!".repeat(66), cursor + "=", cursor.substring(1)))
            denied(() -> service.read(request(invalid, 1)), INVALID_CURSOR);
        byte[] bytes = Base64.getUrlDecoder().decode(cursor);
        for (int offset : List.of(0, 1, 40, 41)) {
            var changed = bytes.clone(); changed[offset] = (byte) 0xff;
            denied(() -> service.read(request(Base64.getUrlEncoder().withoutPadding().encodeToString(changed), 1)), INVALID_CURSOR);
        }
        when(contexts.authorize(any(), any(), any())).thenReturn(Optional.empty());
        denied(() -> service.read(request(cursor, 1)), ACCESS_UNAVAILABLE);
        grant(Availability.AVAILABLE); assertThat(service.read(request(cursor, 1)).events()).extracting(Entry::sequence).containsExactly(2L);
    }

    @Test void callbackAndStorageFailuresCannotExposeOriginalCausesOrSuppressedPayloads() {
        var privateFailure = new IllegalStateException("private credential", new RuntimeException("SELECT private"));
        privateFailure.addSuppressed(new RuntimeException("private@example.invalid"));
        when(contexts.authorize(any(), any(), any())).thenThrow(privateFailure);
        denied(() -> service.read(request(null, 1)), ACCESS_UNAVAILABLE);
        grant(Availability.AVAILABLE);
        when(repository.head(scope.attemptId())).thenThrow(new QueryTimeoutException("SELECT private", privateFailure));
        denied(() -> service.read(request(null, 1)), PERSISTENCE_UNAVAILABLE);
    }

    @Test void optionalTelemetryCannotChangeThePageAndItsLabelsContainNoQueryIdentifiers() {
        service.read(request(null, 1)); denied(() -> service.read(request(null, 0)), INVALID_QUERY);
        assertThat(metrics.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
            assertThat(tag.getKey()).isIn("version", "outcome");
            assertThat(tag.getValue()).doesNotContain(scope.attemptId().toString(), "private");
        }));
        var broken = mock(MeterRegistry.class); when(broken.config()).thenThrow(new IllegalStateException("private"));
        assertThat(reader(List.of(contexts), broken).read(request(null, 100)).events()).hasSize(3);
    }
}
