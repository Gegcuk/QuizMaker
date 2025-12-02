package uk.gegc.quizmaker.service.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.api.dto.LoginRequest;
import uk.gegc.quizmaker.features.auth.api.dto.RefreshRequest;
import uk.gegc.quizmaker.features.auth.api.dto.RegisterRequest;
import uk.gegc.quizmaker.features.auth.application.impl.AuthServiceImpl;
import uk.gegc.quizmaker.features.auth.domain.model.PasswordResetToken;
import uk.gegc.quizmaker.features.auth.domain.repository.PasswordResetTokenRepository;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.user.api.dto.AuthenticatedUserDto;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.user.infra.mapping.UserMapper;
import uk.gegc.quizmaker.features.auth.domain.repository.EmailVerificationTokenRepository;
import uk.gegc.quizmaker.shared.email.EmailService;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.UnauthorizedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private AuthenticationManager authManager;
    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private Clock utcClock;

    @InjectMocks
    private AuthServiceImpl authService;

    private User testUser;
    private UUID userId;
    private LocalDateTime fixedNow;
    private Instant fixedInstant;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = new User();
        testUser.setId(userId);
        testUser.setEmail("test@example.com");
        testUser.setUsername("testuser");
        fixedInstant = Instant.parse("2024-01-01T00:00:00Z");
        fixedNow = LocalDateTime.ofInstant(fixedInstant, ZoneOffset.UTC);
        // Use lenient stubbing for clock to avoid unnecessary stubbing warnings in tests that don't use it
        lenient().when(utcClock.instant()).thenReturn(fixedInstant);
        lenient().when(utcClock.getZone()).thenReturn(ZoneOffset.UTC);
        
        // Mock TransactionTemplate to execute callbacks immediately (for REQUIRES_NEW transaction)
        when(transactionTemplate.getTransactionManager()).thenReturn(transactionManager);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        doAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallbackWithoutResult callback = invocation.getArgument(0);
            callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        
        // Set @Value field that Spring would inject in real context
        ReflectionTestUtils.setField(authService, "registrationBonusTokens", 100L);
    }

    @Test
    @DisplayName("register: saves new user and returns AuthenticatedUserDto")
    void register_happy() {
        var req = new RegisterRequest("john", "john@example.com", "secret123");
        when(userRepository.existsByUsername("john")).thenReturn(false);
        when(userRepository.existsByEmail("john@example.com")).thenReturn(false);

        Role userRole = Role.builder()
                .roleId(1L)
                .roleName(RoleName.ROLE_USER.name())
                .build();
        Role quizCreatorRole = Role.builder()
                .roleId(2L)
                .roleName(RoleName.ROLE_QUIZ_CREATOR.name())
                .build();
        
        when(roleRepository.findByRoleName(RoleName.ROLE_USER.name()))
                .thenReturn(Optional.of(userRole));
        when(roleRepository.findByRoleName(RoleName.ROLE_QUIZ_CREATOR.name()))
                .thenReturn(Optional.of(quizCreatorRole));

        when(passwordEncoder.encode("secret123")).thenReturn("hashedPwd");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        User saved = new User();
        saved.setId(UUID.randomUUID());
        saved.setUsername("john");
        saved.setEmail("john@example.com");
        saved.setActive(true);
        saved.setRoles(Set.of(userRole, quizCreatorRole));
        saved.setCreatedAt(LocalDateTime.now());
        saved.setLastLoginDate(null);
        saved.setUpdatedAt(null);

        when(userRepository.save(captor.capture())).thenReturn(saved);

        AuthenticatedUserDto expectedDto = new AuthenticatedUserDto(
                saved.getId(),
                "john",
                "john@example.com",
                true,
                Set.of(RoleName.ROLE_USER, RoleName.ROLE_QUIZ_CREATOR),
                saved.getCreatedAt(),
                saved.getLastLoginDate(),
                saved.getUpdatedAt()
        );
        when(userMapper.toDto(saved)).thenReturn(expectedDto);
        // Use lenient stubbing for email verification methods that are called but not directly verified
        lenient().doNothing().when(emailVerificationTokenRepository).invalidateUserTokens(any());
        lenient().when(emailVerificationTokenRepository.save(any())).thenReturn(null);
        lenient().doNothing().when(emailService).sendEmailVerificationEmail(any(), any());

        AuthenticatedUserDto result = authService.register(req);

        assertEquals(expectedDto, result);
        // Verify that UserRegisteredEvent is published
        verify(eventPublisher).publishEvent(argThat(event -> {
            if (event instanceof uk.gegc.quizmaker.features.auth.domain.event.UserRegisteredEvent) {
                return ((uk.gegc.quizmaker.features.auth.domain.event.UserRegisteredEvent) event).getUserId().equals(saved.getId());
            }
            return false;
        }));
        User passed = captor.getValue();
        assertEquals("john", passed.getUsername());
        assertEquals("john@example.com", passed.getEmail());
        assertEquals("hashedPwd", passed.getHashedPassword());
        assertTrue(passed.isActive());
        assertTrue(passed.getRoles().contains(userRole));
        assertTrue(passed.getRoles().contains(quizCreatorRole));
    }

    @Test
    @DisplayName("register: duplicate username yields 409")
    void register_duplicateUsername() {
        var req = new RegisterRequest("john", "john@example.com", "secret123");
        when(userRepository.existsByUsername("john")).thenReturn(true);

        var ex = assertThrows(ResponseStatusException.class,
                () -> authService.register(req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    @DisplayName("register: duplicate email yields 409")
    void register_duplicateEmail() {
        var req = new RegisterRequest("john", "john@example.com", "secret123");
        when(userRepository.existsByUsername("john")).thenReturn(false);
        when(userRepository.existsByEmail("john@example.com")).thenReturn(true);

        var ex = assertThrows(ResponseStatusException.class,
                () -> authService.register(req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    @DisplayName("login: correct credentials returns tokens")
    void login_happy() {
        var req = new LoginRequest("john", "pass");
        Authentication auth = mock(Authentication.class);
        when(authManager.authenticate(any()))
                .thenReturn(auth);
        when(jwtTokenService.generateAccessToken(auth))
                .thenReturn("access.jwt");
        when(jwtTokenService.generateRefreshToken(auth))
                .thenReturn("refresh.jwt");
        when(jwtTokenService.getAccessTokenValidityInMs())
                .thenReturn(1000L);
        when(jwtTokenService.getRefreshTokenValidityInMs())
                .thenReturn(5000L);

        JwtResponse resp = authService.login(req);

        assertEquals("access.jwt", resp.accessToken());
        assertEquals("refresh.jwt", resp.refreshToken());
        assertEquals(1000L, resp.accessExpiresInMs());
        assertEquals(5000L, resp.refreshExpiresInMs());
    }

    @Test
    @DisplayName("login: invalid credentials yields UnauthorizedException")
    void login_badCredits() {
        var req = new LoginRequest("john", "wrong");
        when(authManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad creds"));

        UnauthorizedException ex = assertThrows(
                UnauthorizedException.class,
                () -> authService.login(req)
        );
        assertEquals("Invalid username or password", ex.getMessage());
    }

    @Test
    @DisplayName("refresh: valid refresh token returns new access token")
    void refresh_happy() {
        var req = new RefreshRequest("valid.token");
        when(jwtTokenService.validateToken("valid.token")).thenReturn(true);
        Claims claims = mock(Claims.class);
        when(claims.get("type", String.class)).thenReturn("refresh");
        when(jwtTokenService.getClaims("valid.token")).thenReturn(claims);

        Authentication auth = mock(Authentication.class);
        when(jwtTokenService.getAuthentication("valid.token")).thenReturn(auth);
        when(jwtTokenService.generateAccessToken(auth)).thenReturn("new.access");
        when(jwtTokenService.getAccessTokenValidityInMs()).thenReturn(1234L);
        when(jwtTokenService.getRefreshTokenValidityInMs()).thenReturn(5678L);

        JwtResponse out = authService.refresh(req);

        assertEquals("new.access", out.accessToken());
        assertEquals("valid.token", out.refreshToken());
        assertEquals(1234L, out.accessExpiresInMs());
        assertEquals(5678L, out.refreshExpiresInMs());
    }

    @Test
    @DisplayName("refresh: invalid token yields 401")
    void refresh_invalidToken() {
        when(jwtTokenService.validateToken("bad")).thenReturn(false);
        var ex = assertThrows(ResponseStatusException.class,
                () -> authService.refresh(new RefreshRequest("bad")));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    @DisplayName("refresh: wrong token type yields 400")
    void refresh_wrongType() {
        when(jwtTokenService.validateToken("t")).thenReturn(true);
        Claims claims = mock(Claims.class);
        when(claims.get("type", String.class)).thenReturn("access");
        when(jwtTokenService.getClaims("t")).thenReturn(claims);

        var ex = assertThrows(ResponseStatusException.class,
                () -> authService.refresh(new RefreshRequest("t")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("logout: always succeeds (no-op)")
    void logout_noException() {
        assertDoesNotThrow(() -> authService.logout("any.token"));
    }

    @Test
    @DisplayName("getCurrentUser: returns AuthenticatedUserDto when found")
    void getCurrentUser_happy() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("john");
        User user = new User();
        user.setUsername("john");
        when(userRepository.findByUsername("john"))
                .thenReturn(Optional.of(user));

        AuthenticatedUserDto dto = new AuthenticatedUserDto(
                UUID.randomUUID(),
                "john",
                "john@example.com",
                true,
                Set.of(RoleName.ROLE_USER),
                LocalDateTime.now(),
                null,
                null
        );
        when(userMapper.toDto(user)).thenReturn(dto);

        AuthenticatedUserDto out = authService.getCurrentUser(auth);
        assertEquals(dto, out);
    }

    @Test
    @DisplayName("getCurrentUser: missing user yields ResourceNotFoundException")
    void getCurrentUser_notFound() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("noone");
        when(userRepository.findByUsername("noone"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("noone"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> authService.getCurrentUser(auth));
    }

    @Test
    void generatePasswordResetToken_UserExists_CreatesTokenAndSendsEmail() {
        // Given
        String email = "test@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(testUser));
        when(passwordResetTokenRepository.save(any())).thenReturn(null);
        doNothing().when(passwordResetTokenRepository).invalidateUserTokens(userId);
        doNothing().when(emailService).sendPasswordResetEmail(eq(email), any());

        // When
        authService.generatePasswordResetToken(email);

        // Then
        verify(userRepository).findByEmail(email);
        verify(passwordResetTokenRepository).invalidateUserTokens(userId);
        verify(passwordResetTokenRepository).save(any());
        verify(emailService).sendPasswordResetEmail(eq(email), any());
    }

    @Test
    @DisplayName("resetPassword: valid token and password should update user password and mark token as used")
    void resetPassword_ValidToken_UpdatesPasswordAndMarksTokenUsed() {
        // Given
        String token = "valid-reset-token";
        String newPassword = "NewP@ssw0rd123!";
        String tokenHash = "test_pepper" + token; // This is how the hashToken method works
        
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setTokenHash(tokenHash);
        resetToken.setUserId(userId);
        resetToken.setEmail("test@example.com");
        resetToken.setUsed(false);
        resetToken.setExpiresAt(LocalDateTime.now().plusHours(1));
        
        when(passwordResetTokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                any(String.class), any(LocalDateTime.class)))
                .thenReturn(Optional.of(resetToken));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.encode(newPassword)).thenReturn("encoded-new-password");
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class))).thenReturn(resetToken);
        when(userRepository.save(any(User.class))).thenReturn(testUser);

        // When
        authService.resetPassword(token, newPassword);

        // Then
        verify(passwordResetTokenRepository).findByTokenHashAndUsedFalseAndExpiresAtAfter(
                any(String.class), any(LocalDateTime.class));
        verify(userRepository).findById(userId);
        verify(passwordEncoder).encode(newPassword);
        verify(userRepository).save(testUser);
        verify(passwordResetTokenRepository).save(resetToken);
        
        // Verify token is marked as used
        assertTrue(resetToken.isUsed());
        // Verify user password is updated
        assertEquals("encoded-new-password", testUser.getHashedPassword());
    }

    @Test
    @DisplayName("resetPassword: invalid token should throw ResponseStatusException")
    void resetPassword_InvalidToken_ThrowsException() {
        // Given
        String token = "invalid-token";
        String newPassword = "NewP@ssw0rd123!";
        
        when(passwordResetTokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                any(String.class), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> authService.resetPassword(token, newPassword));
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Invalid or expired reset token", exception.getReason());
        
        verify(passwordResetTokenRepository).findByTokenHashAndUsedFalseAndExpiresAtAfter(
                any(String.class), any(LocalDateTime.class));
        verify(userRepository, never()).findById(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("resetPassword: expired token should throw ResponseStatusException")
    void resetPassword_ExpiredToken_ThrowsException() {
        // Given
        String token = "expired-token";
        String newPassword = "NewP@ssw0rd123!";
        
        when(passwordResetTokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                any(String.class), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> authService.resetPassword(token, newPassword));
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Invalid or expired reset token", exception.getReason());
    }

    @Test
    @DisplayName("resetPassword: used token should throw ResponseStatusException")
    void resetPassword_UsedToken_ThrowsException() {
        // Given
        String token = "used-token";
        String newPassword = "NewP@ssw0rd123!";
        
        when(passwordResetTokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                any(String.class), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> authService.resetPassword(token, newPassword));
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Invalid or expired reset token", exception.getReason());
    }

    @Test
    @DisplayName("resetPassword: user not found should throw ResponseStatusException")
    void resetPassword_UserNotFound_ThrowsException() {
        // Given
        String token = "valid-token";
        String newPassword = "NewP@ssw0rd123!";
        
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setTokenHash("test_pepper" + token);
        resetToken.setUserId(userId);
        resetToken.setUsed(false);
        resetToken.setExpiresAt(LocalDateTime.now().plusHours(1));
        
        when(passwordResetTokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                any(String.class), any(LocalDateTime.class)))
                .thenReturn(Optional.of(resetToken));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> authService.resetPassword(token, newPassword));
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Invalid reset token", exception.getReason());
        
        verify(passwordResetTokenRepository).findByTokenHashAndUsedFalseAndExpiresAtAfter(
                any(String.class), any(LocalDateTime.class));
        verify(userRepository).findById(userId);
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void generatePasswordResetToken_UserDoesNotExist_DoesNothing() {
        // Given
        String email = "nonexistent@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        // When
        authService.generatePasswordResetToken(email);

        // Then
        verify(userRepository).findByEmail(email);
        verify(passwordResetTokenRepository, never()).invalidateUserTokens(any());
        verify(passwordResetTokenRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    void generatePasswordResetToken_ValidatesTokenGeneration() {
        // Given
        String email = "test@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(testUser));
        when(passwordResetTokenRepository.save(any())).thenReturn(null);
        doNothing().when(passwordResetTokenRepository).invalidateUserTokens(userId);
        doNothing().when(emailService).sendPasswordResetEmail(eq(email), any());

        // When
        authService.generatePasswordResetToken(email);

        // Then
        verify(passwordResetTokenRepository).save(argThat(token -> {
            return token.getUserId().equals(userId) &&
                   token.getEmail().equals(email) &&
                   !token.isUsed() &&
                   token.getTokenHash() != null;
        }));
    }

    @Test
    @DisplayName("changePassword: updates password when current password matches")
    void changePassword_happyPath_updatesPassword() {
        testUser.setHashedPassword("old-hash");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("current", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-secret")).thenReturn("new-hash");

        authService.changePassword("testuser", "current", "new-secret");

        verify(passwordEncoder).matches("current", "old-hash");
        verify(passwordEncoder).encode("new-secret");
        verify(userRepository).save(testUser);
        assertEquals("new-hash", testUser.getHashedPassword());
        assertEquals(fixedNow, testUser.getPasswordChangedAt());
    }

    @Test
    @DisplayName("changePassword: throws BAD_REQUEST when current password is incorrect")
    void changePassword_incorrectCurrentPassword_throws() {
        testUser.setHashedPassword("old-hash");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrong", "old-hash")).thenReturn(false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> authService.changePassword("testuser", "wrong", "new-secret"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("changePassword: throws NOT_FOUND when user does not exist")
    void changePassword_userNotFound_throws() {
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("nonexistent")).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> authService.changePassword("nonexistent", "current", "new-secret"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertEquals("User not found", exception.getReason());
        verify(userRepository).findByUsername("nonexistent");
        verify(userRepository).findByEmail("nonexistent");
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("changePassword: throws UNAUTHORIZED when username is null")
    void changePassword_nullUsername_throws() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> authService.changePassword(null, "current", "new-secret"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Not authenticated", exception.getReason());
        verify(userRepository, never()).findByUsername(anyString());
        verify(userRepository, never()).findByEmail(anyString());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("changePassword: throws UNAUTHORIZED when username is blank")
    void changePassword_blankUsername_throws() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> authService.changePassword("   ", "current", "new-secret"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Not authenticated", exception.getReason());
        verify(userRepository, never()).findByUsername(anyString());
        verify(userRepository, never()).findByEmail(anyString());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("changePassword: finds user by email when username lookup fails")
    void changePassword_findsUserByEmail_whenUsernameNotFound() {
        testUser.setHashedPassword("old-hash");
        when(userRepository.findByUsername("test@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("current", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-secret")).thenReturn("new-hash");

        authService.changePassword("test@example.com", "current", "new-secret");

        verify(userRepository).findByUsername("test@example.com");
        verify(userRepository).findByEmail("test@example.com");
        verify(passwordEncoder).matches("current", "old-hash");
        verify(passwordEncoder).encode("new-secret");
        verify(userRepository).save(testUser);
        assertEquals("new-hash", testUser.getHashedPassword());
        assertEquals(fixedNow, testUser.getPasswordChangedAt());
    }
}
