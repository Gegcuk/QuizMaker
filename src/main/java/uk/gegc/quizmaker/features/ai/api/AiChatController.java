package uk.gegc.quizmaker.features.ai.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.features.ai.api.dto.ChatRequestDto;
import uk.gegc.quizmaker.features.ai.api.dto.ChatResponseDto;
import uk.gegc.quizmaker.features.ai.application.AiChatService;

/**
 * Controller for AI chat functionality
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "AI Chat", description = "AI-powered chat and conversation")
@SecurityRequirement(name = "Bearer Authentication")
public class AiChatController {

    private final AiChatService aiChatService;

    @Operation(
            summary = "Send chat message to AI",
            description = "Sends a message to the AI and receives a response"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "AI response generated",
                    content = @Content(schema = @Schema(implementation = ChatResponseDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid message"),
            @ApiResponse(responseCode = "503", description = "AI service unavailable")
    })
    @PostMapping("/chat")
    public ResponseEntity<ChatResponseDto> chat(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Chat message request",
                    required = true
            )
            @Valid @RequestBody ChatRequestDto request) {
        log.info("Chat request received: '{}'", request.message());

        ChatResponseDto response = aiChatService.sendMessage(request.message());
        return ResponseEntity.ok(response);
    }
} 