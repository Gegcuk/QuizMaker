package uk.gegc.quizmaker.repository.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.gegc.quizmaker.features.auth.domain.model.PasswordResetToken;
import uk.gegc.quizmaker.features.auth.domain.repository.PasswordResetTokenRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DisplayName("PasswordResetTokenRepository Tests")
class PasswordResetTokenRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    private User testUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        // Create a test user
        testUser = new User();
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        testUser.setHashedPassword("hashedPassword");
        testUser.setActive(true);
        
        testUser = entityManager.persistAndFlush(testUser);
        userId = testUser.getId();
    }

    @Test
    @DisplayName("findByTokenHashAndUsedFalseAndExpiresAtAfter: should find valid token")
    void findByTokenHashAndUsedFalseAndExpiresAtAfter_ValidToken_ReturnsToken() {
        // Given
        String tokenHash = "valid-token-hash";
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(1);
        
        PasswordResetToken token = new PasswordResetToken();
        token.setTokenHash(tokenHash);
        token.setUserId(userId);
        token.setEmail("test@example.com");
        token.setUsed(false);
        token.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(token);

        // When
        Optional<PasswordResetToken> result = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash, LocalDateTime.now());

        // Then
        assertTrue(result.isPresent());
        assertEquals(tokenHash, result.get().getTokenHash());
        assertEquals(userId, result.get().getUserId());
        assertFalse(result.get().isUsed());
    }

    @Test
    @DisplayName("findByTokenHashAndUsedFalseAndExpiresAtAfter: should not find used token")
    void findByTokenHashAndUsedFalseAndExpiresAtAfter_UsedToken_ReturnsEmpty() {
        // Given
        String tokenHash = "used-token-hash";
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(1);
        
        PasswordResetToken token = new PasswordResetToken();
        token.setTokenHash(tokenHash);
        token.setUserId(userId);
        token.setEmail("test@example.com");
        token.setUsed(true);
        token.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(token);

        // When
        Optional<PasswordResetToken> result = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash, LocalDateTime.now());

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("findByTokenHashAndUsedFalseAndExpiresAtAfter: should not find expired token")
    void findByTokenHashAndUsedFalseAndExpiresAtAfter_ExpiredToken_ReturnsEmpty() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        String tokenHash = "expired-token-hash";
        LocalDateTime expiresAt = now.minusHours(1);
        
        PasswordResetToken token = new PasswordResetToken();
        token.setTokenHash(tokenHash);
        token.setUserId(userId);
        token.setEmail("test@example.com");
        token.setUsed(false);
        token.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(token);

        // When
        Optional<PasswordResetToken> result = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash, now);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("findByTokenHashAndUsedFalseAndExpiresAtAfter: should not find non-existent token")
    void findByTokenHashAndUsedFalseAndExpiresAtAfter_NonExistentToken_ReturnsEmpty() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        String nonExistentTokenHash = "non-existent-token-hash";

        // When
        Optional<PasswordResetToken> result = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(nonExistentTokenHash, now);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("invalidateUserTokens: should mark all user tokens as used")
    void invalidateUserTokens_ShouldMarkAllUserTokensAsUsed() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        String tokenHash1 = "token-hash-1";
        String tokenHash2 = "token-hash-2";
        LocalDateTime expiresAt = now.plusHours(1);
        
        PasswordResetToken token1 = new PasswordResetToken();
        token1.setTokenHash(tokenHash1);
        token1.setUserId(userId);
        token1.setEmail("test@example.com");
        token1.setUsed(false);
        token1.setExpiresAt(expiresAt);
        
        PasswordResetToken token2 = new PasswordResetToken();
        token2.setTokenHash(tokenHash2);
        token2.setUserId(userId);
        token2.setEmail("test@example.com");
        token2.setUsed(false);
        token2.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(token1);
        entityManager.persistAndFlush(token2);

        // When
        passwordResetTokenRepository.invalidateUserTokens(userId);
        entityManager.flush();
        entityManager.clear();

        // Then
        Optional<PasswordResetToken> result1 = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash1, now);
        Optional<PasswordResetToken> result2 = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash2, now);
        
        assertFalse(result1.isPresent());
        assertFalse(result2.isPresent());
    }

    @Test
    @DisplayName("invalidateUserTokens: should not affect tokens from other users")
    void invalidateUserTokens_ShouldNotAffectOtherUsersTokens() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        User otherUser = new User();
        otherUser.setUsername("otheruser");
        otherUser.setEmail("other@example.com");
        otherUser.setHashedPassword("hashedPassword");
        otherUser.setActive(true);
        otherUser = entityManager.persistAndFlush(otherUser);
        
        String userTokenHash = "user-token-hash";
        String otherUserTokenHash = "other-user-token-hash";
        LocalDateTime expiresAt = now.plusHours(1);
        
        PasswordResetToken userToken = new PasswordResetToken();
        userToken.setTokenHash(userTokenHash);
        userToken.setUserId(userId);
        userToken.setEmail("test@example.com");
        userToken.setUsed(false);
        userToken.setExpiresAt(expiresAt);
        
        PasswordResetToken otherUserToken = new PasswordResetToken();
        otherUserToken.setTokenHash(otherUserTokenHash);
        otherUserToken.setUserId(otherUser.getId());
        otherUserToken.setEmail("other@example.com");
        otherUserToken.setUsed(false);
        otherUserToken.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(userToken);
        entityManager.persistAndFlush(otherUserToken);

        // When
        passwordResetTokenRepository.invalidateUserTokens(userId);
        entityManager.flush();
        entityManager.clear();

        // Then
        Optional<PasswordResetToken> userTokenResult = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(userTokenHash, now);
        Optional<PasswordResetToken> otherUserTokenResult = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(otherUserTokenHash, now);
        
        assertFalse(userTokenResult.isPresent());
        assertTrue(otherUserTokenResult.isPresent());
    }

    @Test
    @DisplayName("deleteExpiredTokens: should delete expired tokens")
    void deleteExpiredTokens_ShouldDeleteExpiredTokens() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        String expiredTokenHash = "expired-token-hash";
        String validTokenHash = "valid-token-hash";
        LocalDateTime expiredAt = now.minusHours(1);
        LocalDateTime validAt = now.plusHours(1);
        
        PasswordResetToken expiredToken = new PasswordResetToken();
        expiredToken.setTokenHash(expiredTokenHash);
        expiredToken.setUserId(userId);
        expiredToken.setEmail("test@example.com");
        expiredToken.setUsed(false);
        expiredToken.setExpiresAt(expiredAt);
        
        PasswordResetToken validToken = new PasswordResetToken();
        validToken.setTokenHash(validTokenHash);
        validToken.setUserId(userId);
        validToken.setEmail("test@example.com");
        validToken.setUsed(false);
        validToken.setExpiresAt(validAt);
        
        entityManager.persistAndFlush(expiredToken);
        entityManager.persistAndFlush(validToken);

        // When
        passwordResetTokenRepository.deleteExpiredTokens(now);
        entityManager.flush();
        entityManager.clear();

        // Then
        Optional<PasswordResetToken> expiredResult = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(expiredTokenHash, now);
        Optional<PasswordResetToken> validResult = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(validTokenHash, now);
        
        assertFalse(expiredResult.isPresent());
        assertTrue(validResult.isPresent());
    }

    @Test
    @DisplayName("save: should persist new token")
    void save_NewToken_ShouldPersist() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        String tokenHash = "new-token-hash";
        LocalDateTime expiresAt = now.plusHours(1);
        
        PasswordResetToken token = new PasswordResetToken();
        token.setTokenHash(tokenHash);
        token.setUserId(userId);
        token.setEmail("test@example.com");
        token.setUsed(false);
        token.setExpiresAt(expiresAt);

        // When
        PasswordResetToken savedToken = passwordResetTokenRepository.save(token);

        // Then
        assertNotNull(savedToken.getId());
        assertEquals(tokenHash, savedToken.getTokenHash());
        assertEquals(userId, savedToken.getUserId());
        assertFalse(savedToken.isUsed());
        
        // Verify it can be found
        Optional<PasswordResetToken> found = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash, now);
        assertTrue(found.isPresent());
    }

    @Test
    @DisplayName("save: should update existing token")
    void save_ExistingToken_ShouldUpdate() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        String tokenHash = "existing-token-hash";
        LocalDateTime expiresAt = now.plusHours(1);
        
        PasswordResetToken token = new PasswordResetToken();
        token.setTokenHash(tokenHash);
        token.setUserId(userId);
        token.setEmail("test@example.com");
        token.setUsed(false);
        token.setExpiresAt(expiresAt);
        
        token = entityManager.persistAndFlush(token);

        // When
        token.setUsed(true);
        PasswordResetToken updatedToken = passwordResetTokenRepository.save(token);

        // Then
        assertTrue(updatedToken.isUsed());
        
        // Verify it's no longer findable as unused
        Optional<PasswordResetToken> found = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash, now);
        assertFalse(found.isPresent());
    }
}
