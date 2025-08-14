package uk.gegc.quizmaker.features.quiz.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.attempt.*;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateShareLinkRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateShareLinkResponse;
import uk.gegc.quizmaker.features.quiz.api.dto.ShareLinkDto;
import uk.gegc.quizmaker.shared.exception.UnauthorizedException;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.quiz.domain.model.ShareLinkEventType;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.quiz.application.ShareLinkService;
import uk.gegc.quizmaker.features.quiz.infra.web.ShareLinkCookieManager;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1/quizzes")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Share Links", description = "Share link management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class ShareLinkController {

    private static final Pattern TOKEN_RE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final ShareLinkService shareLinkService;
    private final ShareLinkCookieManager cookieManager;
    private final RateLimitService rateLimitService;
    private final TrustedProxyUtil trustedProxyUtil;
    private final AttemptService attemptService;
	private final UserRepository userRepository;

    @PostMapping("/{quizId}/share-link")
    @Operation(
        summary = "Create a share link for a quiz",
        description = "Creates a secure share link for the specified quiz. The link can be one-time use or reusable, with configurable expiry and scope."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Share link created successfully",
            content = @Content(schema = @Schema(implementation = CreateShareLinkResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
        @ApiResponse(responseCode = "404", description = "Quiz not found")
    })
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CreateShareLinkResponse> createShareLink(
            @Parameter(description = "Quiz ID") @PathVariable UUID quizId,
            @Parameter(description = "Share link configuration") @Valid @RequestBody CreateShareLinkRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        
        log.info("Creating share link for quiz {} by user {}", quizId, authentication.getName());
        
		UUID userId = resolveAuthenticatedUserId(authentication);
        // Rate limit: 10/min per user
        rateLimitService.checkRateLimit("share-link-create", userId.toString(), 10);
        CreateShareLinkResponse response = shareLinkService.createShareLink(quizId, userId, request);
        // Analytics: CREATED event
        String ua = httpRequest.getHeader("User-Agent");
        String ip = getClientIpAddress(httpRequest);
        String ref = httpRequest.getHeader("Referer");
        shareLinkService.recordShareLinkEventById(response.link().id(), ShareLinkEventType.CREATED, ua, ip, ref);
        
        log.info("Share link created successfully for quiz {} with ID {}", quizId, response.link().id());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/shared/{tokenId}")
    @Operation(
        summary = "Revoke a share link",
        description = "Revokes a share link, making it unusable. Only the creator or users with appropriate permissions can revoke links."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Share link revoked successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
        @ApiResponse(responseCode = "404", description = "Share link not found")
    })
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revokeShareLink(
            @Parameter(description = "Share link ID") @PathVariable UUID tokenId,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        
        log.info("Revoking share link {} by user {}", tokenId, authentication.getName());
        
		UUID userId = resolveAuthenticatedUserId(authentication);
        shareLinkService.revokeShareLink(tokenId, userId);
        // Analytics: REVOKED event
        String ua = httpRequest.getHeader("User-Agent");
        String ip = getClientIpAddress(httpRequest);
        String ref = httpRequest.getHeader("Referer");
        shareLinkService.recordShareLinkEventById(tokenId, ShareLinkEventType.REVOKED, ua, ip, ref);
        
        log.info("Share link {} revoked successfully", tokenId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/shared/{token}")
    @Operation(
        summary = "Access a shared quiz",
        description = "Validates a share token and sets a secure cookie for accessing the shared quiz. Tracks usage analytics."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Token validated successfully, cookie set"),
        @ApiResponse(responseCode = "400", description = "Invalid token format"),
        @ApiResponse(responseCode = "404", description = "Token not found or expired"),
        @ApiResponse(responseCode = "410", description = "Token already used (one-time links)")
    })
    public ResponseEntity<ShareLinkDto> accessSharedQuiz(
            @Parameter(description = "Share token") @PathVariable String token,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        if (!TOKEN_RE.matcher(token).matches()) {
            throw new IllegalArgumentException("Invalid token format");
        }
        
        String userAgent = request.getHeader("User-Agent");
        String ipAddress = getClientIpAddress(request);
        
        log.info("Accessing shared quiz with token, IP: {}, User-Agent: {}", 
                maskIpAddress(ipAddress), truncateUserAgent(userAgent));
        
        // Validate the token and get share link details
        ShareLinkDto shareLink = shareLinkService.validateToken(token);
        
        // Record usage analytics - need to hash the token first
        String tokenHash = shareLinkService.hashToken(token);
        // Rate limit: 60/min per IP + token-hash
        rateLimitService.checkRateLimit("share-link-access", ipAddress + "|" + tokenHash, 60);
        shareLinkService.recordShareLinkUsage(tokenHash, userAgent, ipAddress);
        // Enhanced analytics: VIEW event
        String ref = request.getHeader("Referer");
        shareLinkService.recordShareLinkEventByToken(token, ShareLinkEventType.VIEW, userAgent, ipAddress, ref);
        
        // Set secure cookie for quiz access
        cookieManager.setShareLinkCookie(response, token, shareLink.quizId());
        
        log.info("Share link {} accessed successfully for quiz {}", shareLink.id(), shareLink.quizId());
        return ResponseEntity.ok(shareLink);
    }

    @PostMapping("/shared/{token}/consume")
    @Operation(
        summary = "Consume a one-time share link",
        description = "Consumes a one-time share link, making it unusable for future requests. Returns the share link details."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Token consumed successfully",
            content = @Content(schema = @Schema(implementation = ShareLinkDto.class))),
        @ApiResponse(responseCode = "400", description = "Invalid token format"),
        @ApiResponse(responseCode = "404", description = "Token not found or expired"),
        @ApiResponse(responseCode = "410", description = "Token already used")
    })
    public ResponseEntity<ShareLinkDto> consumeOneTimeToken(
            @Parameter(description = "Share token") @PathVariable String token,
            HttpServletRequest request) {
        
        if (!TOKEN_RE.matcher(token).matches()) {
            throw new IllegalArgumentException("Invalid token format");
        }
        
        String userAgent = request.getHeader("User-Agent");
        String ipAddress = getClientIpAddress(request);
        
        log.info("Consuming one-time token, IP: {}, User-Agent: {}", 
                maskIpAddress(ipAddress), truncateUserAgent(userAgent));
        
        // Rate limit: 60/min per IP + token-hash
        String tokenHash = shareLinkService.hashToken(token);
        rateLimitService.checkRateLimit("share-link-consume", ipAddress + "|" + tokenHash, 60);
        ShareLinkDto shareLink = shareLinkService.consumeOneTimeToken(token, userAgent, ipAddress);
        // Enhanced analytics: CONSUMED event
        String ref = request.getHeader("Referer");
        shareLinkService.recordShareLinkEventByToken(token, ShareLinkEventType.CONSUMED, userAgent, ipAddress, ref);
        
        log.info("One-time token consumed successfully for quiz {}", shareLink.quizId());
        return ResponseEntity.ok(shareLink);
    }

    @GetMapping("/share-links")
    @Operation(
        summary = "Get user's share links",
        description = "Retrieves all share links created by the authenticated user, ordered by creation date."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Share links retrieved successfully",
            content = @Content(schema = @Schema(implementation = ShareLinkDto.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ShareLinkDto>> getUserShareLinks(Authentication authentication) {
        
		UUID userId = resolveAuthenticatedUserId(authentication);
        
        log.info("Retrieving share links for user {}", userId);
        
        List<ShareLinkDto> shareLinks = shareLinkService.getUserShareLinks(userId);
        
        log.info("Retrieved {} share links for user {}", shareLinks.size(), userId);
        return ResponseEntity.ok(shareLinks);
    }


    @PostMapping("/shared/{token}/attempts")
    @Operation(
        summary = "Start anonymous attempt using a share token",
        description = "Starts an attempt for the quiz referenced by the share token. Intended for anonymous users with a valid share token cookie or link."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Attempt started",
            content = @Content(schema = @Schema(implementation = StartAttemptResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid token format"),
        @ApiResponse(responseCode = "404", description = "Token not found or expired"),
        @ApiResponse(responseCode = "410", description = "Token already used (one-time links)")
    })
    public ResponseEntity<StartAttemptResponse> startAnonymousAttempt(
            @Parameter(description = "Share token") @PathVariable String token,
            @RequestBody(required = false) @Valid StartAttemptRequest request,
            HttpServletRequest httpRequest
    ) {
        if (!TOKEN_RE.matcher(token).matches()) {
            throw new IllegalArgumentException("Invalid token format");
        }

        String userAgent = httpRequest.getHeader("User-Agent");
        String ipAddress = getClientIpAddress(httpRequest);
        String ref = httpRequest.getHeader("Referer");

        // Rate limit: 60/min per IP + token-hash
        String tokenHash = shareLinkService.hashToken(token);
        rateLimitService.checkRateLimit("share-link-attempt-start", ipAddress + "|" + tokenHash, 60);

        // Validate token and resolve quizId
        ShareLinkDto shareLink = shareLinkService.validateToken(token);
        AttemptMode mode = (request != null && request.mode() != null) ? request.mode() : AttemptMode.ALL_AT_ONCE;

        StartAttemptResponse response = attemptService.startAnonymousAttempt(shareLink.quizId(), shareLink.id(), mode);

        // Analytics: ATTEMPT_START event
        shareLinkService.recordShareLinkEventByToken(token, ShareLinkEventType.ATTEMPT_START, userAgent, ipAddress, ref);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/shared/attempts/{attemptId}/answers")
    @Operation(
        summary = "Submit an answer for an anonymous attempt",
        description = "Submits an answer for an in-progress anonymous attempt when a valid share token cookie is present."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Answer submitted",
            content = @Content(schema = @Schema(implementation = AnswerSubmissionDto.class))),
        @ApiResponse(responseCode = "400", description = "Validation error or invalid token"),
        @ApiResponse(responseCode = "404", description = "Attempt or question not found"),
        @ApiResponse(responseCode = "409", description = "Attempt not in progress or duplicate/sequence violation")
    })
    public ResponseEntity<AnswerSubmissionDto> submitAnonymousAnswer(
            @Parameter(description = "UUID of the attempt") @PathVariable UUID attemptId,
            @RequestBody @Valid AnswerSubmissionRequest request,
            HttpServletRequest httpRequest
    ) {
        // Retrieve share token from cookie
        String token = cookieManager.getShareLinkToken(httpRequest)
                .orElseThrow(() -> new IllegalArgumentException("Valid share token required"));
        if (!TOKEN_RE.matcher(token).matches()) {
            throw new IllegalArgumentException("Invalid token format");
        }

        // Validate token and attempt-quiz correlation
        ShareLinkDto shareLink = shareLinkService.validateToken(token);
        UUID attemptQuizId = attemptService.getAttemptQuizId(attemptId);
        UUID attemptShareLinkId = attemptService.getAttemptShareLinkId(attemptId);
        if (!shareLink.quizId().equals(attemptQuizId) || !shareLink.id().equals(attemptShareLinkId)) {
            throw new ResourceNotFoundException("Attempt does not belong to shared quiz");
        }

        // Rate limit per IP + token-hash
        String ipAddress = getClientIpAddress(httpRequest);
        String tokenHash = shareLinkService.hashToken(token);
        rateLimitService.checkRateLimit("share-link-answer", ipAddress + "|" + tokenHash, 60);

        // Submit answer as anonymous user (ownership enforced against 'anonymous' user set at attempt start)
        AnswerSubmissionDto dto = attemptService.submitAnswer("anonymous", attemptId, request);
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/shared/attempts/{attemptId}/answers/batch")
    @Operation(
        summary = "Submit multiple answers for an anonymous attempt",
        description = "Submits a batch of answers for an in-progress anonymous attempt when a valid share token cookie is present."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Answers submitted"),
        @ApiResponse(responseCode = "400", description = "Validation error or invalid token"),
        @ApiResponse(responseCode = "404", description = "Attempt or question not found"),
        @ApiResponse(responseCode = "409", description = "Attempt not in progress or duplicate/sequence violation")
    })
    public ResponseEntity<List<AnswerSubmissionDto>> submitAnonymousAnswersBatch(
            @Parameter(description = "UUID of the attempt") @PathVariable UUID attemptId,
            @RequestBody @Valid BatchAnswerSubmissionRequest request,
            HttpServletRequest httpRequest
    ) {
        String token = cookieManager.getShareLinkToken(httpRequest)
                .orElseThrow(() -> new IllegalArgumentException("Valid share token required"));
        if (!TOKEN_RE.matcher(token).matches()) {
            throw new IllegalArgumentException("Invalid token format");
        }

        ShareLinkDto shareLink = shareLinkService.validateToken(token);
        UUID attemptQuizId = attemptService.getAttemptQuizId(attemptId);
        UUID attemptShareLinkId = attemptService.getAttemptShareLinkId(attemptId);
        if (!shareLink.quizId().equals(attemptQuizId) || !shareLink.id().equals(attemptShareLinkId)) {
            throw new ResourceNotFoundException("Attempt does not belong to shared quiz");
        }

        String ipAddress = getClientIpAddress(httpRequest);
        String tokenHash = shareLinkService.hashToken(token);
        rateLimitService.checkRateLimit("share-link-answer", ipAddress + "|" + tokenHash, 60);

        List<AnswerSubmissionDto> result = attemptService.submitBatch("anonymous", attemptId, request);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/shared/attempts/{attemptId}/stats")
    @Operation(
        summary = "Get anonymous attempt statistics",
        description = "Returns statistics for an anonymous attempt when a valid share token cookie is present."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Attempt statistics",
            content = @Content(schema = @Schema(implementation = AttemptStatsDto.class))),
        @ApiResponse(responseCode = "400", description = "Invalid token or request"),
        @ApiResponse(responseCode = "404", description = "Attempt not found or does not belong to shared quiz")
    })
    public ResponseEntity<AttemptStatsDto> getAnonymousAttemptStats(
            @Parameter(description = "UUID of the attempt") @PathVariable UUID attemptId,
            HttpServletRequest httpRequest
    ) {
        String token = cookieManager.getShareLinkToken(httpRequest)
                .orElseThrow(() -> new IllegalArgumentException("Valid share token required"));
        if (!TOKEN_RE.matcher(token).matches()) {
            throw new IllegalArgumentException("Invalid token format");
        }

        ShareLinkDto shareLink = shareLinkService.validateToken(token);
        UUID attemptQuizId = attemptService.getAttemptQuizId(attemptId);
        UUID attemptShareLinkId = attemptService.getAttemptShareLinkId(attemptId);
        if (!shareLink.quizId().equals(attemptQuizId) || !shareLink.id().equals(attemptShareLinkId)) {
            throw new ResourceNotFoundException("Attempt does not belong to shared quiz");
        }

        String ipAddress = getClientIpAddress(httpRequest);
        String tokenHash = shareLinkService.hashToken(token);
        rateLimitService.checkRateLimit("share-link-stats", ipAddress + "|" + tokenHash, 60);

        AttemptStatsDto stats = attemptService.getAttemptStats(attemptId);
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/shared/attempts/{attemptId}/complete")
    @Operation(
        summary = "Complete an anonymous attempt",
        description = "Completes an in-progress anonymous attempt when a valid share token cookie is present and returns the result summary."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Attempt completed",
            content = @Content(schema = @Schema(implementation = AttemptResultDto.class))),
        @ApiResponse(responseCode = "400", description = "Invalid token or request"),
        @ApiResponse(responseCode = "404", description = "Attempt not found or does not belong to shared quiz"),
        @ApiResponse(responseCode = "409", description = "Attempt not in progress or already completed")
    })
    public ResponseEntity<AttemptResultDto> completeAnonymousAttempt(
            @Parameter(description = "UUID of the attempt") @PathVariable UUID attemptId,
            HttpServletRequest httpRequest
    ) {
        String token = cookieManager.getShareLinkToken(httpRequest)
                .orElseThrow(() -> new IllegalArgumentException("Valid share token required"));
        if (!TOKEN_RE.matcher(token).matches()) {
            throw new IllegalArgumentException("Invalid token format");
        }

        ShareLinkDto shareLink = shareLinkService.validateToken(token);
        UUID attemptQuizId = attemptService.getAttemptQuizId(attemptId);
        if (!shareLink.quizId().equals(attemptQuizId)) {
            throw new ResourceNotFoundException("Attempt does not belong to shared quiz");
        }

        String ipAddress = getClientIpAddress(httpRequest);
        String tokenHash = shareLinkService.hashToken(token);
        rateLimitService.checkRateLimit("share-link-complete", ipAddress + "|" + tokenHash, 60);

        AttemptResultDto result = attemptService.completeAttempt("anonymous", attemptId);
        return ResponseEntity.ok(result);
    }



    /**
     * Safely parses a UUID string, returning Optional.empty() if invalid.
     */
    private Optional<UUID> safeParseUuid(String uuidString) {
        try {
            return Optional.of(UUID.fromString(uuidString));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

	/**
	 * Resolves the authenticated user's ID from the authentication principal (username or email).
	 */
	private UUID resolveAuthenticatedUserId(Authentication authentication) {
		String principal = authentication.getName();
		return safeParseUuid(principal)
				.orElseGet(() -> {
					User user = userRepository.findByUsername(principal)
							.or(() -> userRepository.findByEmail(principal))
							.orElseThrow(() -> new UnauthorizedException("Unknown principal"));
					return user.getId();
				});
	}

    /**
     * Gets the client IP address from the request, handling proxy headers.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        return trustedProxyUtil.getClientIp(request);
    }

    /**
     * Masks an IP address for logging privacy.
     */
    private String maskIpAddress(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return "unknown";
        }
        
        // For IPv4, mask the last octet
        if (ipAddress.contains(".")) {
            String[] parts = ipAddress.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + "." + parts[2] + ".*";
            }
        }
        
        // For IPv6, mask the last 64 bits
        if (ipAddress.contains(":")) {
            String[] parts = ipAddress.split(":");
            if (parts.length >= 4) {
                return parts[0] + ":" + parts[1] + ":" + parts[2] + ":*";
            }
        }
        
        return "masked";
    }

    /**
     * Truncates user agent string for logging privacy.
     */
    private String truncateUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return "unknown";
        }
        
        if (userAgent.length() > 50) {
            return userAgent.substring(0, 47) + "...";
        }
        
        return userAgent;
    }
}
