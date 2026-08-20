package uk.gegc.quizmaker.features.auth.application;

import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.api.dto.OAuthCodeExchangeRequest;

import java.util.UUID;

public interface OAuthExchangeService {

    String issueCode(UUID userId, OAuthLoginContext loginContext);

    JwtResponse exchange(OAuthCodeExchangeRequest request);

    int purgeExpiredCodes();
}
