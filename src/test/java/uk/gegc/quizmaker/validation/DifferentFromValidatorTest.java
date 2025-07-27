package uk.gegc.quizmaker.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DifferentFromValidatorTest {

    private static ValidatorFactory validatorFactory;
    private Validator validator;

    @BeforeAll
    static void setUpFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @BeforeEach
    void setUp() {
        validator = validatorFactory.getValidator();
    }

    @DifferentFrom(field = "field1", notEqualTo = "field2")
    static class TestRecord {
        private final String field1;
        private final String field2;

        public TestRecord(String field1, String field2) {
            this.field1 = field1;
            this.field2 = field2;
        }

        // Property accessor methods for bean introspection
        public String getField1() { return field1; }
        public String getField2() { return field2; }
    }

    @Test
    void shouldAcceptDifferentValues() {
        TestRecord record = new TestRecord("value1", "value2");
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(record);
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldRejectSameValues() {
        TestRecord record = new TestRecord("sameValue", "sameValue");
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(record);
        assertThat(violations).hasSize(1);
        
        ConstraintViolation<TestRecord> violation = violations.iterator().next();
        // Now the violation should be attached to the specific field
        assertThat(violation.getPropertyPath().toString()).isEqualTo("field1");
    }

    @Test
    void shouldSkipValidationWhenFirstFieldIsNull() {
        TestRecord record = new TestRecord(null, "value2");
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(record);
        // Should skip validation and not report @DifferentFrom violation
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldSkipValidationWhenSecondFieldIsNull() {
        TestRecord record = new TestRecord("value1", null);
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(record);
        // Should skip validation and not report @DifferentFrom violation
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldSkipValidationWhenBothFieldsAreNull() {
        TestRecord record = new TestRecord(null, null);
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(record);
        // Should skip validation - let @NotBlank handle null values
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldSkipValidationWhenFirstFieldIsBlank() {
        TestRecord record = new TestRecord("", "value2");
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(record);
        // Should skip validation and not report @DifferentFrom violation
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldSkipValidationWhenSecondFieldIsBlank() {
        TestRecord record = new TestRecord("value1", "");
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(record);
        // Should skip validation and not report @DifferentFrom violation
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldSkipValidationWhenBothFieldsAreBlank() {
        TestRecord record = new TestRecord("", "");
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(record);
        // Should skip validation - let @NotBlank handle blank values
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldSkipValidationWhenFieldsAreWhitespace() {
        TestRecord record = new TestRecord("   ", "   ");
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(record);
        // Should skip validation - isBlank() returns true for whitespace
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldValidateWhenBothFieldsHaveValues() {
        // Test that validation still works when both fields have actual values
        TestRecord recordDifferent = new TestRecord("value1", "value2");
        Set<ConstraintViolation<TestRecord>> violationsDifferent = validator.validate(recordDifferent);
        assertThat(violationsDifferent).isEmpty();

        TestRecord recordSame = new TestRecord("sameValue", "sameValue");
        Set<ConstraintViolation<TestRecord>> violationsSame = validator.validate(recordSame);
        assertThat(violationsSame).hasSize(1);
        assertThat(violationsSame.iterator().next().getPropertyPath().toString()).isEqualTo("field1");
    }

    @Test
    void shouldHandleNullObject() {
        DifferentFromValidator validator = new DifferentFromValidator();
        DifferentFrom annotation = TestRecord.class.getAnnotation(DifferentFrom.class);
        validator.initialize(annotation);
        
        boolean result = validator.isValid(null, null);
        assertThat(result).isTrue();
    }

    @Test
    void shouldThrowValidationExceptionOnNonExistentFields() {
        @DifferentFrom(field = "nonExistent1", notEqualTo = "nonExistent2")
        class InvalidRecord {
            private final String field1 = "value1";
            public String getField1() { return field1; }
        }

        InvalidRecord record = new InvalidRecord();
        
        // Hibernate Validator wraps our ValidationException in another ValidationException
        // We need to check for the cause chain properly
        assertThatThrownBy(() -> validator.validate(record))
            .isInstanceOf(jakarta.validation.ValidationException.class)
            .hasMessageContaining("HV000028: Unexpected exception during isValid call")
            .getCause()
            .isInstanceOf(jakarta.validation.ValidationException.class)
            .hasMessageContaining("Invalid fields for @DifferentFrom: 'nonExistent1' and 'nonExistent2' on InvalidRecord")
            .getCause()
            .isInstanceOf(NoSuchFieldException.class)
            .hasMessage("nonExistent1");
    }

    @Test
    void shouldWorkWithJavaRecords() {
        // Test with actual Java record
        record SimpleRecord(String field1, String field2) {}
        
        @DifferentFrom(field = "field1", notEqualTo = "field2")
        class RecordWrapper {
            private final SimpleRecord record;
            
            public RecordWrapper(String field1, String field2) {
                this.record = new SimpleRecord(field1, field2);
            }
            
            public String getField1() { return record.field1(); }
            public String getField2() { return record.field2(); }
        }

        RecordWrapper wrapper = new RecordWrapper("different1", "different2");
        Set<ConstraintViolation<RecordWrapper>> violations = validator.validate(wrapper);
        assertThat(violations).isEmpty();

        RecordWrapper wrapperSame = new RecordWrapper("same", "same");
        Set<ConstraintViolation<RecordWrapper>> violationsSame = validator.validate(wrapperSame);
        assertThat(violationsSame).hasSize(1);
        assertThat(violationsSame.iterator().next().getPropertyPath().toString()).isEqualTo("field1");
    }
} 