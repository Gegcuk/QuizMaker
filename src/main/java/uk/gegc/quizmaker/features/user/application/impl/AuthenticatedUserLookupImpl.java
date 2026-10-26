package uk.gegc.quizmaker.features.user.application.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

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
        return users.findActiveAuthenticatedUserId(trustedUsername);
    }
}
