package uk.gegc.quizmaker.service.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.dto.user.UpdateUserProfileRequest;
import uk.gegc.quizmaker.dto.user.UserProfileResponse;
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.user.impl.UserProfileServiceImpl;
import uk.gegc.quizmaker.util.XssSanitizer;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileService Implementation Tests")
class UserProfileServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private XssSanitizer xssSanitizer;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private UserProfileServiceImpl meService;

    private User testUser;
    private UUID userId;
    private LocalDateTime createdAt;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        createdAt = LocalDateTime.now();

        testUser = new User();
        testUser.setId(userId);
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        testUser.setHashedPassword("hashedPassword");
        testUser.setCreatedAt(createdAt);
        testUser.setActive(true);
        testUser.setDeleted(false);
        testUser.setEmailVerified(true);
        testUser.setDisplayName("Test User");
        testUser.setBio("Test bio");
        testUser.setAvatarUrl("https://example.com/avatar.jpg");
        testUser.setPreferences("{\"theme\":\"dark\"}");

        Set<Role> roles = new HashSet<>();
        Role userRole = new Role();
        userRole.setRoleName("ROLE_USER");
        roles.add(userRole);
        testUser.setRoles(roles);
    }

    @Test
    @DisplayName("Should get current user profile successfully")
    void shouldGetCurrentUserProfileSuccessfully() throws JsonProcessingException {
        // Given
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRoles("testuser")).thenReturn(Optional.of(testUser));
        when(objectMapper.readValue("{\"theme\":\"dark\"}", Map.class)).thenReturn(Map.of("theme", "dark"));
        lenient().when(xssSanitizer.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        UserProfileResponse result = meService.getCurrentUserProfile(authentication);

        // Then
        assertNotNull(result);
        assertEquals(userId, result.id());
        assertEquals("testuser", result.username());
        assertEquals("test@example.com", result.email());
        assertEquals("Test User", result.displayName());
        assertEquals("Test bio", result.bio());
        assertEquals("https://example.com/avatar.jpg", result.avatarUrl());
        assertEquals(Map.of("theme", "dark"), result.preferences());
        assertEquals(createdAt, result.joinedAt());
        assertTrue(result.verified());
        assertEquals(List.of("USER"), result.roles());

        verify(userRepository).findByUsernameWithRoles("testuser");
        verify(userRepository, never()).findByEmailWithRoles(any());
    }

    @Test
    @DisplayName("Should get current user profile by email when username not found")
    void shouldGetCurrentUserProfileByEmailWhenUsernameNotFound() throws JsonProcessingException {
        // Given
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("test@example.com");
        when(userRepository.findByUsernameWithRoles("test@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("test@example.com")).thenReturn(Optional.of(testUser));
        when(objectMapper.readValue("{\"theme\":\"dark\"}", Map.class)).thenReturn(Map.of("theme", "dark"));

        // When
        UserProfileResponse result = meService.getCurrentUserProfile(authentication);

        // Then
        assertNotNull(result);
        assertEquals(userId, result.id());

        verify(userRepository).findByUsernameWithRoles("test@example.com");
        verify(userRepository).findByEmailWithRoles("test@example.com");
    }

    @Test
    @DisplayName("Should throw unauthorized when not authenticated")
    void shouldThrowUnauthorizedWhenNotAuthenticated() {
        // Given
        when(authentication.isAuthenticated()).thenReturn(false);

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> meService.getCurrentUserProfile(authentication));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Not authenticated", exception.getReason());
    }

    @Test
    @DisplayName("Should throw unauthorized when authentication is anonymous")
    void shouldThrowUnauthorizedWhenAuthenticationIsAnonymous() {
        // Given
        AnonymousAuthenticationToken anonymousAuth = mock(AnonymousAuthenticationToken.class);
        when(anonymousAuth.isAuthenticated()).thenReturn(true);

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> meService.getCurrentUserProfile(anonymousAuth));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Not authenticated", exception.getReason());
    }

    @Test
    @DisplayName("Should throw unauthorized when user not found")
    void shouldThrowUnauthorizedWhenUserNotFound() {
        // Given
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("nonexistent");
        when(userRepository.findByUsernameWithRoles("nonexistent")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("nonexistent")).thenReturn(Optional.empty());

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> meService.getCurrentUserProfile(authentication));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("User not found or inactive", exception.getReason());
    }

    @Test
    @DisplayName("Should throw unauthorized when user is inactive")
    void shouldThrowUnauthorizedWhenUserIsInactive() {
        // Given
        testUser.setActive(false);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRoles("testuser")).thenReturn(Optional.of(testUser));

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> meService.getCurrentUserProfile(authentication));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("User not found or inactive", exception.getReason());
    }

    @Test
    @DisplayName("Should throw unauthorized when user is deleted")
    void shouldThrowUnauthorizedWhenUserIsDeleted() {
        // Given
        testUser.setDeleted(true);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRoles("testuser")).thenReturn(Optional.of(testUser));

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> meService.getCurrentUserProfile(authentication));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("User not found or inactive", exception.getReason());
    }

    @Test
    @DisplayName("Should handle null preferences gracefully")
    void shouldHandleNullPreferencesGracefully() {
        // Given
        testUser.setPreferences(null);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRoles("testuser")).thenReturn(Optional.of(testUser));

        // When
        UserProfileResponse result = meService.getCurrentUserProfile(authentication);

        // Then
        assertNotNull(result);
        assertEquals(Map.of(), result.preferences());
    }

    @Test
    @DisplayName("Should handle invalid preferences JSON gracefully")
    void shouldHandleInvalidPreferencesJsonGracefully() throws JsonProcessingException {
        // Given
        testUser.setPreferences("invalid json");
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRoles("testuser")).thenReturn(Optional.of(testUser));
        when(objectMapper.readValue("invalid json", Map.class)).thenThrow(new JsonProcessingException("Invalid JSON") {});

        // When
        UserProfileResponse result = meService.getCurrentUserProfile(authentication);

        // Then
        assertNotNull(result);
        assertEquals(Map.of(), result.preferences());
    }

    @Test
    @DisplayName("Should update current user profile successfully")
    void shouldUpdateCurrentUserProfileSuccessfully() throws JsonProcessingException {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(
                "New Display Name",
                "New bio with <script>alert('xss')</script>",
                Map.of("theme", "light", "notifications", Map.of("email", false))
        );

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRoles("testuser")).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(xssSanitizer.sanitizeAndTruncate("New Display Name", 50)).thenReturn("New Display Name");
        when(xssSanitizer.sanitizeAndTruncate("New bio with <script>alert('xss')</script>", 500))
                .thenReturn("New bio with alert('xss')");
        lenient().when(objectMapper.writeValueAsString(Map.of("theme", "light", "notifications", Map.of("email", false))))
                .thenReturn("{\"theme\":\"light\",\"notifications\":{\"email\":false}}");
        lenient().when(objectMapper.readValue("{\"theme\":\"dark\"}", Map.class)).thenReturn(Map.of("theme", "dark"));
        lenient().when(objectMapper.readValue("{\"theme\":\"light\",\"notifications\":{\"email\":false}}", Map.class))
                .thenReturn(Map.of("theme", "light", "notifications", Map.of("email", false)));

        // When
        UserProfileResponse result = meService.updateCurrentUserProfile(authentication, request);

        // Then
        assertNotNull(result);
        verify(userRepository).save(any(User.class));
        verify(xssSanitizer).sanitizeAndTruncate("New Display Name", 50);
        verify(xssSanitizer).sanitizeAndTruncate("New bio with <script>alert('xss')</script>", 500);
        verify(objectMapper).writeValueAsString(Map.of("theme", "light", "notifications", Map.of("email", false)));
    }

    @Test
    @DisplayName("Should update profile with partial data")
    void shouldUpdateProfileWithPartialData() throws JsonProcessingException {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("New Name", null, null);

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRoles("testuser")).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(xssSanitizer.sanitizeAndTruncate("New Name", 50)).thenReturn("New Name");
        when(objectMapper.readValue("{\"theme\":\"dark\"}", Map.class)).thenReturn(Map.of("theme", "dark"));

        // When
        UserProfileResponse result = meService.updateCurrentUserProfile(authentication, request);

        // Then
        assertNotNull(result);
        verify(userRepository).save(any(User.class));
        verify(xssSanitizer).sanitizeAndTruncate("New Name", 50);
        verify(xssSanitizer, never()).sanitizeAndTruncate(any(), eq(500));
        verify(objectMapper, never()).writeValueAsString(any());
    }

    @Test
    @DisplayName("Should throw bad request when preferences serialization fails")
    void shouldThrowBadRequestWhenPreferencesSerializationFails() throws JsonProcessingException {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(null, null, Map.of("invalid", "data"));

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRoles("testuser")).thenReturn(Optional.of(testUser));
        when(objectMapper.writeValueAsString(Map.of("invalid", "data")))
                .thenThrow(new JsonProcessingException("Serialization failed") {});

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> meService.updateCurrentUserProfile(authentication, request));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Invalid preferences format", exception.getReason());
    }

    @Test
    @DisplayName("Should throw unauthorized when updating with null authentication")
    void shouldThrowUnauthorizedWhenUpdatingWithNullAuthentication() {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("New Name", "New Bio", null);

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> meService.updateCurrentUserProfile(null, request));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Not authenticated", exception.getReason());
    }

    @Test
    @DisplayName("Should use username as display name when display name is null")
    void shouldUseUsernameAsDisplayNameWhenDisplayNameIsNull() throws JsonProcessingException {
        // Given
        testUser.setDisplayName(null);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRoles("testuser")).thenReturn(Optional.of(testUser));
        when(objectMapper.readValue("{\"theme\":\"dark\"}", Map.class)).thenReturn(Map.of("theme", "dark"));

        // When
        UserProfileResponse result = meService.getCurrentUserProfile(authentication);

        // Then
        assertNotNull(result);
        assertEquals("testuser", result.displayName());
    }
}


