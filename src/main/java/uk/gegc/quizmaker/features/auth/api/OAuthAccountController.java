package uk.gegc.quizmaker.features.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
 * <p>The web client creates and temporarily stores an S256 PKCE verifier, then starts either:</p>
 * <ul>
 *   <li><code>GET /oauth2/authorization/google?client_id=quizzence-web&amp;redirect_uri={exact-encoded-callback}&amp;code_challenge={S256-challenge}&amp;code_challenge_method=S256</code></li>
 *   <li><code>GET /oauth2/authorization/github?client_id=quizzence-web&amp;redirect_uri={exact-encoded-callback}&amp;code_challenge={S256-challenge}&amp;code_challenge_method=S256</code></li>
 * </ul>
 * 
 * <p>After successful OAuth authentication, users are redirected to the frontend with a short-lived code:</p>
 * <code>https://yourfrontend.com/oauth2/redirect?code=opaque-one-time-code</code>
 * <p>The frontend automatically exchanges that code with its PKCE verifier through
 * <code>POST /api/v1/auth/oauth/exchange</code>. Users do not enter or copy the code.</p>
 * 
 * <p>The endpoints below are for <b>managing</b> OAuth accounts after authentication, not for logging in.</p>
 */
@Tag(name = "OAuth Account Management", 
     description = "Manage linked OAuth social login accounts. " +
                   "For current web login with Google or GitHub, the frontend supplies client_id, the exact redirect_uri, " +
                   "an S256 code_challenge, and code_challenge_method=S256 when opening /oauth2/authorization/{provider}. " +
                   "It automatically exchanges the one-time callback code with its verifier. " +
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
        @ApiResponse(responseCode = "401", description = "User not authenticated",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
        @ApiResponse(responseCode = "400", description = "Cannot unlink the only authentication method",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "User not authenticated",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "OAuth account not found",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
