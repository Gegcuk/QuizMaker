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
 * Controller for managing OAuth account linking and unlinking
 */
@Tag(name = "OAuth", description = "Endpoints for managing OAuth social login accounts")
@RestController
@RequestMapping("/api/v1/auth/oauth")
@RequiredArgsConstructor
public class OAuthAccountController {

    private final OAuthAccountService oauthAccountService;

    @Operation(
        summary = "Get linked OAuth accounts",
        description = "Returns a list of all OAuth accounts linked to the authenticated user"
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
        description = "Removes the link between the authenticated user and the specified OAuth provider. " +
                     "Users must have at least one other authentication method (password or another OAuth account) " +
                     "before unlinking."
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

