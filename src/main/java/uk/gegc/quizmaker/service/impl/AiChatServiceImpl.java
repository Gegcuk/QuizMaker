package uk.gegc.quizmaker.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.dto.ai.ChatResponseDto;
import uk.gegc.quizmaker.exception.AiServiceException;
import uk.gegc.quizmaker.service.AiChatService;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiChatServiceImpl implements AiChatService {

    private final ChatClient chatClient;

    @Override
    public ChatResponseDto sendMessage(String message) {
        Instant start = Instant.now();
        
        // Validate input
        if (message == null || message.trim().isEmpty()) {
            throw new AiServiceException("Message cannot be null or empty");
        }
        
        log.info("Sending message to AI: '{}'", message);
        
        try {
            ChatResponse response = chatClient.prompt()
                    .user(message)
                    .call()
                    .chatResponse();
            
            if (response == null) {
                throw new AiServiceException("No response received from AI service");
            }
            
            String aiResponse = response.getResult().getOutput().getText();
            
            long latency = Duration.between(start, Instant.now()).toMillis();
            int tokensUsed = response.getMetadata() != null ? 
                response.getMetadata().getUsage().getTotalTokens() : aiResponse.length() / 4; // Rough estimate
            String modelUsed = response.getMetadata() != null ? 
                response.getMetadata().getModel() : "gpt-3.5-turbo";
            
            log.info("AI response received - Model: {}, Tokens: {}, Latency: {}ms", 
                    modelUsed, tokensUsed, latency);
            
            return new ChatResponseDto(aiResponse, modelUsed, latency, tokensUsed);
            
        } catch (Exception e) {
            log.error("Error calling AI service for message: '{}'", message, e);
            throw new AiServiceException("Failed to get AI response", e);
        }
    }
} 