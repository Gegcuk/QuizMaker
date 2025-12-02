package uk.gegc.quizmaker.features.auth.application.impl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.features.auth.domain.event.UserRegisteredEvent;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.api.dto.LoginRequest;
import uk.gegc.quizmaker.features.auth.api.dto.RefreshRequest;
import uk.gegc.quizmaker.features.auth.api.dto.RegisterRequest;
import uk.gegc.quizmaker.features.auth.application.AuthService;
import uk.gegc.quizmaker.features.auth.domain.model.EmailVerificationToken;
import uk.gegc.quizmaker.features.auth.domain.model.PasswordResetToken;
import uk.gegc.quizmaker.features.auth.domain.repository.EmailVerificationTokenRepository;
import uk.gegc.quizmaker.features.auth.domain.repository.PasswordResetTokenRepository;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.user.api.dto.AuthenticatedUserDto;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.user.infra.mapping.UserMapper;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.shared.email.EmailService;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.UnauthorizedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;
    private final AuthenticationManager authManager;
    private final JwtTokenService jwtTokenService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final EmailService emailService;
    private final ApplicationEventPublisher eventPublisher;
    @Qualifier("utcClock")
    private final Clock utcClock;
    
    @Value("${app.auth.reset-token-pepper}")
    private String resetTokenPepper;
    
    @Value("${app.auth.reset-token-ttl-minutes:60}")
    private long resetTokenTtlMinutes;

    @Value("${app.auth.verification-token-pepper}")
    private String verificationTokenPepper;
    
    @Value("${app.auth.verification-token-ttl-minutes:1440}")
    private long verificationTokenTtlMinutes;

    @Value("${app.auth.registration-bonus-tokens:100}")
    private long registrationBonusTokens;

    private static final String REGISTRATION_BONUS_REF = "registration-bonus";

    @PostConstruct
    void verifyResetPepper() {
        if (resetTokenPepper == null || resetTokenPepper.isBlank()) {
            throw new IllegalStateException("app.auth.reset-token-pepper is not configured");
        }
        if (verificationTokenPepper == null || verificationTokenPepper.isBlank()) {
            throw new IllegalStateException("app.auth.verification-token-pepper is not configured");
        }
    }

    @Override
    public AuthenticatedUserDto register(RegisterRequest request) {

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
        user.setPasswordChangedAt(LocalDateTime.now(utcClock));
        user.setActive(true);
        user.setEmailVerified(false); // New users start with unverified email

        Role userRole = roleRepository.findByRoleName(RoleName.ROLE_USER.name())
                .orElseThrow(() -> new IllegalStateException("ROLE_USER not found"));
        Role quizCreatorRole = roleRepository.findByRoleName(RoleName.ROLE_QUIZ_CREATOR.name())
                .orElseThrow(() -> new IllegalStateException("ROLE_QUIZ_CREATOR not found"));
        user.setRoles(Set.of(userRole, quizCreatorRole));

        User saved = userRepository.save(user);
        
        // Publish event to credit registration bonus tokens after transaction commits
        // This avoids lock conflicts by running the bonus credit in a separate transaction after commit
        eventPublisher.publishEvent(new UserRegisteredEvent(this, saved.getId()));
        
        // Send email verification
        generateEmailVerificationToken(saved.getEmail());
        
        return userMapper.toDto(saved);
    }

    @Override
    public JwtResponse login(LoginRequest loginRequest) {
        try {
            Authentication authentication = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.username(), loginRequest.password())
            );

            String accessToken = jwtTokenService.generateAccessToken(authentication);
            String refreshToken = jwtTokenService.generateRefreshToken(authentication);
            long accessExpiresInMs = jwtTokenService.getAccessTokenValidityInMs();
            long refreshExpiresInMs = jwtTokenService.getRefreshTokenValidityInMs();

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

        if (!jwtTokenService.validateToken(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        String type = jwtTokenService.getClaims(token).get("type", String.class);
        if (!"refresh".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token is not a refresh token");
        }

        Authentication authentication = jwtTokenService.getAuthentication(token);

        return new JwtResponse(jwtTokenService.generateAccessToken(authentication),
                token,
                jwtTokenService.getAccessTokenValidityInMs(),
                jwtTokenService.getRefreshTokenValidityInMs());
    }

    @Override
    public void logout(String token) {

    }

    @Override
    public AuthenticatedUserDto getCurrentUser(Authentication authentication) {
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
            
            // Set expiresAt using configurable TTL (UTC)
            LocalDateTime now = LocalDateTime.now(utcClock);
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
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash, LocalDateTime.now(utcClock));
        
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
        user.setPasswordChangedAt(LocalDateTime.now(utcClock));
        userRepository.save(user);
        
        // Mark the token as used
        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);
    }

    @Override
    @Transactional
    public void changePassword(String usernameOrEmail, String currentPassword, String newPassword) {
        if (usernameOrEmail == null || usernameOrEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        User user = userRepository.findByUsername(usernameOrEmail)
                .or(() -> userRepository.findByEmail(usernameOrEmail))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (!passwordEncoder.matches(currentPassword, user.getHashedPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }

        user.setHashedPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(LocalDateTime.now(utcClock));
        userRepository.save(user);
    }

    @Override
    @Transactional
    public LocalDateTime verifyEmail(String token) {
        // Hash the provided token to match against stored hash
        String tokenHash = hashVerificationToken(token);
        LocalDateTime now = LocalDateTime.now(utcClock);
        
        // Find valid, unused, non-expired token
        Optional<EmailVerificationToken> tokenOpt = emailVerificationTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash, now);
        
        if (tokenOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification token");
        }
        
        EmailVerificationToken verificationToken = tokenOpt.get();
        
        // Find the user first to check if already verified
        Optional<User> userOpt = userRepository.findById(verificationToken.getUserId());
        if (userOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification token");
        }
        
        User user = userOpt.get();
        
        // Verify that the token is for the current user's email (prevent email change attacks)
        if (!user.getEmail().equals(verificationToken.getEmail())) {
            // Token is for a different email - invalidate all tokens for this user and reject
            emailVerificationTokenRepository.invalidateUserTokens(user.getId());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification token");
        }
        
        // If user is already verified, mark token as used and return existing timestamp (idempotent behavior)
        if (user.isEmailVerified()) {
            // Still mark the token as used to prevent reuse
            emailVerificationTokenRepository.markUsedIfValid(verificationToken.getId(), now);
            // Invalidate any other outstanding tokens for this user
            emailVerificationTokenRepository.invalidateUserTokens(user.getId());
            return user.getEmailVerifiedAt(); // User is already verified, return existing timestamp
        }
        
        // Atomically mark the token as used to prevent race conditions
        int updated = emailVerificationTokenRepository.markUsedIfValid(verificationToken.getId(), now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification token");
        }
        
        // Mark the user's email as verified
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(now);
        user.setEmailVerifiedByTokenId(verificationToken.getId());
        userRepository.save(user);
        
        // Invalidate all other verification tokens for this user
        emailVerificationTokenRepository.invalidateUserTokens(user.getId());
        
        return now;
    }

    @Override
    @Transactional
    public void generateEmailVerificationToken(String email) {
        // Check if user exists (but don't reveal if they do or don't)
        Optional<User> userOpt = userRepository.findByEmail(email);
        
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            
            // Only send verification if email is not already verified
            if (!user.isEmailVerified()) {
                // Invalidate any existing tokens for this user
                emailVerificationTokenRepository.invalidateUserTokens(user.getId());
                
                // Generate a secure random token
                String token = generateSecureToken();
                String tokenHash = hashVerificationToken(token);
                
                // Create and save the verification token
                EmailVerificationToken verificationToken = new EmailVerificationToken();
                verificationToken.setTokenHash(tokenHash);
                verificationToken.setUserId(user.getId());
                verificationToken.setEmail(email);
                
                // Set expiresAt using configurable TTL (UTC)
                LocalDateTime now = LocalDateTime.now(utcClock);
                verificationToken.setCreatedAt(now);
                verificationToken.setExpiresAt(now.plusMinutes(verificationTokenTtlMinutes));
                
                emailVerificationTokenRepository.save(verificationToken);
                
                // Send the verification email
                emailService.sendEmailVerificationEmail(email, token);
            }
        }
        // If user doesn't exist, we don't do anything (security through obscurity)
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

    private String hashVerificationToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((verificationTokenPepper + token).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
