package uk.gegc.quizmaker.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.auth.JwtResponse;
import uk.gegc.quizmaker.dto.auth.LoginRequest;
import uk.gegc.quizmaker.dto.auth.RefreshRequest;
import uk.gegc.quizmaker.dto.auth.RegisterRequest;
import uk.gegc.quizmaker.dto.user.UserDto;
import uk.gegc.quizmaker.service.auth.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@Valid @RequestBody RegisterRequest request){
        UserDto createdUser = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    @PostMapping("/login")
    public ResponseEntity<JwtResponse> login(@Valid @RequestBody LoginRequest request){
        JwtResponse tokens = authService.login(request);
        return ResponseEntity.ok(tokens);
    }

    @PostMapping("/refresh")
    public ResponseEntity<JwtResponse> refresh(@RequestBody RefreshRequest refreshRequest){
        return ResponseEntity.ok(authService.refresh(refreshRequest));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String header
    ){
        String token = header.replaceFirst("^Bearer\\s+", "");
        authService.logout(token);
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> me(
            Authentication authentication
            ){
        return ResponseEntity.ok(authService.getCurrentUser(authentication));
    }

}
