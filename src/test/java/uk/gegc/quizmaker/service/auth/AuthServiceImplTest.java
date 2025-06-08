package uk.gegc.quizmaker.service.auth;

import io.jsonwebtoken.Claims;
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
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.dto.auth.JwtResponse;
import uk.gegc.quizmaker.dto.auth.LoginRequest;
import uk.gegc.quizmaker.dto.auth.RefreshRequest;
import uk.gegc.quizmaker.dto.auth.RegisterRequest;
import uk.gegc.quizmaker.dto.user.UserDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.exception.UnauthorizedException;
import uk.gegc.quizmaker.mapper.UserMapper;
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.model.user.RoleName;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.RoleRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.security.JwtTokenProvider;
import uk.gegc.quizmaker.service.auth.impl.AuthServiceImpl;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    @DisplayName("register: saves new user and returns UserDto")
    void register_happy() {
        var req = new RegisterRequest("john", "john@example.com", "secret123");
        when(userRepository.existsByUsername("john")).thenReturn(false);
        when(userRepository.existsByEmail("john@example.com")).thenReturn(false);

        Role userRole = new Role(1L, RoleName.ROLE_USER.name(), null);
        when(roleRepository.findByRole(RoleName.ROLE_USER.name()))
                .thenReturn(Optional.of(userRole));

        when(passwordEncoder.encode("secret123")).thenReturn("hashedPwd");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        User saved = new User();
        saved.setId(UUID.randomUUID());
        saved.setUsername("john");
        saved.setEmail("john@example.com");
        saved.setActive(true);
        saved.setRoles(Set.of(userRole));
        saved.setCreatedAt(LocalDateTime.now());
        saved.setLastLoginDate(null);
        saved.setUpdatedAt(null);

        when(userRepository.save(captor.capture())).thenReturn(saved);

        UserDto expectedDto = new UserDto(
                saved.getId(),
                "john",
                "john@example.com",
                true,
                Set.of(RoleName.ROLE_USER),
                saved.getCreatedAt(),
                saved.getLastLoginDate(),
                saved.getUpdatedAt()
        );
        when(userMapper.toDto(saved)).thenReturn(expectedDto);

        UserDto result = authService.register(req);

        assertEquals(expectedDto, result);
        User passed = captor.getValue();
        assertEquals("john", passed.getUsername());
        assertEquals("john@example.com", passed.getEmail());
        assertEquals("hashedPwd", passed.getHashedPassword());
        assertTrue(passed.isActive());
        assertTrue(passed.getRoles().contains(userRole));
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
        when(jwtTokenProvider.generateAccessToken(auth))
                .thenReturn("access.jwt");
        when(jwtTokenProvider.generateRefreshToken(auth))
                .thenReturn("refresh.jwt");
        when(jwtTokenProvider.getAccessTokenValidityInMs())
                .thenReturn(1000L);
        when(jwtTokenProvider.getRefreshTokenValidityInMs())
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
        when(jwtTokenProvider.validateToken("valid.token")).thenReturn(true);
        Claims claims = mock(Claims.class);
        when(claims.get("type", String.class)).thenReturn("refresh");
        when(jwtTokenProvider.getClaims("valid.token")).thenReturn(claims);

        Authentication auth = mock(Authentication.class);
        when(jwtTokenProvider.getAuthentication("valid.token")).thenReturn(auth);
        when(jwtTokenProvider.generateAccessToken(auth)).thenReturn("new.access");
        when(jwtTokenProvider.getAccessTokenValidityInMs()).thenReturn(1234L);
        when(jwtTokenProvider.getRefreshTokenValidityInMs()).thenReturn(5678L);

        JwtResponse out = authService.refresh(req);

        assertEquals("new.access", out.accessToken());
        assertEquals("valid.token", out.refreshToken());
        assertEquals(1234L, out.accessExpiresInMs());
        assertEquals(5678L, out.refreshExpiresInMs());
    }

    @Test
    @DisplayName("refresh: invalid token yields 401")
    void refresh_invalidToken() {
        when(jwtTokenProvider.validateToken("bad")).thenReturn(false);
        var ex = assertThrows(ResponseStatusException.class,
                () -> authService.refresh(new RefreshRequest("bad")));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    @DisplayName("refresh: wrong token type yields 400")
    void refresh_wrongType() {
        when(jwtTokenProvider.validateToken("t")).thenReturn(true);
        Claims claims = mock(Claims.class);
        when(claims.get("type", String.class)).thenReturn("access");
        when(jwtTokenProvider.getClaims("t")).thenReturn(claims);

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
    @DisplayName("getCurrentUser: returns UserDto when found")
    void getCurrentUser_happy() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("john");
        User user = new User();
        user.setUsername("john");
        when(userRepository.findByUsername("john"))
                .thenReturn(Optional.of(user));

        UserDto dto = new UserDto(
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

        UserDto out = authService.getCurrentUser(auth);
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
}