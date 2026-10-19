package uk.gegc.quizmaker.shared.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gegc.quizmaker.features.ai.application.SchemaDeliveryMode;

@Configuration
public class OpenAiConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    @Bean
    public SchemaDeliveryMode schemaDeliveryMode(ChatModel chatModel) {
        // Only this adapter is known to understand OpenAI's strict response format.
        // Other adapters retain their own default options and receive schema instructions.
        return chatModel instanceof OpenAiChatModel
                ? SchemaDeliveryMode.OPENAI_STRICT
                : SchemaDeliveryMode.PROMPT_SCHEMA;
    }
}
