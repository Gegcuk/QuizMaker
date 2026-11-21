package uk.gegc.quizmaker.features.learning.application.impl;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import java.util.UUID;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

/** Resolve the authenticated learner and retain active-account authority in the calling transaction. */
final class LearningActor {
    private LearningActor() { }

    static UUID lock(AuthenticatedUserLookup users) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        require(authentication instanceof UsernamePasswordAuthenticationToken && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserDetails, UNAUTHENTICATED);
        var principal = (UserDetails) authentication.getPrincipal();
        String name = principal.getUsername();
        require(name != null && !name.isBlank() && name.length() <= 50 && name.equals(authentication.getName())
                && principal.isEnabled() && principal.isAccountNonLocked() && principal.isAccountNonExpired()
                && principal.isCredentialsNonExpired(), UNAUTHENTICATED);
        return users.lockActiveUserId(name).orElseThrow(() -> new LearningSessionException(UNAVAILABLE));
    }
}
