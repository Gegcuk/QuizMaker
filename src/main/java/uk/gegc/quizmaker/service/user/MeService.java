package uk.gegc.quizmaker.service.user;

import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.dto.user.MeResponse;
import uk.gegc.quizmaker.dto.user.UpdateMeRequest;

public interface MeService {
    MeResponse getCurrentUserProfile(Authentication authentication);
    MeResponse updateCurrentUserProfile(Authentication authentication, UpdateMeRequest request);
}


