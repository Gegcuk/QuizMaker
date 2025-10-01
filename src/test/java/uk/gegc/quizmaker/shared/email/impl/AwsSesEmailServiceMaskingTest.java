package uk.gegc.quizmaker.shared.email.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for email masking functionality in AwsSesEmailService.
 * 
 * The masking function is critical for security - it ensures that full email addresses
 * are never logged, preventing email enumeration attacks and PII leakage.
 * 
 * Tests cover:
 * - Null and empty email handling
 * - Single character local parts
 * - Two character local parts
 * - Complex local parts with special characters
 * - Edge cases (no @ sign, @ at start, etc.)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AwsSesEmailService Email Masking Tests")
class AwsSesEmailServiceMaskingTest {

    @Mock
    private SesV2Client sesClient;
    
    private SimpleMeterRegistry meterRegistry;
    private AwsSesEmailService emailService;
    private Method maskEmailMethod;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        
        // Get access to the private maskEmail method
        maskEmailMethod = AwsSesEmailService.class.getDeclaredMethod("maskEmail", String.class);
        maskEmailMethod.setAccessible(true);
    }

    private String maskEmail(String email) {
        try {
            return (String) maskEmailMethod.invoke(emailService, email);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke maskEmail method", e);
        }
    }

    // ========== Null and Empty Tests ==========

    @Test
    @DisplayName("Should mask null email as '***'")
    void shouldMaskNullEmailAsThreeAsterisks() {
        // When
        String masked = maskEmail(null);
        
        // Then
        assertThat(masked).isEqualTo("***");
    }

    @Test
    @DisplayName("Should mask empty string as '***'")
    void shouldMaskEmptyStringAsThreeAsterisks() {
        // When
        String masked = maskEmail("");
        
        // Then
        assertThat(masked).isEqualTo("***");
    }

    // ========== Single Character Local Part Tests ==========

    @Test
    @DisplayName("Should mask single character email 'a@b.com' as '***@b.com'")
    void shouldMaskSingleCharacterEmailAsAsterisksAtDomain() {
        // When
        String masked = maskEmail("a@b.com");
        
        // Then
        assertThat(masked).isEqualTo("***@b.com");
    }

    @Test
    @DisplayName("Should mask single character with long domain 'x@example.com' as '***@example.com'")
    void shouldMaskSingleCharacterEmailWithLongDomain() {
        // When
        String masked = maskEmail("x@example.com");
        
        // Then
        assertThat(masked).isEqualTo("***@example.com");
    }

    // ========== Two Character Local Part Tests ==========

    @Test
    @DisplayName("Should mask two character email 'ab@b.com' as 'a***@b.com'")
    void shouldMaskTwoCharacterEmailAsFirstCharPlusAsterisks() {
        // When
        String masked = maskEmail("ab@b.com");
        
        // Then
        assertThat(masked).isEqualTo("a***@b.com");
    }

    @Test
    @DisplayName("Should mask two character email 'xy@example.com' as 'x***@example.com'")
    void shouldMaskTwoCharacterEmailWithLongDomain() {
        // When
        String masked = maskEmail("xy@example.com");
        
        // Then
        assertThat(masked).isEqualTo("x***@example.com");
    }

    // ========== Standard Email Tests ==========

    @Test
    @DisplayName("Should mask standard email 'user@example.com' as 'u***@example.com'")
    void shouldMaskStandardEmail() {
        // When
        String masked = maskEmail("user@example.com");
        
        // Then
        assertThat(masked).isEqualTo("u***@example.com");
        assertThat(masked).doesNotContain("user");
    }

    @Test
    @DisplayName("Should mask long email 'testuser@example.com' as 't***@example.com'")
    void shouldMaskLongEmail() {
        // When
        String masked = maskEmail("testuser@example.com");
        
        // Then
        assertThat(masked).isEqualTo("t***@example.com");
        assertThat(masked).doesNotContain("testuser");
        assertThat(masked).doesNotContain("estuser");
    }

    // ========== Complex Local Parts Tests ==========

    @Test
    @DisplayName("Should mask email with dots 'user.name@domain.com' as 'u***@domain.com'")
    void shouldMaskEmailWithDots() {
        // When
        String masked = maskEmail("user.name@domain.com");
        
        // Then
        assertThat(masked).isEqualTo("u***@domain.com");
        assertThat(masked).doesNotContain("user");
        assertThat(masked).doesNotContain("name");
    }

    @Test
    @DisplayName("Should mask email with plus tag 'user+tag@domain.com' as 'u***@domain.com'")
    void shouldMaskEmailWithPlusTag() {
        // When
        String masked = maskEmail("user+tag@domain.com");
        
        // Then
        assertThat(masked).isEqualTo("u***@domain.com");
        assertThat(masked).doesNotContain("user");
        assertThat(masked).doesNotContain("tag");
    }

    @Test
    @DisplayName("Should mask complex email 'user.name+tag@domain.com' as 'u***@domain.com'")
    void shouldMaskComplexEmailWithDotsAndPlus() {
        // When
        String masked = maskEmail("user.name+tag@domain.com");
        
        // Then
        assertThat(masked).isEqualTo("u***@domain.com");
        assertThat(masked).doesNotContain("user");
        assertThat(masked).doesNotContain("name");
        assertThat(masked).doesNotContain("tag");
    }

    @Test
    @DisplayName("Should mask email with numbers 'user123@example.com' as 'u***@example.com'")
    void shouldMaskEmailWithNumbers() {
        // When
        String masked = maskEmail("user123@example.com");
        
        // Then
        assertThat(masked).isEqualTo("u***@example.com");
        assertThat(masked).doesNotContain("user123");
        assertThat(masked).doesNotContain("123");
    }

    @Test
    @DisplayName("Should mask email with underscores 'user_name@example.com' as 'u***@example.com'")
    void shouldMaskEmailWithUnderscores() {
        // When
        String masked = maskEmail("user_name@example.com");
        
        // Then
        assertThat(masked).isEqualTo("u***@example.com");
        assertThat(masked).doesNotContain("user_name");
    }

    // ========== Edge Cases Tests ==========

    @Test
    @DisplayName("Should handle email with no @ sign as '***@***'")
    void shouldHandleEmailWithNoAtSign() {
        // When
        String masked = maskEmail("notanemail");
        
        // Then
        // No @ found means atIndex = -1, which is <= 1
        // Code: atIndex > 0 is false, so returns "***@***"
        assertThat(masked).isEqualTo("***@***");
    }

    @Test
    @DisplayName("Should handle email starting with @ as '***@***'")
    void shouldHandleEmailStartingWithAtSign() {
        // When
        String masked = maskEmail("@domain.com");
        
        // Then
        // atIndex = 0, which is <= 1
        // Code: atIndex > 0 is false (0 is not > 0), so returns "***@***"
        assertThat(masked).isEqualTo("***@***");
    }

    @Test
    @DisplayName("Should mask email with @ at position 1 as '***@domain.com'")
    void shouldMaskEmailWithAtAtPosition1() {
        // When
        String masked = maskEmail("a@domain.com"); // @ at index 1 (position 2)
        
        // Then
        // atIndex = 1, which is <= 1
        assertThat(masked).isEqualTo("***@domain.com");
    }

    @Test
    @DisplayName("Should preserve full domain - masks only local part")
    void shouldPreserveFullDomain() {
        // When
        String masked = maskEmail("testuser@subdomain.example.co.uk");
        
        // Then
        assertThat(masked).isEqualTo("t***@subdomain.example.co.uk");
        assertThat(masked).contains("subdomain.example.co.uk"); // Full domain preserved
    }

    @Test
    @DisplayName("Should handle very long local part correctly")
    void shouldHandleVeryLongLocalPart() {
        // When
        String masked = maskEmail("verylongusernamewithmanycharacters@example.com");
        
        // Then
        assertThat(masked).isEqualTo("v***@example.com");
        assertThat(masked).doesNotContain("verylongusernamewithmanycharacters");
    }

    @Test
    @DisplayName("Should handle email with multiple @ signs - uses first @ as separator")
    void shouldHandleEmailWithMultipleAtSigns() {
        // When
        String masked = maskEmail("user@test@example.com");
        
        // Then
        // indexOf('@') returns the first occurrence
        assertThat(masked).isEqualTo("u***@test@example.com");
    }

    @Test
    @DisplayName("Should mask uppercase email - preserves case of first character")
    void shouldMaskUppercaseEmail() {
        // When
        String masked = maskEmail("User@Example.com");
        
        // Then
        assertThat(masked).isEqualTo("U***@Example.com");
    }

    @Test
    @DisplayName("Should handle email with special characters in domain")
    void shouldHandleSpecialCharactersInDomain() {
        // When
        String masked = maskEmail("user@test-domain.co.uk");
        
        // Then
        assertThat(masked).isEqualTo("u***@test-domain.co.uk");
    }
}

