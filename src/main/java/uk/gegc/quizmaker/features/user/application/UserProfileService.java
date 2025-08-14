package uk.gegc.quizmaker.features.user.application;

import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.user.api.dto.UpdateUserProfileRequest;
import uk.gegc.quizmaker.features.user.api.dto.UserProfileResponse;

public interface UserProfileService {
    UserProfileResponse getCurrentUserProfile(Authentication authentication);
    UserProfileResponse updateCurrentUserProfile(Authentication authentication, UpdateUserProfileRequest request);
    UserProfileResponse updateCurrentUserProfile(Authentication authentication, UpdateUserProfileRequest request, Long ifMatchVersion);
    UserProfileResponse updateCurrentUserProfile(Authentication authentication, com.fasterxml.jackson.databind.JsonNode mergePatch, Long ifMatchVersion);
}


