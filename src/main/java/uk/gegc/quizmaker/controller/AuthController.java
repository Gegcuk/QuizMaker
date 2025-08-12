package uk.gegc.quizmaker.controller;

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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.auth.ForgotPasswordRequest;
import uk.gegc.quizmaker.dto.auth.ForgotPasswordResponse;
import uk.gegc.quizmaker.dto.auth.JwtResponse;
import uk.gegc.quizmaker.dto.auth.LoginRequest;
import uk.gegc.quizmaker.dto.auth.RefreshRequest;
import uk.gegc.quizmaker.dto.auth.RegisterRequest;
import uk.gegc.quizmaker.dto.auth.ResetPasswordRequest;
import uk.gegc.quizmaker.dto.auth.ResetPasswordResponse;
import uk.gegc.quizmaker.dto.auth.VerifyEmailRequest;
import uk.gegc.quizmaker.dto.auth.VerifyEmailResponse;
import uk.gegc.quizmaker.dto.auth.ResendVerificationRequest;
import uk.gegc.quizmaker.dto.auth.ResendVerificationResponse;
import uk.gegc.quizmaker.dto.user.UserDto;
import uk.gegc.quizmaker.service.RateLimitService;
import uk.gegc.quizmaker.service.auth.AuthService;

import java.util.Optional;

@Tag(name = "Authentication", description = "Endpoints for registering, logging in, refreshing tokens, logout, and fetching current user")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RateLimitService rateLimitService;

    @Operation(
            summary = "Register a new user",
            description = "Creates a new user account. Returns the created user's details."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User successfully registered"),
            @ApiResponse(responseCode = "400", description = "Validation errors"),
            @ApiResponse(responseCode = "409", description = "Username or email already in use")
    })
    @PostMapping("/register")
    public ResponseEntity<UserDto> register(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Registration information",
                    required = true,
                    content = @Content(schema = @Schema(implementation = RegisterRequest.class))
            )
            @Valid @RequestBody RegisterRequest request
    ) {
        UserDto createdUser = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    @Operation(
            summary = "Log in",
            description = "Authenticates a user and returns access and refresh JWT tokens."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials")
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
            @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token")
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
            @ApiResponse(responseCode = "401", description = "Invalid or missing token")
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
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/me")
    public ResponseEntity<UserDto> me(
            Authentication authentication
    ) {
        return ResponseEntity.ok(authService.getCurrentUser(authentication));
    }

    @Operation(
            summary = "Forgot password",
            description = "Initiates a password reset process. If the email exists, a reset link will be sent."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Request accepted"),
            @ApiResponse(responseCode = "400", description = "Invalid email format"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
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
        // Get client IP (respecting X-Forwarded-For header)
        String clientIp = Optional.ofNullable(httpRequest.getHeader("X-Forwarded-For"))
                .map(x -> x.split(",")[0].trim())
                .orElse(httpRequest.getRemoteAddr());
        
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
            @ApiResponse(responseCode = "400", description = "Invalid or expired token"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
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
        // Get client IP (respecting X-Forwarded-For header)
        String clientIp = Optional.ofNullable(httpRequest.getHeader("X-Forwarded-For"))
                .map(x -> x.split(",")[0].trim())
                .orElse(httpRequest.getRemoteAddr());
        
        // Rate limiting check by IP + token
        rateLimitService.checkRateLimit("reset-password", clientIp + "|" + token);
        
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
            @ApiResponse(responseCode = "400", description = "Invalid or expired token")
    })
    @PostMapping("/verify-email")
    public ResponseEntity<VerifyEmailResponse> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request) {
        
        authService.verifyEmail(request.token());
        
        return ResponseEntity.ok(new VerifyEmailResponse(true, "Email verified successfully"));
    }

    @Operation(
            summary = "Resend verification email",
            description = "Resends email verification link if email exists and is not verified."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "If the email exists and is not verified, a verification link was sent"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<ResendVerificationResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request,
            HttpServletRequest httpRequest) {
        
        String ip = Optional.ofNullable(httpRequest.getHeader("X-Forwarded-For"))
                .map(x -> x.split(",")[0].trim())
                .orElse(httpRequest.getRemoteAddr());
        
        // Rate limiting check with IP + email
        rateLimitService.checkRateLimit("resend-verification", request.email() + "|" + ip);
        
        // Generate verification token (if email exists and not verified)
        authService.generateEmailVerificationToken(request.email());
        
        return ResponseEntity.accepted()
                .body(new ResendVerificationResponse("If the email exists and is not verified, a verification link was sent."));
    }
}