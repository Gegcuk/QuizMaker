package uk.gegc.quizmaker.features.auth.application;

import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.api.dto.LoginRequest;
import uk.gegc.quizmaker.features.auth.api.dto.RefreshRequest;
import uk.gegc.quizmaker.features.auth.api.dto.RegisterRequest;
import uk.gegc.quizmaker.features.user.api.dto.AuthenticatedUserDto;

import java.time.LocalDateTime;

public interface AuthService {
    AuthenticatedUserDto register(RegisterRequest request);
    JwtResponse login(LoginRequest request);
    JwtResponse refresh(RefreshRequest request);
    void logout(String token);
    AuthenticatedUserDto getCurrentUser(Authentication authentication);
    void generatePasswordResetToken(String email);
    void resetPassword(String token, String newPassword);
    LocalDateTime verifyEmail(String token);
    void generateEmailVerificationToken(String email);
}
