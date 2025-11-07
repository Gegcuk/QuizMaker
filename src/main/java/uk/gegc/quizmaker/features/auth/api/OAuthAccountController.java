package uk.gegc.quizmaker.features.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.auth.api.dto.LinkedAccountsResponse;
import uk.gegc.quizmaker.features.auth.api.dto.UnlinkAccountRequest;
import uk.gegc.quizmaker.features.auth.application.OAuthAccountService;

/**
 * Controller for managing OAuth account linking and unlinking.
 * 
 * <p><b>⚠️ IMPORTANT: OAuth Login Flow</b></p>
 * <p>To login with OAuth (Google, GitHub, Facebook, Microsoft), redirect users to:</p>
 * <ul>
 *   <li><code>GET /oauth2/authorization/google</code> - Login with Google</li>
 *   <li><code>GET /oauth2/authorization/github</code> - Login with GitHub</li>
 *   <li><code>GET /oauth2/authorization/facebook</code> - Login with Facebook</li>
 *   <li><code>GET /oauth2/authorization/microsoft</code> - Login with Microsoft</li>
 * </ul>
 * 
 * <p>After successful OAuth authentication, users are redirected to your frontend with JWT tokens:</p>
 * <code>https://yourfrontend.com/oauth2/redirect?accessToken=xxx&refreshToken=yyy</code>
 * 
 * <p>The endpoints below are for <b>managing</b> OAuth accounts after authentication, not for logging in.</p>
 */
@Tag(name = "OAuth Account Management", 
     description = "Manage linked OAuth social login accounts. " +
                   "To login with OAuth, redirect to /oauth2/authorization/{provider} (google, github, facebook, microsoft). " +
                   "These endpoints are for viewing and unlinking OAuth accounts after authentication.")
@RestController
@RequestMapping("/api/v1/auth/oauth")
@RequiredArgsConstructor
public class OAuthAccountController {

    private final OAuthAccountService oauthAccountService;

    @Operation(
        summary = "Get linked OAuth accounts",
        description = "Returns a list of all OAuth accounts (Google, GitHub, Facebook, Microsoft) linked to the authenticated user. " +
                     "Use this to display which social login methods the user has connected."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Successfully retrieved linked accounts"),
        @ApiResponse(responseCode = "401", description = "User not authenticated")
    })
    @GetMapping("/accounts")
    public ResponseEntity<LinkedAccountsResponse> getLinkedAccounts(Authentication authentication) {
        LinkedAccountsResponse response = oauthAccountService.getLinkedAccounts(authentication);
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Unlink an OAuth account",
        description = "Removes the link between the authenticated user and the specified OAuth provider (e.g., unlink Google account). " +
                     "<p><b>Security:</b> Users must have at least one other authentication method (password or another OAuth account) " +
                     "before unlinking. This prevents users from locking themselves out.</p>" +
                     "<p><b>Example use case:</b> User wants to stop using 'Login with Google' but still has a password or GitHub login.</p>"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Successfully unlinked account"),
        @ApiResponse(responseCode = "400", description = "Cannot unlink the only authentication method"),
        @ApiResponse(responseCode = "401", description = "User not authenticated"),
        @ApiResponse(responseCode = "404", description = "OAuth account not found")
    })
    @DeleteMapping("/accounts")
    public ResponseEntity<Void> unlinkAccount(
        Authentication authentication,
        @Valid @RequestBody UnlinkAccountRequest request
    ) {
        oauthAccountService.unlinkAccount(authentication, request.provider());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}

