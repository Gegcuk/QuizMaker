package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.LearningHistorySelectionServiceImpl;
import uk.gegc.quizmaker.features.learning.config.LearningDueSelectionConfig;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningHistorySelection.*;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;

/** Contract/authentication tests; real history writers, SQL and method-security proxy use conformance tests. */
class LearningHistorySelectionTest {
    static final UUID ANNA = UUID.randomUUID();
    static final Instant TIME = Instant.parse("2026-11-24T12:00:00Z");
    static final LearningSession.Source SOURCE = new LearningSession.Source(1, UUID.randomUUID(), UUID.randomUUID(), 1);
    final AuthenticatedUserLookup users = mock(AuthenticatedUserLookup.class);
    final LearningSessionSourceVerifier access = mock(LearningSessionSourceVerifier.class);
    final MistakeHistorySource mistakes = mock(MistakeHistorySource.class);
    final UnseenContentSource unseen = mock(UnseenContentSource.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final LearningDueSelectionConfig config = new LearningDueSelectionConfig();
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    LearningHistorySelectionService service;

    @BeforeEach void setup() {
        config.setMaxBatchSize(20);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(users.lockActiveUserId("anna")).thenReturn(Optional.of(ANNA));
        when(access.allows(ANNA, SOURCE)).thenReturn(true);
        login(false);
        service = service(metrics);
    }
    LearningHistorySelectionService service(MeterRegistry meter) {
        return new LearningHistorySelectionServiceImpl(users,List.of(access),List.of(mistakes),List.of(unseen),config,
                Clock.fixed(TIME.plusNanos(123),ZoneOffset.UTC),meter,manager);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); TransactionSynchronizationManager.clear(); metrics.close(); }
    static void login(boolean disabled) {
        var principal = User.withUsername("anna").password("unused").authorities("ROLE_USER").disabled(disabled).build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
    }
    static LearningHistorySelectionService.Select request(Mode mode,int limit,Cursor after) {
        return new LearningHistorySelectionService.Select(1,SOURCE,mode,limit,after);
    }
    static Item item(int position,boolean mistake) {
        return new Item(UUID.randomUUID(),UUID.randomUUID(),position,mistake
                ? new Evidence(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),TIME) : null);
    }
    static void rejected(Runnable work,LearningSessionException.Reason reason) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(LearningSessionException.class,e -> assertThat(e.reason()).isEqualTo(reason))
                .hasMessage(reason.name()).hasNoCause();
    }
    @Test void orderedLookaheadHasSourceBoundContinuationAndImmutableProvenance() {
        var a=item(2,true); var b=item(7,true); var c=item(10,true);
        when(mistakes.mistakes(ANNA,SOURCE,-1,3)).thenReturn(new Batch(Availability.COMPLETE,List.of(a,b,c)));
        var first=service.select(request(Mode.INCORRECT_ONLY,2,null));
        assertThat(first.items()).containsExactly(a,b); assertThat(first.outcome()).isEqualTo(Outcome.READY);
        assertThat(first.selectedAt()).isEqualTo(TIME); assertThat(first.next().authoredPosition()).isEqualTo(7);
        assertThatThrownBy(() -> first.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        when(mistakes.mistakes(ANNA,SOURCE,7,3)).thenReturn(new Batch(Availability.COMPLETE,List.of(c)));
        var last=service.select(request(Mode.INCORRECT_ONLY,2,first.next()));
        assertThat(last.items()).containsExactly(c); assertThat(last.outcome()).isEqualTo(Outcome.PARTIAL); assertThat(last.next()).isNull();
        verifyNoInteractions(unseen);
    }
    @Test void unavailableAndExhaustedAreDifferentEmptyResultsWithoutFallback() {
        for(var state:Availability.values()) {
            when(unseen.unseen(ANNA,SOURCE,-1,3)).thenReturn(new Batch(state,List.of()));
            var result=service.select(request(Mode.UNSEEN_ONLY,2,null));
            assertThat(result.availability()).isEqualTo(state); assertThat(result.outcome()).isEqualTo(Outcome.EMPTY);
            assertThat(result.items()).isEmpty(); assertThat(result.next()).isNull();
        }
        verifyNoInteractions(mistakes);
    }
    @Test void invalidRequestsAndConfigurationStopBeforeIdentityAndSources() {
        rejected(() -> service.select(null),INVALID_INPUT);
        rejected(() -> service.select(new LearningHistorySelectionService.Select(2,SOURCE,Mode.UNSEEN_ONLY,1,null)),UNSUPPORTED_VERSION);
        for(int size:List.of(0,-1,21)) rejected(() -> service.select(request(Mode.UNSEEN_ONLY,size,null)),INVALID_INPUT);
        for(var cursor:List.of(new Cursor(1,null,Mode.UNSEEN_ONLY,0),new Cursor(1,SOURCE,Mode.INCORRECT_ONLY,0),
                new Cursor(1,SOURCE,Mode.UNSEEN_ONLY,-1)))
            rejected(() -> service.select(request(Mode.UNSEEN_ONLY,1,cursor)),INVALID_INPUT);
        config.setMaxBatchSize(null); rejected(() -> service.select(request(Mode.UNSEEN_ONLY,1,null)),SOURCE_UNAVAILABLE);
        verifyNoInteractions(users,access,mistakes,unseen);
    }
    @Test void identityAndCurrentSourceAccessAreRequired() {
        SecurityContextHolder.clearContext(); rejected(() -> service.select(request(Mode.UNSEEN_ONLY,1,null)),UNAUTHENTICATED);
        login(true); rejected(() -> service.select(request(Mode.UNSEEN_ONLY,1,null)),UNAUTHENTICATED);
        verifyNoInteractions(users);
        login(false); when(users.lockActiveUserId("anna")).thenReturn(Optional.empty());
        rejected(() -> service.select(request(Mode.UNSEEN_ONLY,1,null)),UNAVAILABLE);
        when(users.lockActiveUserId("anna")).thenReturn(Optional.of(ANNA)); when(access.allows(ANNA,SOURCE)).thenReturn(false);
        rejected(() -> service.select(request(Mode.UNSEEN_ONLY,1,null)),SOURCE_UNAVAILABLE);
        verifyNoInteractions(mistakes,unseen);
    }
    @Test void incompatibleOuterIsolationRejectsBeforeStartingOrReading() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        for(Integer isolation:Arrays.asList(null,TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_READ_UNCOMMITTED)) {
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
            rejected(() -> service.select(request(Mode.UNSEEN_ONLY,1,null)),UNSUPPORTED_TRANSACTION);
        }
        verifyNoInteractions(manager,users,access,mistakes,unseen);
    }
    @Test void corruptOrderOrDuplicateIdentityNeverBecomesAValidSelection() {
        var a=item(1,false); var b=item(2,false);
        for(var items:List.of(List.of(b,a),List.of(a,new Item(b.sourceItemId(),a.questionRevisionId(),2,null)))) {
            when(unseen.unseen(ANNA,SOURCE,-1,3)).thenReturn(new Batch(Availability.COMPLETE,items));
            rejected(() -> service.select(request(Mode.UNSEEN_ONLY,2,null)),CORRUPT);
        }
    }
    @Test void missingAdapterIsUnavailableAndFailuresAreSanitizedBeforeTransactionProxy() {
        var absent=new LearningHistorySelectionServiceImpl(users,List.of(access),List.of(),List.of(),config,
                Clock.fixed(TIME,ZoneOffset.UTC),metrics,manager);
        assertThat(absent.select(request(Mode.INCORRECT_ONLY,1,null)).availability()).isEqualTo(Availability.SOURCE_UNAVAILABLE);
        when(mistakes.mistakes(ANNA,SOURCE,-1,2)).thenThrow(new IllegalStateException("private-answer-token"));
        rejected(() -> service.select(request(Mode.INCORRECT_ONLY,1,null)),PERSISTENCE_UNAVAILABLE);
    }
    @Test void diagnosticsAndOptionalTelemetryDoNotDiscloseOrChangeSelections() {
        var value=item(1,false);
        when(unseen.unseen(ANNA,SOURCE,-1,2)).thenReturn(new Batch(Availability.COMPLETE,List.of(value)));
        var broken=mock(MeterRegistry.class); when(broken.counter(anyString(),any(String[].class))).thenThrow(new IllegalStateException());
        var result=service(broken).select(request(Mode.UNSEEN_ONLY,1,null));
        assertThat(result.items()).containsExactly(value);
        assertThat(result.toString()+value+request(Mode.UNSEEN_ONLY,1,result.next()))
                .doesNotContain(value.questionRevisionId().toString(),SOURCE.quizId().toString());
    }
}
