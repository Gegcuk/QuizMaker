package uk.gegc.quizmaker.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.user.MeResponse;
import uk.gegc.quizmaker.dto.user.UpdateMeRequest;
import uk.gegc.quizmaker.service.user.MeService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class UserController {

    private final MeService meService;

    @Operation(
            summary = "Get my profile",
            description = "Returns the authenticated user's profile"
    )
    @ApiResponse(responseCode = "200", description = "Profile returned",
            content = @Content(schema = @Schema(implementation = MeResponse.class)))
    @GetMapping("/me")
    public ResponseEntity<MeResponse> getMe(Authentication authentication) {
        MeResponse body = meService.getCurrentUserProfile(authentication);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Pragma", "no-cache")
                .body(body);
    }

    @Operation(
            summary = "Update my profile",
            description = "Updates the authenticated user's profile information"
    )
    @ApiResponse(responseCode = "200", description = "Profile updated successfully",
            content = @Content(schema = @Schema(implementation = MeResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @PatchMapping("/me")
    public ResponseEntity<MeResponse> updateMe(
            Authentication authentication,
            @RequestBody @Valid UpdateMeRequest request) {
        MeResponse body = meService.updateCurrentUserProfile(authentication, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Pragma", "no-cache")
                .body(body);
    }
}
