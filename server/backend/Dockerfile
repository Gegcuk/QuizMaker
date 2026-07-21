FROM eclipse-temurin:17-jre-alpine

# Install curl for health checks
RUN apk add --no-cache curl

# Create non-root user for security
RUN addgroup -g 1001 -S quizmaker && \
    adduser -S quizmaker -u 1001 -G quizmaker

# Set working directory
WORKDIR /app

# The JAR is packaged by the successful CI run and placed in this build context.
COPY QuizMaker-*.jar app.jar
COPY application-prod.properties /app/config/application-prod.properties

# Create uploads directory and set permissions
RUN mkdir -p /app/uploads/documents && \
    chown -R quizmaker:quizmaker /app

# Switch to non-root user
USER quizmaker

# Expose port
EXPOSE 8080

# Health check (use minimal startup group to avoid external dependencies)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health/startup || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.config.additional-location=file:/app/config/"]
