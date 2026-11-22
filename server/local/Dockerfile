FROM eclipse-temurin:17-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 1001 quizmaker && \
    useradd --uid 1001 --gid quizmaker --no-create-home quizmaker

WORKDIR /app
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.revision=$SOURCE_REVISION

COPY target/QuizMaker-*.jar app.jar
COPY server/local/application-local.properties config/application-local.properties
RUN mkdir -p uploads/documents && chown -R quizmaker:quizmaker /app

USER quizmaker
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.config.additional-location=file:/app/config/application-local.properties"]
