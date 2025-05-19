package uk.gegc.quizmaker.service.auth;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.dto.auth.JwtResponse;
import uk.gegc.quizmaker.dto.auth.LoginRequest;
import uk.gegc.quizmaker.dto.auth.RefreshRequest;
import uk.gegc.quizmaker.dto.auth.RegisterRequest;
import uk.gegc.quizmaker.dto.user.UserDto;

public interface AuthService {
    UserDto register(@Valid RegisterRequest request);
    JwtResponse login(LoginRequest loginRequest);
    JwtResponse refresh(RefreshRequest refreshRequest);
    void logout(String token);
    UserDto getCurrentUser(Authentication authentication);
}
