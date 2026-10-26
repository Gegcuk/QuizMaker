package uk.gegc.quizmaker.features.attempt.subject;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticatedUserLookupTest {
    @Test
    void invalidNamesNeverReachPersistence() {
        var users = mock(UserRepository.class);
        AuthenticatedUserLookup lookup = new AuthenticatedUserLookupImpl(users);
        for (String username : new String[]{null, "", " ", "a".repeat(51)}) {
            assertThat(lookup.findActiveUserId(username)).isEmpty();
        }
        verifyNoInteractions(users);
    }

    @Test
    void lookupPreservesTheExactPrincipalNameAndNeverFallsBackToOtherIdentitySources() {
        var users = mock(UserRepository.class);
        UUID expected = UUID.randomUUID();
        when(users.findActiveAuthenticatedUserId("Alice")).thenReturn(Optional.of(expected));
        when(users.findActiveAuthenticatedUserId("missing")).thenReturn(Optional.empty());
        AuthenticatedUserLookup lookup = new AuthenticatedUserLookupImpl(users);
        assertThat(lookup.findActiveUserId("Alice")).contains(expected);
        assertThat(lookup.findActiveUserId("missing")).isEmpty();
        verify(users).findActiveAuthenticatedUserId("Alice");
        verify(users).findActiveAuthenticatedUserId("missing");
        verifyNoMoreInteractions(users);
    }
}
