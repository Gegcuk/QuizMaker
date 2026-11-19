package uk.gegc.quizmaker.features.attempt.subject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticatedUserLookupTest {
    @ParameterizedTest
    @CsvSource({"false,0", "false,1205", "false,1213", "true,0", "true,1205", "true,1213"})
    void bothQueriesDiscardDriverGraphsWithoutChangingConflictClassification(boolean locking, int code) {
        var users = mock(UserRepository.class);
        var raw = new SQLException("PRIVATE_LOOKUP_DRIVER", "HY000", code, new IllegalStateException("PRIVATE_LOOKUP_CAUSE"));
        raw.addSuppressed(new IllegalStateException("PRIVATE_LOOKUP_SUPPRESSED"));
        raw.setNextException(new SQLException("PRIVATE_LOOKUP_NEXT"));
        var original = new DataAccessResourceFailureException("PRIVATE_LOOKUP_OUTER", raw);
        original.addSuppressed(new IllegalStateException("PRIVATE_LOOKUP_TOP_SUPPRESSED"));
        if (locking) when(users.lockActiveAuthenticatedUserId("Alice")).thenThrow(original);
        else when(users.findActiveAuthenticatedUserId("Alice")).thenThrow(original);
        var lookup = new AuthenticatedUserLookupImpl(users);
        Throwable failure = catchThrowable(() -> {
            if (locking) lookup.lockActiveUserId("Alice"); else lookup.findActiveUserId("Alice");
        });
        assertThat(failure).isExactlyInstanceOf(DataAccessResourceFailureException.class)
                .isNotSameAs(original).hasMessage("Database operation failed");
        assertThat(failure.getSuppressed()).isEmpty();
        if (code == 0) assertThat(failure).hasNoCause();
        else assertThat(failure.getCause()).isInstanceOfSatisfying(DatabaseConflictException.class, conflict -> {
            assertThat(conflict.kind()).isEqualTo(code == 1205 ? DatabaseConflictException.Kind.LOCK_TIMEOUT
                    : DatabaseConflictException.Kind.DEADLOCK);
            assertThat(conflict).hasNoCause();
            assertThat(conflict.getSuppressed()).isEmpty();
        });
        if (locking) verify(users).lockActiveAuthenticatedUserId("Alice");
        else verify(users).findActiveAuthenticatedUserId("Alice");
        verifyNoMoreInteractions(users);
    }

    @Test
    void invalidNamesNeverReachPersistence() {
        var users = mock(UserRepository.class);
        AuthenticatedUserLookup lookup = new AuthenticatedUserLookupImpl(users);
        for (String username : new String[]{null, "", " ", "a".repeat(51)}) {
            assertThat(lookup.findActiveUserId(username)).isEmpty();
            assertThat(lookup.lockActiveUserId(username)).isEmpty();
        }
        verifyNoInteractions(users);
    }

    @Test
    void currentLookupUsesTheLockingQueryAndPreservesThePrincipalName() {
        var users = mock(UserRepository.class);
        UUID expected = UUID.randomUUID();
        when(users.lockActiveAuthenticatedUserId("Alice")).thenReturn(Optional.of(expected));
        when(users.lockActiveAuthenticatedUserId("missing")).thenReturn(Optional.empty());
        AuthenticatedUserLookup lookup = new AuthenticatedUserLookupImpl(users);
        assertThat(lookup.lockActiveUserId("Alice")).contains(expected);
        assertThat(lookup.lockActiveUserId("missing")).isEmpty();
        verify(users).lockActiveAuthenticatedUserId("Alice");
        verify(users).lockActiveAuthenticatedUserId("missing");
        verifyNoMoreInteractions(users);
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
