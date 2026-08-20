package uk.gegc.quizmaker.features.auth.application;

import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.user.domain.model.User;

/**
 * Issues and validates JWTs through a revocable server-side session.
 */
public interface AuthSessionService {

    JwtResponse issueTokens(Authentication authentication);

    /**
     * Issues a session for a user already resolved inside a wider authentication transaction.
     * This keeps OAuth exchange-code consumption and session creation atomic without repeating
     * user lookups while the exchange row is locked.
     */
    JwtResponse issueTokensForUser(User user);

    JwtResponse refresh(String refreshToken);

    void logout(String accessToken);

    Authentication authenticateAccessToken(String accessToken);

    int purgeExpiredSessions();
}
