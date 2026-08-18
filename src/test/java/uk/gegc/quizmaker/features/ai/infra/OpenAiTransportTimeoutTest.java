package uk.gegc.quizmaker.features.ai.infra;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.Role.USER;

@DisplayName("OpenAI blocking transport timeouts")
class OpenAiTransportTimeoutTest {

    private static final String COMPLETIONS_PATH = "/v1/chat/completions";
    private static final Duration TEST_CONNECT_TIMEOUT = Duration.ofMillis(250);
    private static final Duration TEST_READ_TIMEOUT = Duration.ofMillis(150);
    private static final Duration MAX_EXPECTED_TIMEOUT = Duration.ofSeconds(2);
    private static final String SUCCESS_RESPONSE = """
            {
              "id": "completion-id",
              "choices": [
                {
                  "finish_reason": "stop",
                  "index": 0,
                  "message": {
                    "content": "Generated question",
                    "role": "assistant"
                  }
                }
              ],
              "created": 1,
              "model": "test-model",
              "object": "chat.completion",
              "usage": {
                "completion_tokens": 4,
                "prompt_tokens": 8,
                "total_tokens": 12
              }
            }
            """;

    private final AtomicInteger requestCount = new AtomicInteger();
    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Stops a delayed OpenAI-compatible response at the configured read deadline")
    void delayedResponseStopsAtConfiguredReadDeadline() throws IOException {
        startServer(exchange -> {
            requestCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(Duration.ofSeconds(5).toMillis());
                writeJson(exchange, SUCCESS_RESPONSE);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });

        contextRunner(TEST_CONNECT_TIMEOUT, TEST_READ_TIMEOUT).run(context -> {
            assertThat(context).hasNotFailed();
            ClientHttpRequestFactorySettings settings =
                    context.getBean(ClientHttpRequestFactorySettings.class);
            assertThat(settings.connectTimeout()).isEqualTo(TEST_CONNECT_TIMEOUT);
            assertThat(settings.readTimeout()).isEqualTo(TEST_READ_TIMEOUT);
            OpenAiApi openAiApi = openAiApi(context.getBean(RestClient.Builder.class));
            long startedAt = System.nanoTime();

            Throwable failure = catchThrowable(() -> openAiApi.chatCompletionEntity(request()));

            assertTransportDeadlineFailure(failure);

            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(MAX_EXPECTED_TIMEOUT);
            assertThat(requestCount).hasValue(1);
        });
    }

    @Test
    @DisplayName("Returns a valid OpenAI-compatible response before the configured deadline")
    void responseBeforeDeadlineSucceeds() throws IOException {
        startServer(exchange -> {
            requestCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            writeJson(exchange, SUCCESS_RESPONSE);
        });

        contextRunner(TEST_CONNECT_TIMEOUT, Duration.ofSeconds(2)).run(context -> {
            assertThat(context).hasNotFailed();
            OpenAiApi openAiApi = openAiApi(context.getBean(RestClient.Builder.class));

            var response = openAiApi.chatCompletionEntity(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().choices()).hasSize(1);
            assertThat(response.getBody().choices().get(0).message().content())
                    .isEqualTo("Generated question");
            assertThat(requestCount).hasValue(1);
        });
    }

    @Test
    @DisplayName("Fails closed when an HTTP timeout duration cannot be parsed")
    void invalidTimeoutConfigurationFailsBinding() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class))
                .withPropertyValues("spring.http.client.read-timeout=not-a-duration")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("spring.http.client");
                });
    }

    @Test
    @DisplayName("Rejects a negative HTTP timeout before a provider request is sent")
    void negativeTimeoutConfigurationFailsClosedBeforeDispatch() throws IOException {
        startServer(exchange -> {
            requestCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            writeJson(exchange, SUCCESS_RESPONSE);
        });

        contextRunner(TEST_CONNECT_TIMEOUT, Duration.ofSeconds(-1)).run(context -> {
            assertThat(context).hasNotFailed();
            OpenAiApi openAiApi = openAiApi(context.getBean(RestClient.Builder.class));

            Throwable failure = catchThrowable(() -> openAiApi.chatCompletionEntity(request()));

            assertTransportDeadlineFailure(failure);
            assertThat(requestCount).hasValue(0);
        });
    }

    private void assertTransportDeadlineFailure(Throwable failure) {
        assertThat(failure)
                .isNotNull()
                .isInstanceOfAny(ResourceAccessException.class, CancellationException.class);
        if (failure instanceof ResourceAccessException) {
            assertThat(failure).hasRootCauseInstanceOf(HttpTimeoutException.class);
        }
    }

    private ApplicationContextRunner contextRunner(Duration connectTimeout, Duration readTimeout) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class,
                        HttpClientAutoConfiguration.class,
                        RestClientAutoConfiguration.class
                ))
                .withPropertyValues(
                        "spring.http.client.factory=jdk",
                        "spring.http.client.connect-timeout=" + connectTimeout,
                        "spring.http.client.read-timeout=" + readTimeout
                );
    }

    private OpenAiApi openAiApi(RestClient.Builder restClientBuilder) {
        return OpenAiApi.builder()
                .baseUrl("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort())
                .apiKey("offline-test-key")
                .completionsPath(COMPLETIONS_PATH)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(WebClient.builder())
                .build();
    }

    private OpenAiApi.ChatCompletionRequest request() {
        var message = new OpenAiApi.ChatCompletionMessage("Generate one question", USER);
        return new OpenAiApi.ChatCompletionRequest(List.of(message), false);
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serverExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "openai-timeout-test-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverExecutor);
        server.createContext(COMPLETIONS_PATH, exchange -> handler.handle(exchange));
        server.start();
    }

    private void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(HttpStatus.OK.value(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {

        void handle(HttpExchange exchange) throws IOException;
    }
}
