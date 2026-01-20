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
import uk.gegc.quizmaker.features.auth.domain.model.EmailVerificationToken;
import uk.gegc.quizmaker.features.auth.domain.repository.EmailVerificationTokenRepository;
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
    "spring.jpa.hibernate.ddl-auto=update"
})
@DisplayName("EmailVerificationTokenRepository Tests")
class EmailVerificationTokenRepositoryTest {

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private TestEntityManager entityManager;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        // Create a test user
        user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setHashedPassword("hashedPassword");
        user.setActive(true);
        user.setEmailVerified(false);
        user = entityManager.persistAndFlush(user);
        userId = user.getId();
    }

    @Test
    @DisplayName("findByTokenHashAndUsedFalseAndExpiresAtAfter: should find valid token")
    void findByTokenHashAndUsedFalseAndExpiresAtAfter_ValidToken_ReturnsToken() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        String tokenHash = "valid-token-hash";
        LocalDateTime expiresAt = now.plusHours(1);
        
        EmailVerificationToken token = new EmailVerificationToken();
        token.setTokenHash(tokenHash);
        token.setUserId(userId);
        token.setEmail("test@example.com");
        token.setUsed(false);
        token.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(token);

        // When
        Optional<EmailVerificationToken> result = emailVerificationTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash, now);

        // Then
        assertTrue(result.isPresent());
        assertEquals(tokenHash, result.get().getTokenHash());
        assertEquals(userId, result.get().getUserId());
        assertFalse(result.get().isUsed());
    }

    @Test
    @DisplayName("findByTokenHashAndUsedFalseAndExpiresAtAfter: should not find expired token")
    void findByTokenHashAndUsedFalseAndExpiresAtAfter_ExpiredToken_ReturnsEmpty() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        String tokenHash = "expired-token-hash";
        LocalDateTime expiresAt = now.minusHours(1);
        
        EmailVerificationToken token = new EmailVerificationToken();
        token.setTokenHash(tokenHash);
        token.setUserId(userId);
        token.setEmail("test@example.com");
        token.setUsed(false);
        token.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(token);

        // When
        Optional<EmailVerificationToken> result = emailVerificationTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash, now);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("findByTokenHashAndUsedFalseAndExpiresAtAfter: should not find used token")
    void findByTokenHashAndUsedFalseAndExpiresAtAfter_UsedToken_ReturnsEmpty() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        String tokenHash = "used-token-hash";
        LocalDateTime expiresAt = now.plusHours(1);
        
        EmailVerificationToken token = new EmailVerificationToken();
        token.setTokenHash(tokenHash);
        token.setUserId(userId);
        token.setEmail("test@example.com");
        token.setUsed(true);
        token.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(token);

        // When
        Optional<EmailVerificationToken> result = emailVerificationTokenRepository
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
        Optional<EmailVerificationToken> result = emailVerificationTokenRepository
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
        
        EmailVerificationToken token1 = new EmailVerificationToken();
        token1.setTokenHash(tokenHash1);
        token1.setUserId(userId);
        token1.setEmail("test@example.com");
        token1.setUsed(false);
        token1.setExpiresAt(expiresAt);
        
        EmailVerificationToken token2 = new EmailVerificationToken();
        token2.setTokenHash(tokenHash2);
        token2.setUserId(userId);
        token2.setEmail("test@example.com");
        token2.setUsed(false);
        token2.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(token1);
        entityManager.persistAndFlush(token2);

        // When
        emailVerificationTokenRepository.invalidateUserTokens(userId);
        entityManager.clear(); // Clear the persistence context to force reload

        // Then
        EmailVerificationToken updatedToken1 = entityManager.find(EmailVerificationToken.class, token1.getId());
        EmailVerificationToken updatedToken2 = entityManager.find(EmailVerificationToken.class, token2.getId());
        
        assertTrue(updatedToken1.isUsed());
        assertTrue(updatedToken2.isUsed());
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
        otherUser.setEmailVerified(false);
        otherUser = entityManager.persistAndFlush(otherUser);
        
        String userTokenHash = "user-token-hash";
        String otherUserTokenHash = "other-user-token-hash";
        LocalDateTime expiresAt = now.plusHours(1);
        
        EmailVerificationToken userToken = new EmailVerificationToken();
        userToken.setTokenHash(userTokenHash);
        userToken.setUserId(userId);
        userToken.setEmail("test@example.com");
        userToken.setUsed(false);
        userToken.setExpiresAt(expiresAt);
        
        EmailVerificationToken otherUserToken = new EmailVerificationToken();
        otherUserToken.setTokenHash(otherUserTokenHash);
        otherUserToken.setUserId(otherUser.getId());
        otherUserToken.setEmail("other@example.com");
        otherUserToken.setUsed(false);
        otherUserToken.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(userToken);
        entityManager.persistAndFlush(otherUserToken);

        // When
        emailVerificationTokenRepository.invalidateUserTokens(userId);
        entityManager.clear(); // Clear the persistence context to force reload

        // Then
        EmailVerificationToken updatedUserToken = entityManager.find(EmailVerificationToken.class, userToken.getId());
        EmailVerificationToken updatedOtherUserToken = entityManager.find(EmailVerificationToken.class, otherUserToken.getId());
        
        assertTrue(updatedUserToken.isUsed());
        assertFalse(updatedOtherUserToken.isUsed());
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
        
        EmailVerificationToken expiredToken = new EmailVerificationToken();
        expiredToken.setTokenHash(expiredTokenHash);
        expiredToken.setUserId(userId);
        expiredToken.setEmail("test@example.com");
        expiredToken.setUsed(false);
        expiredToken.setExpiresAt(expiredAt);
        
        EmailVerificationToken validToken = new EmailVerificationToken();
        validToken.setTokenHash(validTokenHash);
        validToken.setUserId(userId);
        validToken.setEmail("test@example.com");
        validToken.setUsed(false);
        validToken.setExpiresAt(validAt);
        
        entityManager.persistAndFlush(expiredToken);
        entityManager.persistAndFlush(validToken);

        // When
        emailVerificationTokenRepository.deleteExpiredTokens(now);
        entityManager.clear(); // Clear the persistence context to force reload

        // Then
        EmailVerificationToken deletedToken = entityManager.find(EmailVerificationToken.class, expiredToken.getId());
        EmailVerificationToken remainingToken = entityManager.find(EmailVerificationToken.class, validToken.getId());
        
        assertNull(deletedToken);
        assertNotNull(remainingToken);
    }

    @Test
    @DisplayName("markUsedIfValid: should mark valid token as used")
    void markUsedIfValid_ValidToken_ReturnsOne() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        String tokenHash = "valid-token-hash";
        LocalDateTime expiresAt = now.plusHours(1);
        
        EmailVerificationToken token = new EmailVerificationToken();
        token.setTokenHash(tokenHash);
        token.setUserId(userId);
        token.setEmail("test@example.com");
        token.setUsed(false);
        token.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(token);

        // When
        int updated = emailVerificationTokenRepository.markUsedIfValid(token.getId(), now);
        entityManager.clear(); // Clear the persistence context to force reload

        // Then
        assertEquals(1, updated);
        EmailVerificationToken updatedToken = entityManager.find(EmailVerificationToken.class, token.getId());
        assertTrue(updatedToken.isUsed());
    }

    @Test
    @DisplayName("markUsedIfValid: should not mark already used token")
    void markUsedIfValid_AlreadyUsedToken_ReturnsZero() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        String tokenHash = "used-token-hash";
        LocalDateTime expiresAt = now.plusHours(1);
        
        EmailVerificationToken token = new EmailVerificationToken();
        token.setTokenHash(tokenHash);
        token.setUserId(userId);
        token.setEmail("test@example.com");
        token.setUsed(true);
        token.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(token);

        // When
        int updated = emailVerificationTokenRepository.markUsedIfValid(token.getId(), now);

        // Then
        assertEquals(0, updated);
    }

    @Test
    @DisplayName("markUsedIfValid: should not mark expired token")
    void markUsedIfValid_ExpiredToken_ReturnsZero() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        String tokenHash = "expired-token-hash";
        LocalDateTime expiresAt = now.minusHours(1);
        
        EmailVerificationToken token = new EmailVerificationToken();
        token.setTokenHash(tokenHash);
        token.setUserId(userId);
        token.setEmail("test@example.com");
        token.setUsed(false);
        token.setExpiresAt(expiresAt);
        
        entityManager.persistAndFlush(token);

        // When
        int updated = emailVerificationTokenRepository.markUsedIfValid(token.getId(), now);

        // Then
        assertEquals(0, updated);
    }
}
