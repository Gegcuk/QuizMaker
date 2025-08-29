package uk.gegc.quizmaker.features.documentProcess.api.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestRequestValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void IngestRequest_validation_ok() {
        IngestRequest request = new IngestRequest("Valid text content", "en");
        
        Set<ConstraintViolation<IngestRequest>> violations = validator.validate(request);
        
        assertTrue(violations.isEmpty(), "Valid request should have no violations");
    }

    @Test
    void IngestRequest_text_blank_isInvalid() {
        IngestRequest request = new IngestRequest("   ", "en");
        
        Set<ConstraintViolation<IngestRequest>> violations = validator.validate(request);
        
        assertFalse(violations.isEmpty(), "Blank text should have violations");
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("text")), 
                "Should have violation for text field");
    }

    @Test
    void IngestRequest_text_null_isInvalid() {
        IngestRequest request = new IngestRequest(null, "en");
        
        Set<ConstraintViolation<IngestRequest>> violations = validator.validate(request);
        
        assertFalse(violations.isEmpty(), "Null text should have violations");
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("text")), 
                "Should have violation for text field");
    }

    @Test
    void IngestRequest_language_tooLong_isInvalid() {
        String longLanguage = "a".repeat(33); // 33 characters, exceeds 32 limit
        IngestRequest request = new IngestRequest("Valid text", longLanguage);
        
        Set<ConstraintViolation<IngestRequest>> violations = validator.validate(request);
        
        assertFalse(violations.isEmpty(), "Language too long should have violations");
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("language")), 
                "Should have violation for language field");
    }

    @Test
    void IngestRequest_language_exactly32Chars_isValid() {
        String language32Chars = "a".repeat(32); // Exactly 32 characters
        IngestRequest request = new IngestRequest("Valid text", language32Chars);
        
        Set<ConstraintViolation<IngestRequest>> violations = validator.validate(request);
        
        assertTrue(violations.isEmpty(), "Language exactly 32 chars should be valid");
    }

    @Test
    void IngestRequest_language_null_isValid() {
        IngestRequest request = new IngestRequest("Valid text", null);
        
        Set<ConstraintViolation<IngestRequest>> violations = validator.validate(request);
        
        assertTrue(violations.isEmpty(), "Null language should be valid");
    }
}
