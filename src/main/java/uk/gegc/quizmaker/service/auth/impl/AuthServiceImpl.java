package uk.gegc.quizmaker.service.auth.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.dto.auth.JwtResponse;
import uk.gegc.quizmaker.dto.auth.LoginRequest;
import uk.gegc.quizmaker.dto.auth.RefreshRequest;
import uk.gegc.quizmaker.dto.auth.RegisterRequest;
import uk.gegc.quizmaker.dto.user.UserDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.exception.UnauthorizedException;
import uk.gegc.quizmaker.mapper.UserMapper;
import uk.gegc.quizmaker.model.auth.PasswordResetToken;
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.model.user.RoleName;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.auth.PasswordResetTokenRepository;
import uk.gegc.quizmaker.repository.user.RoleRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.security.JwtTokenProvider;
import uk.gegc.quizmaker.service.EmailService;
import uk.gegc.quizmaker.service.auth.AuthService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import jakarta.annotation.PostConstruct;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;
    private final AuthenticationManager authManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailService emailService;
    
    @Value("${app.auth.reset-token-pepper}")
    private String resetTokenPepper;
    
    @Value("${app.auth.reset-token-ttl-minutes:60}")
    private long resetTokenTtlMinutes;

    @PostConstruct
    void verifyResetPepper() {
        if (resetTokenPepper == null || resetTokenPepper.isBlank()) {
            throw new IllegalStateException("app.auth.reset-token-pepper is not configured");
        }
    }

    @Override
    public UserDto register(RegisterRequest request) {

        if (userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already in use");
        }

        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setHashedPassword(passwordEncoder.encode(request.password()));
        user.setActive(true);

        Role userRole = roleRepository.findByRoleName(RoleName.ROLE_USER.name())
                .orElseThrow(() -> new IllegalStateException("ROLE_USER not found"));
        user.setRoles(Set.of(userRole));

        User saved = userRepository.save(user);
        return userMapper.toDto(saved);
    }

    @Override
    public JwtResponse login(LoginRequest loginRequest) {
        try {
            Authentication authentication = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.username(), loginRequest.password())
            );

            String accessToken = jwtTokenProvider.generateAccessToken(authentication);
            String refreshToken = jwtTokenProvider.generateRefreshToken(authentication);
            long accessExpiresInMs = jwtTokenProvider.getAccessTokenValidityInMs();
            long refreshExpiresInMs = jwtTokenProvider.getRefreshTokenValidityInMs();

            return new JwtResponse(accessToken, refreshToken, accessExpiresInMs, refreshExpiresInMs);
        } catch (AuthenticationException ex) {
            throw new UnauthorizedException("Invalid username or password");
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    @Override
    public JwtResponse refresh(RefreshRequest refreshRequest) {

        String token = refreshRequest.refreshToken();

        if (!jwtTokenProvider.validateToken(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        String type = jwtTokenProvider.getClaims(token).get("type", String.class);
        if (!"refresh".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token is not a refresh token");
        }

        Authentication authentication = jwtTokenProvider.getAuthentication(token);

        return new JwtResponse(jwtTokenProvider.generateAccessToken(authentication),
                token,
                jwtTokenProvider.getAccessTokenValidityInMs(),
                jwtTokenProvider.getRefreshTokenValidityInMs());
    }

    @Override
    public void logout(String token) {

    }

    @Override
    public UserDto getCurrentUser(Authentication authentication) {
        String username = authentication.getName();
        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User  " + username + " not found"));
        return userMapper.toDto(user);
    }

    @Override
    @Transactional
    public void generatePasswordResetToken(String email) {
        // Check if user exists (but don't reveal if they do or don't)
        Optional<User> userOpt = userRepository.findByEmail(email);
        
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            
            // Invalidate any existing tokens for this user
            passwordResetTokenRepository.invalidateUserTokens(user.getId());
            
            // Generate a secure random token
            String token = generateSecureToken();
            String tokenHash = hashToken(token);
            
            // Create and save the reset token
            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setTokenHash(tokenHash);
            resetToken.setUserId(user.getId());
            resetToken.setEmail(email);
            
            // Set expiresAt using configurable TTL
            LocalDateTime now = LocalDateTime.now();
            resetToken.setCreatedAt(now);
            resetToken.setExpiresAt(now.plusMinutes(resetTokenTtlMinutes));
            
            passwordResetTokenRepository.save(resetToken);
            
            // Send the reset email
            emailService.sendPasswordResetEmail(email, token);
        }
        // If user doesn't exist, we don't do anything (security through obscurity)
    }

    @Override
    @Transactional
    public void resetPassword(String token, String newPassword) {
        // Hash the provided token to match against stored hash
        String tokenHash = hashToken(token);
        
        // Find valid, unused, non-expired token
        Optional<PasswordResetToken> tokenOpt = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash, LocalDateTime.now());
        
        if (tokenOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token");
        }
        
        PasswordResetToken resetToken = tokenOpt.get();
        
        // Find the user
        Optional<User> userOpt = userRepository.findById(resetToken.getUserId());
        if (userOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reset token");
        }
        
        User user = userOpt.get();
        
        // Update the user's password
        user.setHashedPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        
        // Mark the token as used
        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);
    }
    
    private String generateSecureToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    
    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((resetTokenPepper + token).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
