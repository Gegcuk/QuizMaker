package uk.gegc.quizmaker.features.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.features.auth.api.dto.*;
import uk.gegc.quizmaker.features.auth.application.AuthService;
import uk.gegc.quizmaker.features.user.api.dto.AuthenticatedUserDto;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Tag(name = "Authentication", 
     description = "Endpoints for registering, logging in, refreshing tokens, logout, and fetching current user. " +
                   "For OAuth social login (Google, GitHub, Facebook, Microsoft), see the 'OAuth Account Management' section.")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RateLimitService rateLimitService;
    private final TrustedProxyUtil trustedProxyUtil;

    @Operation(
            summary = "Register a new user",
            description = "Creates a new user account with email and password. Returns the created user's details. " +
                         "<p><b>Alternative:</b> Users can also register instantly via OAuth social login " +
                         "by redirecting to <code>/oauth2/authorization/{provider}</code> (google, github, facebook, microsoft).</p>"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User successfully registered"),
            @ApiResponse(responseCode = "400", description = "Validation errors",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Username or email already in use",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/register")
    public ResponseEntity<AuthenticatedUserDto> register(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Registration information",
                    required = true,
                    content = @Content(schema = @Schema(implementation = RegisterRequest.class))
            )
            @Valid @RequestBody RegisterRequest request
    ) {
        AuthenticatedUserDto createdUser = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    @Operation(
            summary = "Log in",
            description = "Authenticates a user with email/username and password, returns access and refresh JWT tokens. " +
                         "<p><b>Alternative - OAuth Social Login:</b> Users can login via Google, GitHub, Facebook, or Microsoft " +
                         "by redirecting to <code>/oauth2/authorization/{provider}</code>. After OAuth authentication, " +
                         "they'll be redirected back to your frontend with JWT tokens in the URL query parameters.</p>"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/login")
    public ResponseEntity<JwtResponse> login(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Username (or email) and password",
                    required = true,
                    content = @Content(schema = @Schema(implementation = LoginRequest.class))
            )
            @Valid @RequestBody LoginRequest request
    ) {
        JwtResponse tokens = authService.login(request);
        return ResponseEntity.ok(tokens);
    }

    @Operation(
            summary = "Refresh tokens",
            description = "Exchanges a valid refresh token for a new access token (and optionally a new refresh token)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens refreshed"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/refresh")
    public ResponseEntity<JwtResponse> refresh(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Refresh token payload",
                    required = true,
                    content = @Content(schema = @Schema(implementation = RefreshRequest.class))
            )
            @RequestBody RefreshRequest refreshRequest
    ) {
        return ResponseEntity.ok(authService.refresh(refreshRequest));
    }

    @Operation(
            summary = "Logout",
            description = "Revokes the provided access token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Logout successful"),
            @ApiResponse(responseCode = "401", description = "Invalid or missing token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/logout")
    public void logout(
            @Parameter(
                    description = "Bearer access token",
                    in = ParameterIn.HEADER,
                    name = HttpHeaders.AUTHORIZATION,
                    required = true,
                    schema = @Schema(type = "string", example = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
            )
            @RequestHeader(HttpHeaders.AUTHORIZATION) String header
    ) {
        String token = header.replaceFirst("^Bearer\\s+", "");
        authService.logout(token);
    }

    @Operation(
            summary = "Get current user",
            description = "Returns details of the authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current user retrieved"),
            @ApiResponse(responseCode = "401", description = "Not authenticated",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/me")
    public ResponseEntity<AuthenticatedUserDto> me(
            Authentication authentication
    ) {
        return ResponseEntity.ok(authService.getCurrentUser(authentication));
    }

    @Operation(
            summary = "Change password",
            description = "Allows an authenticated user to change their password by providing the current password."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password changed successfully"),
            @ApiResponse(responseCode = "400", description = "Validation errors or incorrect current password",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Not authenticated",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/change-password")
    public ResponseEntity<ChangePasswordResponse> changePassword(
            Authentication authentication,
            HttpServletRequest httpRequest,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        String clientIp = trustedProxyUtil.getClientIp(httpRequest);
        String rateLimitKey = authentication.getName() + "|" + clientIp;
        rateLimitService.checkRateLimit("change-password", rateLimitKey, 3);

        authService.changePassword(authentication.getName(), request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(new ChangePasswordResponse("Password updated successfully"));
    }

    @Operation(
            summary = "Forgot password",
            description = "Initiates a password reset process. If the email exists, a reset link will be sent."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Request accepted"),
            @ApiResponse(responseCode = "400", description = "Invalid email format",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/forgot-password")
    public ResponseEntity<ForgotPasswordResponse> forgotPassword(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Email address for password reset",
                    required = true,
                    content = @Content(schema = @Schema(implementation = ForgotPasswordRequest.class))
            )
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest
    ) {
        // Get client IP from trusted proxy
        String clientIp = trustedProxyUtil.getClientIp(httpRequest);
        
        // Rate limiting check by email + IP
        rateLimitService.checkRateLimit("forgot-password", request.email() + "|" + clientIp);
        
        // Generate reset token (if email exists)
        authService.generatePasswordResetToken(request.email());
        
        return ResponseEntity.accepted()
                .body(new ForgotPasswordResponse("If the email exists, a reset link was sent."));
    }

    @Operation(
            summary = "Reset password",
            description = "Resets user password using a valid reset token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password reset successful"),
            @ApiResponse(responseCode = "400", description = "Invalid or expired token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/reset-password")
    public ResponseEntity<ResetPasswordResponse> resetPassword(
            @Parameter(
                    description = "Reset token from email link",
                    in = ParameterIn.QUERY,
                    required = true,
                    schema = @Schema(type = "string", example = "l7UumEXn0GtNrrBQRg7kWGdOmP7WkTHUbqkENk2U1Oo")
            )
            @RequestParam String token,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "New password",
                    required = true,
                    content = @Content(schema = @Schema(implementation = ResetPasswordRequest.class))
            )
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest
    ) {
        // Get client IP from trusted proxy
        String clientIp = trustedProxyUtil.getClientIp(httpRequest);
        
        // Rate limiting check scoped to caller IP to prevent bucket rotation via token parameter
        rateLimitService.checkRateLimit("reset-password", clientIp);
        
        // Reset the password
        authService.resetPassword(token, request.newPassword());
        
        return ResponseEntity.ok(new ResetPasswordResponse("Password updated successfully"));
    }

    @Operation(
            summary = "Verify email",
            description = "Verifies user email using a verification token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email verified successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid or expired token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/verify-email")
    public ResponseEntity<VerifyEmailResponse> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request,
            HttpServletRequest httpRequest    ) {
        
        // Get client IP from trusted proxy
        String clientIp = trustedProxyUtil.getClientIp(httpRequest);
        
        // Rate limiting combines caller IP with token fingerprint so users behind shared IPs don't throttle each other
        String tokenFingerprint = fingerprintToken(request.token());
        rateLimitService.checkRateLimit("verify-email", clientIp + "|" + tokenFingerprint);
        
        LocalDateTime verifiedAt = authService.verifyEmail(request.token());
        
        return ResponseEntity.ok(new VerifyEmailResponse(true, "Email verified successfully", verifiedAt));
    }

    @Operation(
            summary = "Resend verification email",
            description = "Resends email verification link if email exists and is not verified."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "If the email exists and is not verified, a verification link was sent"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<ResendVerificationResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request,
            HttpServletRequest httpRequest    ) {
        
        String clientIp = trustedProxyUtil.getClientIp(httpRequest);
        
        // Rate limiting check with IP + email
        rateLimitService.checkRateLimit("resend-verification", request.email() + "|" + clientIp);
        
        // Generate verification token (if email exists and not verified)
        authService.generateEmailVerificationToken(request.email());
        
        return ResponseEntity.accepted()
                .body(new ResendVerificationResponse("If the email exists and is not verified, a verification link was sent."));
    }

    private String fingerprintToken(String token) {
        if (token == null || token.isBlank()) {
            return "missing";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
