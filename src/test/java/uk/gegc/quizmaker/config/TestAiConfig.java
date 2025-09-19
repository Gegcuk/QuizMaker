package uk.gegc.quizmaker.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestConfiguration
@Profile({"test", "!real-ai"})
public class TestAiConfig {

    private final AtomicReference<String> lastUserMessage = new AtomicReference<>();

    @Bean
    @Primary
    public ChatClient testChatClient() {
        ChatClient mockChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec mockCallSpec = mock(ChatClient.CallResponseSpec.class);

        // Configure the mock chain
        when(mockChatClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(any(String.class))).thenAnswer(invocation -> {
            String message = invocation.getArgument(0);
            lastUserMessage.set(message);
            return mockRequestSpec;
        });
        when(mockRequestSpec.call()).thenReturn(mockCallSpec);

        // Create mock response
        when(mockCallSpec.chatResponse()).thenAnswer(invocation -> {
            String userMessage = lastUserMessage.get();
            String aiResponse = generateMockResponse(userMessage);

            // Add artificial delay to simulate realistic AI response time
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Create a proper mock response with all required methods
            ChatResponse mockResponse = mock(ChatResponse.class);
            Generation mockGeneration = mock(Generation.class);
            AssistantMessage mockAssistantMessage = mock(AssistantMessage.class);

            // Configure the mock chain for the main response
            when(mockResponse.getResult()).thenReturn(mockGeneration);
            when(mockGeneration.getOutput()).thenReturn(mockAssistantMessage);
            when(mockAssistantMessage.getText()).thenReturn(aiResponse);

            // Return null metadata - the service handles this gracefully
            when(mockResponse.getMetadata()).thenReturn(null);

            return mockResponse;
        });

        return mockChatClient;
    }

    private String getLastUserMessage() {
        return lastUserMessage.get() != null ? lastUserMessage.get() : "Hello";
    }

    private String generateMockResponse(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return "I didn't receive a message. How can I help you?";
        }

        String lowerMessage = userMessage.toLowerCase();

        if (lowerMessage.contains("joke")) {
            return "Why don't scientists trust atoms? Because they make up everything! ????";
        } else if (lowerMessage.contains("2+2") || lowerMessage.contains("what is 2+2")) {
            return "2+2 equals 4! It's a basic mathematical fact.";
        } else if (lowerMessage.contains("computer")) {
            return "A computer is an electronic device that processes data and performs calculations. It can store, retrieve, and process information. ???????";
        } else if (lowerMessage.contains("artificial intelligence") || lowerMessage.contains("ai")) {
            return "Artificial Intelligence (AI) is technology that enables machines to simulate human intelligence. It can learn, reason, and make decisions based on data.";
        } else if (lowerMessage.contains("capital") && lowerMessage.contains("france")) {
            return "Paris is the capital city of France. It's known for the Eiffel Tower, Louvre Museum, and rich cultural heritage.";
        } else if (lowerMessage.contains("paris") || lowerMessage.contains("france")) {
            return "Paris is the capital city of France. It's known for the Eiffel Tower, Louvre Museum, and rich cultural heritage.";
        } else if (lowerMessage.contains("hello") || lowerMessage.contains("hi")) {
            return "Hello! How can I assist you today? I'm here to help with any questions you might have.";
        } else if (lowerMessage.contains("symbol") || lowerMessage.contains("@#$%")) {
            return "Those are special characters and symbols commonly used in programming and communication. @ is an at symbol, # is a hash, $ is a dollar sign, etc.";
        } else if (lowerMessage.contains("bonjour") || lowerMessage.contains("hola")) {
            return "Hello! Bonjour! Hola! Those are greetings in different languages. 'Bonjour' is French for hello, and 'Hola' is Spanish for hello.";
        } else {
            return "I understand you said: \"" + userMessage + "\". How can I help you with that?";
        }
    }
} 
