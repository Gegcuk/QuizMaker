package uk.gegc.quizmaker.features.attempt.subject;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.CannotCreateTransactionException;
import uk.gegc.quizmaker.features.attempt.application.AuthenticatedSubjectResolver;
import uk.gegc.quizmaker.features.attempt.application.impl.AuthenticatedSubjectResolverImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException.Reason.*;

class AuthenticatedSubjectResolverTest {
    private final UUID attempt = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final AuthenticatedUserLookup users = mock(AuthenticatedUserLookup.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AuthenticatedSubjectResolver resolver = new AuthenticatedSubjectResolverImpl(users, metrics);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        metrics.close();
    }

    @Test
    void currentAuthenticatedPrincipalProducesTheExistingTypedSubjectWithoutPersisting() {
        authenticate(token(principal("alice")));
        when(users.findActiveUserId("alice")).thenReturn(Optional.of(alice));
        assertThat(resolver.resolve(attempt)).isEqualTo(AttemptSubject.authenticated(attempt, alice));
        verify(users).findActiveUserId("alice");
        verifyNoMoreInteractions(users);
        assertThat(metrics.get("quizmaker.attempt.subject.resolution").tag("outcome", "resolved").timer().count()).isEqualTo(1);
    }

    @Test
    void absentAnonymousAndUnauthenticatedContextsFailEvenWhenAnonymousTokenIsAuthenticated() {
        SecurityContextHolder.clearContext();
        rejected(() -> resolver.resolve(attempt), UNAUTHENTICATED);
        authenticate(UsernamePasswordAuthenticationToken.unauthenticated(principal("alice"), "unused"));
        rejected(() -> resolver.resolve(attempt), UNAUTHENTICATED);
        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThat(anonymous.isAuthenticated()).isTrue();
        authenticate(anonymous);
        rejected(() -> resolver.resolve(attempt), UNAUTHENTICATED);
        verifyNoInteractions(users);
    }

    @Test
    void stringUuidCompositeAndNullPrincipalsNeverBecomeUserIdentity() {
        for (Object supplied : new Object[]{"alice", alice, alice.toString(), Map.of("userId", alice), null}) {
            authenticate(UsernamePasswordAuthenticationToken.authenticated(supplied, null, List.of()));
            rejected(() -> resolver.resolve(attempt), INVALID);
        }
        verifyNoInteractions(users);
    }

    @Test
    void unsupportedAuthenticatedTokenTypesCannotActAsSessionAuthentication() {
        for (Authentication authentication : List.of(
                new TestingAuthenticationToken(principal("alice"), null, "ROLE_USER"),
                new RememberMeAuthenticationToken("key", principal("alice"), List.of(new SimpleGrantedAuthority("ROLE_USER"))))) {
            authenticate(authentication);
            rejected(() -> resolver.resolve(attempt), INVALID);
        }
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "locked", "accountExpired", "credentialsExpired"})
    void invalidPrincipalStateCannotReachTheUserLookup(String state) {
        var principal = User.withUsername("alice").password("unused").authorities(List.of())
                .disabled(state.equals("disabled")).accountLocked(state.equals("locked"))
                .accountExpired(state.equals("accountExpired")).credentialsExpired(state.equals("credentialsExpired")).build();
        authenticate(token(principal));
        rejected(() -> resolver.resolve(attempt), INVALID);
        verifyNoInteractions(users);
    }

    @Test
    void invalidPrincipalNamesAreRejectedWithoutNormalizationOrLookup() {
        for (String username : new String[]{null, "", " ", "a".repeat(51)}) {
            UserDetails principal = mock(UserDetails.class);
            when(principal.getUsername()).thenReturn(username);
            authenticate(token(principal));
            rejected(() -> resolver.resolve(attempt), INVALID);
        }
        verifyNoInteractions(users);
    }

    @Test
    void contradictoryIdentitySourcesFailClosed() {
        var authentication = new UsernamePasswordAuthenticationToken(principal("alice"), null, List.of()) {
            @Override public String getName() { return "bob"; }
        };
        authenticate(authentication);
        rejected(() -> resolver.resolve(attempt), MIXED);
        verifyNoInteractions(users);
    }

    @Test
    void callerIdsAndGuestMaterialInDetailsCannotReplaceTheAuthenticatedPrincipal() {
        var authentication = token(principal("alice"));
        authentication.setDetails(Map.of("userId", UUID.randomUUID(), "subjectId", UUID.randomUUID(),
                "subjectType", "CAPABILITY_GUEST", "capability", "synthetic-unused"));
        authenticate(authentication);
        when(users.findActiveUserId("alice")).thenReturn(Optional.of(alice));
        assertThat(resolver.resolve(attempt).userId()).isEqualTo(alice);
        verify(users).findActiveUserId("alice");
        verifyNoMoreInteractions(users);
    }

    @Test
    void uuidShapedUsernameUsesExactUsernameLookupRatherThanCallerSelectedId() {
        String username = UUID.randomUUID().toString();
        authenticate(token(principal(username)));
        when(users.findActiveUserId(username)).thenReturn(Optional.of(alice));
        assertThat(resolver.resolve(attempt).userId()).isEqualTo(alice).isNotEqualTo(UUID.fromString(username));
        verify(users).findActiveUserId(username);
    }

    @Test
    void missingOrNoLongerEligibleUserGetsTheSameNonEnumeratingFailure() {
        authenticate(token(principal("alice")));
        when(users.findActiveUserId("alice")).thenReturn(Optional.empty());
        rejected(() -> resolver.resolve(attempt), INVALID);
        when(users.findActiveUserId("alice")).thenReturn(Optional.of(alice));
        assertThat(resolver.resolve(attempt).userId()).isEqualTo(alice);
    }

    @Test
    void storageAndTransactionOutagesNeverExposeDiagnosticsOrResolveAnIdentity() {
        authenticate(token(principal("alice")));
        when(users.findActiveUserId("alice")).thenThrow(new DataAccessResourceFailureException("private database diagnostic"));
        rejected(() -> resolver.resolve(attempt), UNAVAILABLE);
        doThrow(new CannotCreateTransactionException("private transaction diagnostic")).when(users).findActiveUserId("alice");
        rejected(() -> resolver.resolve(attempt), UNAVAILABLE);
    }

    @Test
    void missingServerAttemptIdIsRejectedWithoutAnyLookup() {
        authenticate(token(principal("alice")));
        rejected(() -> resolver.resolve(null), INVALID_ATTEMPT);
        verifyNoInteractions(users);
    }

    @Test
    void aMetricTypeCollisionCannotChangeSuccessOrTheOriginalFailure() {
        authenticate(token(principal("alice")));
        metrics.counter("quizmaker.attempt.subject.resolution", "outcome", "resolved");
        metrics.counter("quizmaker.attempt.subject.resolution", "outcome", "invalid");
        metrics.counter("quizmaker.attempt.subject.resolution", "outcome", "unavailable");
        when(users.findActiveUserId("alice")).thenReturn(Optional.of(alice));
        assertThat(resolver.resolve(attempt).userId()).isEqualTo(alice);
        when(users.findActiveUserId("alice")).thenReturn(Optional.empty());
        rejected(() -> resolver.resolve(attempt), INVALID);
        when(users.findActiveUserId("alice")).thenThrow(new DataAccessResourceFailureException("private"));
        rejected(() -> resolver.resolve(attempt), UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void metricClockFailureAtStartOrCompletionCannotChangeIdentityOrDenial(boolean atStart) {
        var fail = new AtomicBoolean(atStart);
        Clock clock = new Clock() {
            @Override public long wallTime() { return 0; }
            @Override public long monotonicTime() {
                if (fail.get()) throw new IllegalStateException("Synthetic metric failure");
                return 1;
            }
        };
        var broken = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        try {
            var service = new AuthenticatedSubjectResolverImpl(users, broken);
            authenticate(token(principal("alice")));
            when(users.findActiveUserId("alice")).thenAnswer(call -> { fail.set(true); return Optional.of(alice); });
            assertThat(service.resolve(attempt).userId()).isEqualTo(alice);
            fail.set(atStart);
            when(users.findActiveUserId("alice")).thenAnswer(call -> { fail.set(true); return Optional.empty(); });
            rejected(() -> service.resolve(attempt), INVALID);
        } finally { broken.close(); }
    }

    @Test
    void independentThreadContextsNeverReuseAnotherParticipantsIdentity() throws Exception {
        UUID bob = UUID.randomUUID();
        when(users.findActiveUserId("alice")).thenReturn(Optional.of(alice));
        when(users.findActiveUserId("bob")).thenReturn(Optional.of(bob));
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<UUID> first = () -> resolveOnThread("alice");
            Callable<UUID> second = () -> resolveOnThread("bob");
            var one = pool.submit(first);
            var two = pool.submit(second);
            assertThat(one.get(5, TimeUnit.SECONDS)).isEqualTo(alice);
            assertThat(two.get(5, TimeUnit.SECONDS)).isEqualTo(bob);
            assertThat(pool.submit(() -> {
                try { rejected(() -> resolver.resolve(attempt), UNAUTHENTICATED); return true; }
                finally { SecurityContextHolder.clearContext(); }
            }).get(5, TimeUnit.SECONDS)).isTrue();
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    private UUID resolveOnThread(String username) {
        try { authenticate(token(principal(username))); return resolver.resolve(attempt).userId(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    static UserDetails principal(String username) {
        return User.withUsername(username).password("unused").authorities(List.of()).build();
    }

    static UsernamePasswordAuthenticationToken token(UserDetails principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    static void authenticate(Authentication authentication) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    static void rejected(Runnable action, SubjectResolutionException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SubjectResolutionException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getMessage()).isEqualTo("Authenticated participant unavailable");
            assertThat(failure.getCause()).isNull();
        });
    }
}
