package uk.gegc.quizmaker.features.user.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.user.api.dto.AvatarUploadResponse;
import uk.gegc.quizmaker.features.user.api.dto.UserProfileResponse;
import uk.gegc.quizmaker.features.user.application.AvatarService;
import uk.gegc.quizmaker.features.user.application.UserProfileService;


@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User profile and avatar management")
@SecurityRequirement(name = "Bearer Authentication")
public class UserController {

    private final UserProfileService userProfileService;
    private final AvatarService avatarService;

    @Operation(
            summary = "Get my profile",
            description = "Returns the authenticated user's profile"
    )
    @ApiResponse(responseCode = "200", description = "Profile returned",
            content = @Content(schema = @Schema(implementation = UserProfileResponse.class)))
    @GetMapping(path = "/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserProfileResponse> getMe(Authentication authentication) {
        UserProfileResponse body = userProfileService.getCurrentUserProfile(authentication);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Pragma", "no-cache")
                .eTag('"' + String.valueOf(body.version() == null ? 0 : body.version()) + '"')
                .body(body);
    }

    @Operation(
            summary = "Update my profile",
            description = "Updates the authenticated user's profile information"
    )
    @ApiResponse(responseCode = "200", description = "Profile updated successfully",
            content = @Content(schema = @Schema(implementation = UserProfileResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @PatchMapping(path = "/me", consumes = {"application/json", "application/merge-patch+json"})
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserProfileResponse> updateMe(
            Authentication authentication,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody JsonNode payload) {
        Long version = null;
        if (ifMatch != null && !ifMatch.isBlank()) {
            String trimmed = ifMatch.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            try {
                version = Long.parseLong(trimmed);
            } catch (NumberFormatException ignored) {
                // treat invalid If-Match as missing; client will get fresh ETag in response
            }
        }
        UserProfileResponse body = userProfileService.updateCurrentUserProfile(authentication, payload, version);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Pragma", "no-cache")
                .eTag('"' + String.valueOf(body.version() == null ? 0 : body.version()) + '"')
                .body(body);
    }

    @Operation(
            summary = "Upload my avatar",
            description = "Uploads a new avatar image for the authenticated user. Accepts PNG, JPEG, WEBP. Image is resized to max 512x512.")
    @ApiResponse(responseCode = "200", description = "Avatar updated successfully",
            content = @Content(schema = @Schema(implementation = AvatarUploadResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid file or unsupported MIME type")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @PostMapping(path = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AvatarUploadResponse> uploadAvatar(
            Authentication authentication,
            @RequestPart("file") MultipartFile file
    ) {
        String url = avatarService.uploadAndAssignAvatar(authentication.getName(), file);
        return ResponseEntity.ok(new AvatarUploadResponse(url, "Avatar updated successfully"));
    }
}
