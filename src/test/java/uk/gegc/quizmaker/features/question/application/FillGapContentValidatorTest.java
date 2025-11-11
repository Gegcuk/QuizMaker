package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FillGapContentValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Valid fill-gap content with single gap passes validation")
    void validSingleGap() throws Exception {
        String json = """
            {
              "text": "The capital of France is {1}",
              "gaps": [
                {"id": 1, "answer": "Paris"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertTrue(result.valid());
        assertNull(result.errorMessage());
    }

    @Test
    @DisplayName("Valid fill-gap content with multiple gaps passes validation")
    void validMultipleGaps() throws Exception {
        String json = """
            {
              "text": "Spring provides {1} and {2} for {3} applications",
              "gaps": [
                {"id": 1, "answer": "dependency injection"},
                {"id": 2, "answer": "inversion of control"},
                {"id": 3, "answer": "Java"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertTrue(result.valid());
    }

    @Test
    @DisplayName("Null content fails validation")
    void nullContent() {
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(null);
        
        assertFalse(result.valid());
        assertEquals("Content must be a JSON object", result.errorMessage());
    }

    @Test
    @DisplayName("Missing text field fails validation")
    void missingTextField() throws Exception {
        String json = """
            {
              "gaps": [
                {"id": 1, "answer": "Paris"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertEquals("FILL_GAP requires non-empty 'text' field", result.errorMessage());
    }

    @Test
    @DisplayName("Empty text fails validation")
    void emptyText() throws Exception {
        String json = """
            {
              "text": "",
              "gaps": [
                {"id": 1, "answer": "Paris"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertEquals("FILL_GAP requires non-empty 'text' field", result.errorMessage());
    }

    @Test
    @DisplayName("Missing gaps array fails validation")
    void missingGapsArray() throws Exception {
        String json = """
            {
              "text": "The capital is {1}"
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertEquals("FILL_GAP must have at least one gap defined", result.errorMessage());
    }

    @Test
    @DisplayName("Empty gaps array fails validation")
    void emptyGapsArray() throws Exception {
        String json = """
            {
              "text": "The capital is {1}",
              "gaps": []
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertEquals("FILL_GAP must have at least one gap defined", result.errorMessage());
    }

    @Test
    @DisplayName("Gap missing ID fails validation")
    void gapMissingId() throws Exception {
        String json = """
            {
              "text": "The capital is {1}",
              "gaps": [
                {"answer": "Paris"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertEquals("Each gap must have an 'id' and 'answer'", result.errorMessage());
    }

    @Test
    @DisplayName("Gap with blank answer fails validation")
    void gapBlankAnswer() throws Exception {
        String json = """
            {
              "text": "The capital is {1}",
              "gaps": [
                {"id": 1, "answer": "   "}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertEquals("Gap answer cannot be blank", result.errorMessage());
    }

    @Test
    @DisplayName("Gap ID must be integer")
    void gapIdNotInteger() throws Exception {
        String json = """
            {
              "text": "The capital is {1}",
              "gaps": [
                {"id": "one", "answer": "Paris"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertEquals("Gap 'id' must be an integer", result.errorMessage());
    }

    @Test
    @DisplayName("Gap ID must be positive")
    void gapIdZero() throws Exception {
        String json = """
            {
              "text": "The capital is {0}",
              "gaps": [
                {"id": 0, "answer": "Paris"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("must be positive integers starting from 1"));
    }

    @Test
    @DisplayName("Duplicate gap IDs fail validation")
    void duplicateGapIds() throws Exception {
        String json = """
            {
              "text": "The capital is {1}",
              "gaps": [
                {"id": 1, "answer": "Paris"},
                {"id": 1, "answer": "London"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("must be unique"));
    }

    @Test
    @DisplayName("Non-sequential gap IDs fail validation")
    void nonSequentialGapIds() throws Exception {
        String json = """
            {
              "text": "The {1} and {3} are cities",
              "gaps": [
                {"id": 1, "answer": "Paris"},
                {"id": 3, "answer": "London"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("sequential"));
        assertTrue(result.errorMessage().contains("Expected id=2"));
    }

    @Test
    @DisplayName("Placeholder in text without corresponding gap fails validation")
    void placeholderWithoutGap() throws Exception {
        String json = """
            {
              "text": "The {1} and {2} are cities",
              "gaps": [
                {"id": 1, "answer": "Paris"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("Gap {2} found in text"));
    }

    @Test
    @DisplayName("Gap not referenced in text fails validation")
    void gapNotInText() throws Exception {
        String json = """
            {
              "text": "The capital is {1}",
              "gaps": [
                {"id": 1, "answer": "Paris"},
                {"id": 2, "answer": "London"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("Gap with id=2 defined in gaps array but {2} not found in text"));
    }

    @Test
    @DisplayName("Text with no placeholders fails validation")
    void textWithoutPlaceholders() throws Exception {
        String json = """
            {
              "text": "The capital of France is Paris",
              "gaps": [
                {"id": 1, "answer": "Paris"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertFalse(result.valid());
        assertEquals("No gaps found in text. Use {N} format (e.g., {1}, {2}) to mark gap positions", result.errorMessage());
    }

    @Test
    @DisplayName("Complex valid scenario with gaps in different order")
    void complexValidScenario() throws Exception {
        String json = """
            {
              "text": "In {1}, the {2} was built in {3} to commemorate {1}'s centennial",
              "gaps": [
                {"id": 1, "answer": "1889"},
                {"id": 2, "answer": "Eiffel Tower"},
                {"id": 3, "answer": "Paris"}
              ]
            }
            """;
        
        JsonNode content = objectMapper.readTree(json);
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(content);
        
        assertTrue(result.valid());
    }
}

