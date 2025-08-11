package uk.gegc.quizmaker.controller;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.quiz.CreateShareLinkRequest;
import uk.gegc.quizmaker.dto.quiz.CreateShareLinkResponse;
import uk.gegc.quizmaker.dto.quiz.ShareLinkDto;
import uk.gegc.quizmaker.exception.UnauthorizedException;
import uk.gegc.quizmaker.service.quiz.ShareLinkService;
import uk.gegc.quizmaker.util.ShareLinkCookieManager;

import java.time.Duration;
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
            Authentication authentication) {
        
        log.info("Creating share link for quiz {} by user {}", quizId, authentication.getName());
        
        UUID userId = safeParseUuid(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("Invalid principal"));
        CreateShareLinkResponse response = shareLinkService.createShareLink(quizId, userId, request);
        
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
            Authentication authentication) {
        
        log.info("Revoking share link {} by user {}", tokenId, authentication.getName());
        
        UUID userId = safeParseUuid(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("Invalid principal"));
        shareLinkService.revokeShareLink(tokenId, userId);
        
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
        shareLinkService.recordShareLinkUsage(tokenHash, userAgent, ipAddress);
        
        // Set secure cookie for quiz access
        cookieManager.setShareLinkCookie(response, token, shareLink.quizId());
        
        log.info("Share link {} accessed successfully for quiz {}", shareLink.id(), shareLink.quizId());
        return ResponseEntity.ok(shareLink);
    }

    @GetMapping("/shared/{token}/consume")
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
        
        ShareLinkDto shareLink = shareLinkService.consumeOneTimeToken(token, userAgent, ipAddress);
        
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
        
        UUID userId = safeParseUuid(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("Invalid principal"));
        
        log.info("Retrieving share links for user {}", userId);
        
        List<ShareLinkDto> shareLinks = shareLinkService.getUserShareLinks(userId);
        
        log.info("Retrieved {} share links for user {}", shareLinks.size(), userId);
        return ResponseEntity.ok(shareLinks);
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
     * Gets the client IP address from the request, handling proxy headers.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
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
