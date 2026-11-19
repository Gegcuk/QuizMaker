package uk.gegc.quizmaker.features.user.application.impl;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;

import java.util.Optional;
import java.util.UUID;

@Service
public class AuthenticatedUserLookupImpl implements AuthenticatedUserLookup {
    private final UserRepository users;

    public AuthenticatedUserLookupImpl(UserRepository users) { this.users = users; }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findActiveUserId(String trustedUsername) {
        if (trustedUsername == null || trustedUsername.isBlank() || trustedUsername.length() > 50) {
            return Optional.empty();
        }
        try { return users.findActiveAuthenticatedUserId(trustedUsername); }
        catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> lockActiveUserId(String trustedUsername) {
        if (trustedUsername == null || trustedUsername.isBlank() || trustedUsername.length() > 50) {
            return Optional.empty();
        }
        // Redact here, before this facade's own MANDATORY transaction interceptor logs the error.
        try { return users.lockActiveAuthenticatedUserId(trustedUsername); }
        catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }
}
