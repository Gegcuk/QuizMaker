package uk.gegc.quizmaker.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.dto.ai.ChatRequestDto;
import uk.gegc.quizmaker.dto.ai.ChatResponseDto;
import uk.gegc.quizmaker.service.AiChatService;

/**
 * Controller for AI chat functionality
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Slf4j
public class AiChatController {

    private final AiChatService aiChatService;

    /**
     * Send a message to AI and get a response
     *
     * @param request The chat request containing the message
     * @return The AI's response
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponseDto> chat(@Valid @RequestBody ChatRequestDto request) {
        log.info("Chat request received: '{}'", request.message());

        ChatResponseDto response = aiChatService.sendMessage(request.message());
        return ResponseEntity.ok(response);
    }
} 