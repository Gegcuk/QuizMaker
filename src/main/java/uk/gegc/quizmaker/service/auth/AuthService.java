package uk.gegc.quizmaker.service.auth;

import jakarta.validation.Valid;
import uk.gegc.quizmaker.dto.auth.RegisterRequest;
import uk.gegc.quizmaker.dto.user.UserDto;

public interface AuthService {
    UserDto register(@Valid RegisterRequest request);
}
