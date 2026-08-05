package uk.gegc.quizmaker.features.auth.application;

import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;

/**
 * Issues and validates JWTs through a revocable server-side session.
 */
public interface AuthSessionService {

    JwtResponse issueTokens(Authentication authentication);

    JwtResponse refresh(String refreshToken);

    void logout(String accessToken);

    Authentication authenticateAccessToken(String accessToken);

    int purgeExpiredSessions();
}
