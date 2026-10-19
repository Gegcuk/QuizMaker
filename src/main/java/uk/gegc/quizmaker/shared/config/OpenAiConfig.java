package uk.gegc.quizmaker.shared.config;

import org.springframework.aop.framework.AopProxyUtils;
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
        // Spring AOP wrappers must not silently downgrade the underlying OpenAI adapter.
        // Other adapters retain their own default options and receive schema instructions.
        return OpenAiChatModel.class.isAssignableFrom(AopProxyUtils.ultimateTargetClass(chatModel))
                ? SchemaDeliveryMode.OPENAI_STRICT
                : SchemaDeliveryMode.PROMPT_SCHEMA;
    }
}
