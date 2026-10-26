package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import uk.gegc.quizmaker.features.attempt.application.AuthenticatedSubjectResolver;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;

import java.util.Locale;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException.Reason.*;

@Service
public class AuthenticatedSubjectResolverImpl implements AuthenticatedSubjectResolver {
    private final AuthenticatedUserLookup users;
    private final MeterRegistry metrics;

    public AuthenticatedSubjectResolverImpl(AuthenticatedUserLookup users, MeterRegistry metrics) {
        this.users = users;
        this.metrics = metrics;
    }

    @Override
    public AttemptSubject resolve(UUID serverAttemptId) {
        var sample = startTimer();
        String outcome = "unavailable";
        try {
            if (serverAttemptId == null) throw new SubjectResolutionException(INVALID_ATTEMPT);
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || authentication instanceof AnonymousAuthenticationToken) {
                throw new SubjectResolutionException(UNAUTHENTICATED);
            }
            // This is the authenticated token/principal produced by the existing session boundary.
            // OAuth callback, pre-auth, string/UUID and future guest principals are not target identity.
            if (!(authentication instanceof UsernamePasswordAuthenticationToken)
                    || !(authentication.getPrincipal() instanceof UserDetails principal)) {
                throw new SubjectResolutionException(INVALID);
            }
            String username = principal.getUsername();
            if (username == null || username.isBlank() || username.length() > 50
                    || !principal.isEnabled() || !principal.isAccountNonLocked()
                    || !principal.isAccountNonExpired() || !principal.isCredentialsNonExpired()) {
                throw new SubjectResolutionException(INVALID);
            }
            if (!username.equals(authentication.getName())) throw new SubjectResolutionException(MIXED);
            UUID userId = users.findActiveUserId(username)
                    .orElseThrow(() -> new SubjectResolutionException(INVALID));
            var subject = AttemptSubject.authenticated(serverAttemptId, userId);
            outcome = "resolved";
            return subject;
        } catch (SubjectResolutionException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            throw new SubjectResolutionException(UNAVAILABLE);
        } finally {
            record(sample, outcome);
        }
    }

    private Timer.Sample startTimer() {
        try { return Timer.start(metrics); }
        catch (RuntimeException ignored) { return null; }
    }

    private void record(Timer.Sample sample, String outcome) {
        try {
            if (sample != null) sample.stop(Timer.builder("quizmaker.attempt.subject.resolution")
                    .tag("outcome", outcome).register(metrics));
        } catch (RuntimeException ignored) {
            // Observability must not replace a resolved identity or the original failure.
        }
    }
}
