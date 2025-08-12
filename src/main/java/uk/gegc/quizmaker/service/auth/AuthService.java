package uk.gegc.quizmaker.service.auth;

import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.dto.auth.*;
import uk.gegc.quizmaker.dto.user.UserDto;

public interface AuthService {
    UserDto register(RegisterRequest request);
    JwtResponse login(LoginRequest request);
    JwtResponse refresh(RefreshRequest request);
    void logout(String token);
    UserDto getCurrentUser(Authentication authentication);
    void generatePasswordResetToken(String email);
    void resetPassword(String token, String newPassword);
    void verifyEmail(String token);
    void generateEmailVerificationToken(String email);
}
