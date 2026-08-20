package uk.gegc.quizmaker.features.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.api.dto.OAuthCodeExchangeRequest;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeService;
import uk.gegc.quizmaker.features.auth.application.OAuthPkcePolicy;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeRequestException;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;

import java.util.regex.Pattern;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/v1/auth/oauth")
@RequiredArgsConstructor
public class OAuthCodeExchangeController {

    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_REDIRECT_URI_LENGTH = 2048;
    private static final int CODE_ATTEMPTS_PER_MINUTE = 5;

    private final OAuthExchangeService oauthExchangeService;
    private final RateLimitService rateLimitService;

    @Operation(
            summary = "Exchange an OAuth callback code",
            description = "Automatically exchanges a two-minute, single-use OAuth callback code for the existing "
                    + "session-bound JWT response. The browser supplies its RFC 7636 S256 verifier; the code is also "
                    + "bound to the exact registered client and redirect URI. A successful code cannot be exchanged "
                    + "again. This operation has no pagination, filtering, or sorting parameters and requires no "
                    + "additional action from the user. The browser must be online to exchange the code; if the "
                    + "two-minute code expires while offline, it restarts sign-in."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Code consumed once and session tokens returned",
                    content = @Content(schema = @Schema(implementation = JwtResponse.class))),
            @ApiResponse(responseCode = "400", description = "Malformed or structurally invalid exchange request",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Code is invalid, expired, or does not match its PKCE/client/redirect binding",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Code has already been consumed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Too many exchange attempts",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Exchange state is temporarily unavailable; restart sign-in or retry as directed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(
            value = "/exchange",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<JwtResponse> exchange(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Opaque code, original PKCE verifier, and exact registered client binding",
                    content = @Content(schema = @Schema(implementation = OAuthCodeExchangeRequest.class))
            )
            @RequestBody OAuthCodeExchangeRequest request
    ) {
        validateBoundedRequest(request);
        rateLimitService.checkRateLimit(
                "oauth-code-exchange-code",
                OAuthPkcePolicy.hashRawCode(request.code()),
                CODE_ATTEMPTS_PER_MINUTE
        );

        JwtResponse response = oauthExchangeService.exchange(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }

    private void validateBoundedRequest(OAuthCodeExchangeRequest request) {
        if (request == null
                || request.clientId() == null
                || !CLIENT_ID.matcher(request.clientId()).matches()
                || request.redirectUri() == null
                || request.redirectUri().isBlank()
                || request.redirectUri().length() > MAX_REDIRECT_URI_LENGTH) {
            throw new OAuthExchangeRequestException();
        }
        OAuthPkcePolicy.requireCode(request.code());
        OAuthPkcePolicy.requireVerifier(request.codeVerifier());
    }
}
