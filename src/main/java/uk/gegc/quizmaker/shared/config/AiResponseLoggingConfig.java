package uk.gegc.quizmaker.shared.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Configuration
public class AiResponseLoggingConfig {

    private static final String AI_RESPONSE_LOGGER_NAME = "ai.response.logger";
    private static final String LOG_FILE_PREFIX = "ai-responses";

    @Bean
    @Lazy
    public Logger aiResponseLogger() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        
        // Create the logger
        Logger aiLogger = loggerContext.getLogger(AI_RESPONSE_LOGGER_NAME);
        aiLogger.setAdditive(false); // Don't propagate to parent loggers
        
        // Create file appender
        FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
        fileAppender.setContext(loggerContext);
        fileAppender.setName("ai-response-file-appender");
        
        // Set file path with timestamp
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String fileName = String.format("%s_%s.log", LOG_FILE_PREFIX, timestamp);
        
        // Create logs directory if it doesn't exist
        try {
            Path logsDir = Paths.get("logs");
            if (!Files.exists(logsDir)) {
                Files.createDirectories(logsDir);
            }
            fileAppender.setFile(logsDir.resolve(fileName).toString());
        } catch (Exception e) {
            // Fallback to current directory if logs directory creation fails
            fileAppender.setFile(fileName);
        }
        
        // Create encoder
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern("%msg%n"); // Only log the message, no timestamp or other metadata
        encoder.start();
        
        fileAppender.setEncoder(encoder);
        fileAppender.start();
        
        // Add appender to logger
        aiLogger.addAppender(fileAppender);
        
        return aiLogger;
    }
} 