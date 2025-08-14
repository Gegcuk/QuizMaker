package uk.gegc.quizmaker.service;

import uk.gegc.quizmaker.features.ai.api.dto.ChatResponseDto;

public interface AiChatService {

    /**
     * Send a message to the AI and get a response
     *
     * @param message The message to send to the AI
     * @return The AI's response
     */
    ChatResponseDto sendMessage(String message);
} 