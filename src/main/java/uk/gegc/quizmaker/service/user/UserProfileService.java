package uk.gegc.quizmaker.service.user;

import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.dto.user.UserProfileResponse;
import uk.gegc.quizmaker.dto.user.UpdateUserProfileRequest;

public interface UserProfileService {
    UserProfileResponse getCurrentUserProfile(Authentication authentication);
    UserProfileResponse updateCurrentUserProfile(Authentication authentication, UpdateUserProfileRequest request);
    UserProfileResponse updateCurrentUserProfile(Authentication authentication, UpdateUserProfileRequest request, Long ifMatchVersion);
    UserProfileResponse updateCurrentUserProfile(Authentication authentication, com.fasterxml.jackson.databind.JsonNode mergePatch, Long ifMatchVersion);
}


