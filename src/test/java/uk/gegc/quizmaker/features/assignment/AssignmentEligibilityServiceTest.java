package uk.gegc.quizmaker.features.assignment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import uk.gegc.quizmaker.features.assignment.application.AssignmentEligibilityService;
import uk.gegc.quizmaker.features.assignment.application.impl.AssignmentEligibilityServiceImpl;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentEligibility.*;
import uk.gegc.quizmaker.features.attempt.application.AuthenticatedSubjectResolver;
import uk.gegc.quizmaker.features.attempt.application.impl.AuthenticatedSubjectResolverImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.assignment.AssignmentFixtures.*;

class AssignmentEligibilityServiceTest {
    private final AuthenticatedUserLookup users = mock(AuthenticatedUserLookup.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AuthenticatedSubjectResolver subjects = new AuthenticatedSubjectResolverImpl(users, metrics);
    private final Clock fixed = Clock.fixed(TIME, ZoneOffset.UTC);
    private final AssignmentEligibilityService service = new AssignmentEligibilityServiceImpl(subjects, fixed, metrics);

    @BeforeEach void authenticateEligibleMember() {
        when(users.findActiveUserId("alice")).thenReturn(Optional.of(MEMBER));
        authenticate("alice", "QUIZ_TAKE");
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); metrics.close(); }

    @Test void realSubjectResolverAndSpringWiringExposeOnlyTheApplicationInterfaceForConsumers() {
        try (var spring = new AnnotationConfigApplicationContext()) {
            spring.registerBean(AuthenticatedUserLookup.class, () -> users);
            spring.registerBean(MeterRegistry.class, () -> metrics);
            spring.registerBean(Clock.class, () -> fixed);
            spring.register(AuthenticatedSubjectResolverImpl.class, AssignmentEligibilityServiceImpl.class);
            spring.refresh();
            var consumer = spring.getBean(AssignmentEligibilityService.class);
            var f = new EligibilityFixture();
            assertThat(consumer.evaluate(f.context()).outcome()).isEqualTo(Outcome.ALLOW);
            verify(users).findActiveUserId("alice");
            verifyNoMoreInteractions(users);
        }
    }

    @Test void claimedForeignSubjectCannotReplaceTheAuthenticatedParticipant() {
        var f = new EligibilityFixture();
        assertThat(service.evaluate(f.context()).outcome()).isEqualTo(Outcome.ALLOW);
        f.subject = AttemptSubject.authenticated(f.subject.attemptId(), OTHER_MEMBER);
        assertThat(service.evaluate(f.context()).reason()).isEqualTo(Reason.SUBJECT_MISMATCH);
        f.subject = AttemptSubject.authenticated(f.subject.attemptId(), MEMBER);
        assertThat(service.evaluate(f.context()).outcome()).isEqualTo(Outcome.ALLOW);
    }

    @Test void structurallyValidSubjectWithoutAuthenticationNeverAuthorizesParticipation() {
        var context = new EligibilityFixture().context();
        assertThat(service.evaluate(context).outcome()).isEqualTo(Outcome.ALLOW);
        SecurityContextHolder.clearContext();
        assertThat(service.evaluate(context).reason()).isEqualTo(Reason.SUBJECT_UNAVAILABLE);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("anonymous", "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(service.evaluate(context).reason()).isEqualTo(Reason.SUBJECT_UNAVAILABLE);
        // A token with a caller-supplied UUID/string principal is not the existing session boundary.
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                MEMBER.toString(), null, List.of(new SimpleGrantedAuthority("QUIZ_ADMIN"))));
        assertThat(service.evaluate(context).reason()).isEqualTo(Reason.SUBJECT_UNAVAILABLE);
        authenticate("alice", "QUIZ_TAKE");
        assertThat(service.evaluate(context).outcome()).isEqualTo(Outcome.ALLOW);
        verify(users, times(2)).findActiveUserId("alice");
        verifyNoMoreInteractions(users);
    }

    @Test void currentAccountLossAndLookupFailureDenyWithoutRetryOrDiagnosticDisclosure() {
        var context = new EligibilityFixture().context();
        assertThat(service.evaluate(context).outcome()).isEqualTo(Outcome.ALLOW);
        doReturn(Optional.empty()).when(users).findActiveUserId("alice");
        assertThat(service.evaluate(context).reason()).isEqualTo(Reason.SUBJECT_UNAVAILABLE);
        doThrow(new DataAccessResourceFailureException("PRIVATE_LOOKUP_DIAGNOSTIC")).when(users).findActiveUserId("alice");
        var unavailable = service.evaluate(context);
        assertThat(unavailable.reason()).isEqualTo(Reason.SUBJECT_UNAVAILABLE);
        assertThat(unavailable.toString()).doesNotContain("PRIVATE_LOOKUP_DIAGNOSTIC");
        doThrow(new DataAccessResourceFailureException("PRIVATE_SQL_DIAGNOSTIC",
                new SQLException("PRIVATE_SQL_DIAGNOSTIC", "40001", 1213))).when(users).findActiveUserId("alice");
        assertThat(service.evaluate(context).reason()).isEqualTo(Reason.SUBJECT_UNAVAILABLE);
        doReturn(Optional.of(MEMBER)).when(users).findActiveUserId("alice");
        assertThat(service.evaluate(context).outcome()).isEqualTo(Outcome.ALLOW);
        verify(users, times(5)).findActiveUserId("alice");
        verifyNoMoreInteractions(users);
    }

    @Test void quizAdminRoleAndAssignmentOwnershipDoNotBypassTheCapturedAudience() {
        var f = new EligibilityFixture();
        assertThat(service.evaluate(f.context()).outcome()).isEqualTo(Outcome.ALLOW);
        when(users.findActiveUserId("owner")).thenReturn(Optional.of(OWNER));
        authenticate("owner", "QUIZ_ADMIN");
        f.subject = AttemptSubject.authenticated(f.subject.attemptId(), OWNER);
        assertThat(service.evaluate(f.context()).reason()).isEqualTo(Reason.AUDIENCE_MISMATCH);
    }

    @Test void onlyTheServerClockDeterminesAvailabilityAndIsSampledExactlyOnce() {
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(TIME);
        var checked = new AssignmentEligibilityServiceImpl(subjects, clock, metrics);
        var context = new EligibilityFixture().context();
        assertThat(checked.evaluate(context).outcome()).isEqualTo(Outcome.ALLOW);
        verify(clock).instant();
        when(clock.instant()).thenReturn(TIME.plusSeconds(3600));
        // The snapshot's recordedAt remains inside the window and cannot act as claimed current time.
        assertThat(checked.evaluate(context).reason()).isEqualTo(Reason.CLOSED);
        when(clock.instant()).thenThrow(new IllegalStateException("PRIVATE_CLOCK_DIAGNOSTIC"));
        var failure = checked.evaluate(context);
        assertThat(failure.reason()).isEqualTo(Reason.CLOCK_UNAVAILABLE);
        assertThat(failure.toString()).doesNotContain("PRIVATE_CLOCK_DIAGNOSTIC");
        verify(clock, times(3)).instant();
        verifyNoMoreInteractions(clock);
        var beforeOpen = new AssignmentEligibilityServiceImpl(subjects, Clock.offset(fixed, Duration.ofNanos(-1)), metrics);
        assertThat(beforeOpen.evaluate(context).reason()).isEqualTo(Reason.NOT_OPEN);
    }

    @Test void missingAndUnsupportedInputsNeverCauseIdentityLookupOrAcquireAuthority() {
        assertThat(service.evaluate(null).reason()).isEqualTo(Reason.INVALID_INPUT);
        var f = new EligibilityFixture(); f.subject = null;
        assertThat(service.evaluate(f.context()).reason()).isEqualTo(Reason.SUBJECT_UNAVAILABLE);
        f = new EligibilityFixture();
        for (int version : new int[]{-1, 0, 2, Integer.MAX_VALUE}) {
            f.version = version;
            assertThat(service.evaluate(f.context()).reason()).isEqualTo(Reason.UNSUPPORTED_VERSION);
        }
        verifyNoInteractions(users);
        assertThat(metrics.get("quizmaker.assignment.eligibility").tags("version", "unsupported",
                "reason", "unsupported_version", "outcome", "deny").counter().count()).isEqualTo(4);
    }

    @Test void newCurrentFactsCanDenyAfterAnEarlierAllowAndNoMetricsContainIdentity() {
        var f = new EligibilityFixture();
        assertThat(service.evaluate(f.context()).outcome()).isEqualTo(Outcome.ALLOW);
        f.publication = Publication.REVOKED;
        assertThat(service.evaluate(f.context()).reason()).isEqualTo(Reason.REVOKED);
        f.publication = Publication.CURRENT; f.visibility = Verification.DENIED;
        assertThat(service.evaluate(f.context()).reason()).isEqualTo(Reason.NOT_VISIBLE);
        for (var meter : metrics.get("quizmaker.assignment.eligibility").meters()) {
            assertThat(meter.getId().getTags()).extracting("key").containsExactly("outcome", "reason", "version");
            assertThat(meter.getId().toString()).doesNotContain(ID.toString(), MEMBER.toString(), OWNER.toString(),
                    ASSIGNMENT.toString(), ORGANIZATION.toString(), f.subject.attemptId().toString());
        }
    }

    @Test void unavailableTelemetryCannotChangeAllowOrDeny() {
        var broken = mock(MeterRegistry.class);
        when(broken.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("PRIVATE_METRIC_DIAGNOSTIC"));
        var checked = new AssignmentEligibilityServiceImpl(subjects, fixed, broken);
        var f = new EligibilityFixture();
        assertThat(checked.evaluate(f.context()).outcome()).isEqualTo(Outcome.ALLOW);
        f.access = Verification.DENIED;
        var denied = checked.evaluate(f.context());
        assertThat(denied.reason()).isEqualTo(Reason.ACCESS_DENIED);
        assertThat(denied.toString()).doesNotContain("PRIVATE_METRIC_DIAGNOSTIC");
    }

    private static void authenticate(String username, String authority) {
        var principal = User.withUsername(username).password("unused").authorities(authority).build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
}
