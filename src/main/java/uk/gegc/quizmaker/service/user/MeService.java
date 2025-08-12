package uk.gegc.quizmaker.service.user;

import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.dto.user.MeResponse;

public interface MeService {
    MeResponse getCurrentUserProfile(Authentication authentication);
}


