package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.domain.model.Balance;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@Transactional
@DisplayName("Billing Reserve Idempotency Simple Tests")
class BillingReserveIdempotencySimpleTest {

    @Autowired
    private BillingService billingService;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @WithMockUser(authorities = "BILLING_WRITE")
    @DisplayName("Should handle idempotent reservation with same key")
    void shouldHandleIdempotentReservationWithSameKey() {
        // Given
        User testUser = createTestUser();
        createTestBalance(testUser.getId(), 2000L);
        
        String idempotencyKey = "test-key-" + UUID.randomUUID();
        long estimatedTokens = 1000L;
        String ref = "test-ref";

        // When - First reservation
        ReservationDto reservation1 = billingService.reserve(testUser.getId(), estimatedTokens, ref, idempotencyKey);
        
        // When - Second reservation with same key (should be idempotent)
        ReservationDto reservation2 = billingService.reserve(testUser.getId(), estimatedTokens, ref, idempotencyKey);

        // Then
        assertThat(reservation1.id()).isEqualTo(reservation2.id());
        assertThat(reservation1.estimatedTokens()).isEqualTo(reservation2.estimatedTokens());
        assertThat(reservation1.estimatedTokens()).isEqualTo(estimatedTokens);
    }

    @Test
    @WithMockUser(authorities = "BILLING_WRITE")
    @DisplayName("Should throw InsufficientTokensException with details when not enough tokens")
    void shouldThrowInsufficientTokensExceptionWithDetailsWhenNotEnoughTokens() {
        // Given
        User testUser = createTestUser();
        createTestBalance(testUser.getId(), 500L); // Less than required
        
        String idempotencyKey = "test-key-" + UUID.randomUUID();
        long estimatedTokens = 1000L;
        String ref = "test-ref";

        // When & Then
        assertThatThrownBy(() -> billingService.reserve(testUser.getId(), estimatedTokens, ref, idempotencyKey))
                .isInstanceOf(InsufficientTokensException.class)
                .satisfies(exception -> {
                    InsufficientTokensException ex = (InsufficientTokensException) exception;
                    assertThat(ex.getEstimatedTokens()).isEqualTo(estimatedTokens);
                    assertThat(ex.getAvailableTokens()).isEqualTo(500L);
                    assertThat(ex.getShortfall()).isEqualTo(500L); // 1000 - 500
                    assertThat(ex.getReservationTtl()).isNotNull();
                });
    }

    @Test
    @WithMockUser(authorities = "BILLING_WRITE")
    @DisplayName("Should create balance automatically when user has no balance")
    void shouldCreateBalanceAutomaticallyWhenUserHasNoBalance() {
        // Given
        User testUser = createTestUser();
        // No balance created for this user
        
        String idempotencyKey = "test-key-" + UUID.randomUUID();
        long estimatedTokens = 1000L;
        String ref = "test-ref";

        // When & Then
        assertThatThrownBy(() -> billingService.reserve(testUser.getId(), estimatedTokens, ref, idempotencyKey))
                .isInstanceOf(InsufficientTokensException.class)
                .satisfies(exception -> {
                    InsufficientTokensException ex = (InsufficientTokensException) exception;
                    assertThat(ex.getAvailableTokens()).isEqualTo(0L);
                });

        // Verify balance was created
        var balance = balanceRepository.findByUserId(testUser.getId());
        assertThat(balance).isPresent();
        assertThat(balance.get().getAvailableTokens()).isEqualTo(0L);
    }

    private User createTestUser() {
        User user = new User();
        user.setUsername("testuser-" + UUID.randomUUID().toString().substring(0, 8));
        user.setEmail(user.getUsername() + "@example.com");
        user.setHashedPassword("hashedPassword");
        user.setActive(true);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private Balance createTestBalance(UUID userId, long availableTokens) {
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(availableTokens);
        balance.setReservedTokens(0L);
        return balanceRepository.save(balance);
    }
}
