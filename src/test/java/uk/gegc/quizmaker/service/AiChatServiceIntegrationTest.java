package uk.gegc.quizmaker.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.config.TestAiConfig;
import uk.gegc.quizmaker.dto.ai.ChatResponseDto;
import uk.gegc.quizmaker.exception.AiServiceException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test-mysql")
@Import(TestAiConfig.class)
class AiChatServiceIntegrationTest {

    @Autowired
    private AiChatService aiChatService;

    @Test
    void testSendMessageWithValidInput() {
        // Given
        String message = "Hello, can you tell me a short joke?";

        // When
        ChatResponseDto response = aiChatService.sendMessage(message);

        // Then
        assertNotNull(response);
        assertNotNull(response.message());
        assertFalse(response.message().isEmpty());
        assertNotNull(response.model());
        assertTrue(response.latency() > 0);
        assertTrue(response.tokensUsed() > 0);
        assertNotNull(response.timestamp());

        System.out.println("AI Response: " + response.message());
        System.out.println("Model: " + response.model());
        System.out.println("Latency: " + response.latency() + "ms");
        System.out.println("Tokens Used: " + response.tokensUsed());
    }

    @Test
    void testSendMessageWithSimpleQuestion() {
        // Given
        String message = "What is 2+2?";

        // When
        ChatResponseDto response = aiChatService.sendMessage(message);

        // Then
        assertNotNull(response);
        assertNotNull(response.message());
        assertFalse(response.message().isEmpty());
        assertTrue(response.message().toLowerCase().contains("4") ||
                response.message().toLowerCase().contains("four"));
    }

    @Test
    void testSendMessageWithEmojis() {
        // Given
        String message = "Can you explain what a computer is? 🖥️";

        // When
        ChatResponseDto response = aiChatService.sendMessage(message);

        // Then
        assertNotNull(response);
        assertNotNull(response.message());
        assertFalse(response.message().isEmpty());
        assertTrue(response.message().toLowerCase().contains("computer") ||
                response.message().toLowerCase().contains("machine") ||
                response.message().toLowerCase().contains("device"));
    }

    @Test
    void testSendMessageWithLongerText() {
        // Given
        String message = "Please explain the concept of artificial intelligence in simple terms.";

        // When
        ChatResponseDto response = aiChatService.sendMessage(message);

        // Then
        assertNotNull(response);
        assertNotNull(response.message());
        assertFalse(response.message().isEmpty());
        assertTrue(response.message().length() > 20); // Should be a substantial response
    }

    @Test
    void testSendMessageWithEmptyString() {
        // Given
        String message = "";

        // When & Then
        assertThrows(AiServiceException.class, () -> {
            aiChatService.sendMessage(message);
        });
    }

    @Test
    void testSendMessageWithNullString() {
        // Given
        String message = null;

        // When & Then
        assertThrows(AiServiceException.class, () -> {
            aiChatService.sendMessage(message);
        });
    }

    @Test
    void testSendMessageWithVeryShortInput() {
        // Given
        String message = "Hi";

        // When
        ChatResponseDto response = aiChatService.sendMessage(message);

        // Then
        assertNotNull(response);
        assertNotNull(response.message());
        assertFalse(response.message().isEmpty());
    }

    @Test
    void testSendMessageWithSpecialCharacters() {
        // Given
        String message = "What does this symbol mean: @#$%^&*()?";

        // When
        ChatResponseDto response = aiChatService.sendMessage(message);

        // Then
        assertNotNull(response);
        assertNotNull(response.message());
        assertFalse(response.message().isEmpty());
    }

    @Test
    void testSendMessageWithMultilingualText() {
        // Given
        String message = "Hello! Bonjour! Hola! How do you say hello in different languages?";

        // When
        ChatResponseDto response = aiChatService.sendMessage(message);

        // Then
        assertNotNull(response);
        assertNotNull(response.message());
        assertFalse(response.message().isEmpty());
    }

    @Test
    void testResponseConsistency() {
        // Given
        String message = "What is the capital of France?";

        // When
        ChatResponseDto response1 = aiChatService.sendMessage(message);
        ChatResponseDto response2 = aiChatService.sendMessage(message);

        // Then
        assertNotNull(response1);
        assertNotNull(response2);
        assertNotNull(response1.message());
        assertNotNull(response2.message());

        // Both responses should mention Paris (though exact text may vary)
        String response1Lower = response1.message().toLowerCase();
        String response2Lower = response2.message().toLowerCase();

        assertTrue(response1Lower.contains("paris") || response2Lower.contains("paris") ||
                response1Lower.contains("france") || response2Lower.contains("france"));
    }
} 