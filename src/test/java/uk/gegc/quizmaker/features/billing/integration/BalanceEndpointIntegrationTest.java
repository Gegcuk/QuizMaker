package uk.gegc.quizmaker.features.billing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.billing.domain.model.Balance;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionSource;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Optional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "quizmaker.features.billing.enabled=true"
})
@Transactional
@DisplayName("Balance Endpoint Integration Tests")
class BalanceEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private TokenTransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private RoleRepository roleRepository;
    
    @Autowired
    private PermissionRepository permissionRepository;

    private User testUser;
    private String testUsername;
    private String testUserToken;
    private SimpleGrantedAuthority billingReadAuthority;
    private SimpleGrantedAuthority billingWriteAuthority;

    @BeforeEach
    void setUp() {
        // Clean up database (following ShareLinkControllerIntegrationTest pattern)
        userRepository.deleteAllInBatch();
        roleRepository.deleteAllInBatch();
        permissionRepository.deleteAllInBatch();
        
        // Create billing permissions (following ShareLinkControllerIntegrationTest pattern)
        Permission billingReadPermission = new Permission();
        billingReadPermission.setPermissionName(PermissionName.BILLING_READ.name());
        billingReadPermission = permissionRepository.save(billingReadPermission);
        
        Permission billingWritePermission = new Permission();
        billingWritePermission.setPermissionName(PermissionName.BILLING_WRITE.name());
        billingWritePermission = permissionRepository.save(billingWritePermission);

        // Create role with billing permissions (following ShareLinkControllerIntegrationTest pattern)
        Role billingRole = new Role();
        billingRole.setRoleName(RoleName.ROLE_USER.name());
        billingRole.setPermissions(Set.of(billingReadPermission, billingWritePermission));
        billingRole = roleRepository.save(billingRole);

        // Create test user with the role
        testUsername = "testuser-" + UUID.randomUUID().toString().substring(0, 8);
        testUser = new User();
        testUser.setUsername(testUsername);
        testUser.setEmail(testUsername + "@example.com");
        testUser.setHashedPassword("hashedPassword");
        testUser.setActive(true);
        testUser.setEmailVerified(true);
        testUser.setRoles(Set.of(billingRole));
        testUser = userRepository.save(testUser);
        testUserToken = testUser.getId().toString();
        
        // Create authorities for Spring Security context
        billingReadAuthority = new SimpleGrantedAuthority("BILLING_READ");
        billingWriteAuthority = new SimpleGrantedAuthority("BILLING_WRITE");

        // Create test balance
        Balance balance = new Balance();
        balance.setUserId(testUser.getId());
        balance.setAvailableTokens(5000L);
        balance.setReservedTokens(1000L);
        balance.setUpdatedAt(LocalDateTime.now());
        balanceRepository.save(balance);

        // Create some test transactions
        createTestTransactions();
    }

    @Nested
    @DisplayName("Authentication Tests")
    class AuthenticationTests {

        @Test
        @DisplayName("Should return 403 when not authenticated")
        void shouldReturn403WhenNotAuthenticated() throws Exception {
            mockMvc.perform(get("/api/v1/billing/balance"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Should return 200 when authenticated with BILLING_READ authority")
        void shouldReturn200WhenAuthenticatedWithBillingReadAuthority() throws Exception {
            // Use the actual test user from setUp
            mockMvc.perform(get("/api/v1/billing/balance")
                    .with(user(testUserToken).authorities(billingReadAuthority)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.availableTokens").value(5000))
                    .andExpect(jsonPath("$.reservedTokens").value(1000));
        }

        @Test
        @DisplayName("Should return 403 when authenticated without BILLING_READ authority")
        void shouldReturn403WhenAuthenticatedWithoutBillingReadAuthority() throws Exception {
            // Create a user without BILLING_READ authority
            User userWithoutBillingAuth = new User();
            userWithoutBillingAuth.setUsername("nobillinguser");
            userWithoutBillingAuth.setEmail("nobilling@example.com");
            userWithoutBillingAuth.setHashedPassword("password");
            userWithoutBillingAuth.setActive(true);
            userWithoutBillingAuth.setEmailVerified(true);
            
            // Create a role without billing permissions
            Permission basicPermission = permissionRepository.findByPermissionName(PermissionName.USER_READ.name())
                    .orElseGet(() -> {
                        Permission permission = new Permission();
                        permission.setPermissionName(PermissionName.USER_READ.name());
                        permission.setDescription("Basic user read permission");
                        return permissionRepository.save(permission);
                    });
            
            Role basicRole = roleRepository.findByRoleName("ROLE_BASIC")
                    .orElseGet(() -> {
                        Role role = new Role();
                        role.setRoleName("ROLE_BASIC");
                        role.setPermissions(Set.of(basicPermission));
                        return roleRepository.save(role);
                    });
            
            userWithoutBillingAuth.setRoles(Set.of(basicRole));
            userWithoutBillingAuth = userRepository.save(userWithoutBillingAuth);
            
            mockMvc.perform(get("/api/v1/billing/balance")
                    .with(user(userWithoutBillingAuth.getId().toString())))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Balance Response Tests")
    class BalanceResponseTests {

        @Test
        @DisplayName("Should return correct balance information")
        void shouldReturnCorrectBalanceInformation() throws Exception {
            // Use the actual test user from setUp
            mockMvc.perform(get("/api/v1/billing/balance")
                    .with(user(testUserToken).authorities(billingReadAuthority)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.userId").value(testUser.getId().toString()))
                    .andExpect(jsonPath("$.availableTokens").value(5000))
                    .andExpect(jsonPath("$.reservedTokens").value(1000))
                    .andExpect(jsonPath("$.updatedAt").exists());
        }

        @Test
        @DisplayName("Should create balance if user has no balance")
        void shouldCreateBalanceIfUserHasNoBalance() throws Exception {
            // Create new user without balance
            User newUser = new User();
            newUser.setUsername("newuser");
            newUser.setEmail("newuser@example.com");
            newUser.setHashedPassword("hashedPassword");
            newUser.setActive(true);
            newUser.setEmailVerified(true);
            
            // Assign ROLE_USER to give billing permissions
            Role userRole = roleRepository.findByRoleName(RoleName.ROLE_USER.name()).orElseThrow();
            newUser.setRoles(Set.of(userRole));
            final User savedNewUser = userRepository.save(newUser);

            mockMvc.perform(get("/api/v1/billing/balance")
                    .with(user(savedNewUser.getId().toString()).authorities(billingReadAuthority)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.userId").value(savedNewUser.getId().toString()))
                    .andExpect(jsonPath("$.availableTokens").value(0))
                    .andExpect(jsonPath("$.reservedTokens").value(0));

            // Verify balance was created in database
            var balance = balanceRepository.findByUserId(savedNewUser.getId());
            assertThat(balance).isPresent();
            assertThat(balance.get().getAvailableTokens()).isEqualTo(0L);
            assertThat(balance.get().getReservedTokens()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("Transactions Endpoint Tests")
    class TransactionsEndpointTests {

        @Test
        @DisplayName("Should return transactions with pagination")
        void shouldReturnTransactionsWithPagination() throws Exception {
            mockMvc.perform(get("/api/v1/billing/transactions")
                            .with(user(testUserToken).authorities(billingReadAuthority))
                            .param("page", "0")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(3)) // We created 3 transactions
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.size").value(10))
                    .andExpect(jsonPath("$.number").value(0));
        }

        @Test
        @DisplayName("Should filter transactions by type")
        void shouldFilterTransactionsByType() throws Exception {
            mockMvc.perform(get("/api/v1/billing/transactions")
                            .with(user(testUserToken).authorities(billingReadAuthority))
                            .param("type", "PURCHASE"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].type").value("PURCHASE"));
        }

        @Test
        @DisplayName("Should filter transactions by source")
        void shouldFilterTransactionsBySource() throws Exception {
            mockMvc.perform(get("/api/v1/billing/transactions")
                            .with(user(testUserToken).authorities(billingReadAuthority))
                            .param("source", "STRIPE"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.content[0].source").value("STRIPE"));
        }

        @Test
        @DisplayName("Should filter transactions by date range")
        void shouldFilterTransactionsByDateRange() throws Exception {
            LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
            LocalDateTime tomorrow = LocalDateTime.now().plusDays(1);

            mockMvc.perform(get("/api/v1/billing/transactions")
                            .with(user(testUserToken).authorities(billingReadAuthority))
                            .param("dateFrom", yesterday.toString())
                            .param("dateTo", tomorrow.toString()))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(3));
        }

        @Test
        @DisplayName("Should return empty list when no transactions match filters")
        void shouldReturnEmptyListWhenNoTransactionsMatchFilters() throws Exception {
            mockMvc.perform(get("/api/v1/billing/transactions")
                            .with(user(testUserToken).authorities(billingReadAuthority))
                            .param("type", "COMMIT")) // Use a type that doesn't exist in our test data
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(0))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("Should handle invalid date format gracefully")
        void shouldHandleInvalidDateFormatGracefully() throws Exception {
            mockMvc.perform(get("/api/v1/billing/transactions")
                            .with(user(testUserToken).authorities(billingReadAuthority))
                            .param("dateFrom", "invalid-date"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Rate Limiting Tests")
    class RateLimitingTests {

        @Test
        @DisplayName("Should respect rate limits for balance endpoint")
        void shouldRespectRateLimitsForBalanceEndpoint() throws Exception {
            // Make multiple requests to test rate limiting
            for (int i = 0; i < 10; i++) {
                mockMvc.perform(get("/api/v1/billing/balance")
                        .with(user(testUserToken).authorities(billingReadAuthority)))
                        .andExpect(status().isOk());
            }

            // Note: Actual rate limiting behavior depends on configuration
            // This test verifies the endpoint doesn't crash under load
        }

        @Test
        @DisplayName("Should respect rate limits for transactions endpoint")
        void shouldRespectRateLimitsForTransactionsEndpoint() throws Exception {
            // Make multiple requests to test rate limiting
            for (int i = 0; i < 10; i++) {
                mockMvc.perform(get("/api/v1/billing/transactions")
                        .with(user(testUserToken).authorities(billingReadAuthority)))
                        .andExpect(status().isOk());
            }

            // Note: Actual rate limiting behavior depends on configuration
            // This test verifies the endpoint doesn't crash under load
        }
    }

    @Nested
    @DisplayName("Cache Headers Tests")
    class CacheHeadersTests {

        @Test
        @DisplayName("Should include appropriate cache headers for balance")
        void shouldIncludeAppropriateCacheHeadersForBalance() throws Exception {
            mockMvc.perform(get("/api/v1/billing/balance")
                    .with(user(testUserToken).authorities(billingReadAuthority)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "private, max-age=30"));
        }

        @Test
        @DisplayName("Should include appropriate cache headers for transactions")
        void shouldIncludeAppropriateCacheHeadersForTransactions() throws Exception {
            mockMvc.perform(get("/api/v1/billing/transactions")
                    .with(user(testUserToken).authorities(billingReadAuthority)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "private, max-age=60"));
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle malformed pagination parameters gracefully")
        void shouldHandleMalformedPaginationParameters() throws Exception {
            mockMvc.perform(get("/api/v1/billing/transactions")
                            .with(user(testUserToken).authorities(billingReadAuthority))
                            .param("page", "invalid")
                            .param("size", "invalid"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pageable.pageNumber").value(0))
                    .andExpect(jsonPath("$.pageable.pageSize").value(20));
        }

        @Test
        @DisplayName("Should handle invalid transaction type")
        void shouldHandleInvalidTransactionType() throws Exception {
            mockMvc.perform(get("/api/v1/billing/transactions")
                            .with(user(testUserToken).authorities(billingReadAuthority))
                            .param("type", "INVALID_TYPE"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should handle invalid transaction source")
        void shouldHandleInvalidTransactionSource() throws Exception {
            mockMvc.perform(get("/api/v1/billing/transactions")
                            .with(user(testUserToken).authorities(billingReadAuthority))
                            .param("source", "INVALID_SOURCE"))
                    .andExpect(status().isBadRequest());
        }
    }

    private void createTestTransactions() {
        // Create and persist transactions - createdAt will be set automatically by database
        TokenTransaction purchase = new TokenTransaction();
        purchase.setUserId(testUser.getId());
        purchase.setType(TokenTransactionType.PURCHASE);
        purchase.setSource(TokenTransactionSource.STRIPE);
        purchase.setAmountTokens(5000L);
        purchase.setRefId("stripe_payment_123");
        purchase.setIdempotencyKey("purchase-" + UUID.randomUUID());
        purchase.setMetaJson("{\"stripe_payment_id\":\"pi_123\"}");
        purchase.setBalanceAfterAvailable(5000L);
        purchase.setBalanceAfterReserved(0L);
        transactionRepository.save(purchase);

        TokenTransaction reserve = new TokenTransaction();
        reserve.setUserId(testUser.getId());
        reserve.setType(TokenTransactionType.RESERVE);
        reserve.setSource(TokenTransactionSource.QUIZ_GENERATION);
        reserve.setAmountTokens(0L);
        reserve.setRefId(UUID.randomUUID().toString());
        reserve.setIdempotencyKey("reserve-" + UUID.randomUUID());
        reserve.setMetaJson("{\"job_id\":\"job_123\"}");
        reserve.setBalanceAfterAvailable(4000L);
        reserve.setBalanceAfterReserved(1000L);
        transactionRepository.save(reserve);

        TokenTransaction refund = new TokenTransaction();
        refund.setUserId(testUser.getId());
        refund.setType(TokenTransactionType.REFUND);
        refund.setSource(TokenTransactionSource.STRIPE);
        refund.setAmountTokens(-100L);
        refund.setRefId("refund_123");
        refund.setIdempotencyKey("refund-" + UUID.randomUUID());
        refund.setMetaJson("{\"refund_id\":\"re_123\"}");
        refund.setBalanceAfterAvailable(4900L);
        refund.setBalanceAfterReserved(1000L);
        transactionRepository.save(refund);
    }
}
