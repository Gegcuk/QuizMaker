package uk.gegc.quizmaker.features.ai.infra.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.StreamingHttpOutputMessage;
import uk.gegc.quizmaker.features.ai.application.ProviderDeadline;

import javax.net.ssl.SSLParameters;
import java.net.http.HttpClient;
import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.function.Supplier;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class GenerationDeadlineHttpConfiguration {
    @Bean
    public RestClientCustomizer generationDeadlineTransport(ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                                                            ClientHttpRequestFactorySettings settings) {
        var ordinaryFactory = factoryBuilder.build(settings);
        var builder = HttpClient.newBuilder();
        if (settings.connectTimeout() != null) builder.connectTimeout(settings.connectTimeout());
        if (settings.redirects() != null) {
            builder.followRedirects(switch (settings.redirects()) {
                case FOLLOW_WHEN_POSSIBLE, FOLLOW -> HttpClient.Redirect.NORMAL;
                case DONT_FOLLOW -> HttpClient.Redirect.NEVER;
            });
        }
        if (settings.sslBundle() != null) {
            builder.sslContext(settings.sslBundle().createSslContext());
            var options = settings.sslBundle().getOptions();
            var parameters = new SSLParameters();
            if (options.getEnabledProtocols() != null) parameters.setProtocols(options.getEnabledProtocols());
            if (options.getCiphers() != null) parameters.setCipherSuites(options.getCiphers());
            builder.sslParameters(parameters);
        }
        HttpClient sharedClient = builder.build();
        return restClient -> restClient.requestFactory((uri, method) -> {
            if (!ProviderDeadline.isBounded()) return ordinaryFactory.createRequest(uri, method);
            long configuredMillis = settings.readTimeout() == null ? Long.MAX_VALUE : settings.readTimeout().toMillis();
            Supplier<Long> remaining = ProviderDeadline.propagate(() -> ProviderDeadline.limitMillis(configuredMillis));
            return new DeadlineRequest(uri, method, sharedClient, remaining);
        });
    }

    /** Calculate the timeout at dispatch, including body publication in the transport deadline. */
    private static final class DeadlineRequest extends AbstractClientHttpRequest implements StreamingHttpOutputMessage {
        private final URI uri;
        private final HttpMethod method;
        private final HttpClient client;
        private final Supplier<Long> remaining;
        private final ByteArrayOutputStream bufferedBody = new ByteArrayOutputStream();
        private Body body;

        private DeadlineRequest(URI uri, HttpMethod method, HttpClient client, Supplier<Long> remaining) {
            this.uri = uri;
            this.method = method;
            this.client = client;
            this.remaining = remaining;
        }

        @Override public URI getURI() { return uri; }
        @Override public HttpMethod getMethod() { return method; }

        @Override
        protected OutputStream getBodyInternal(HttpHeaders headers) {
            if (body != null) throw new IllegalStateException("Streaming body already configured");
            return bufferedBody;
        }

        @Override
        public void setBody(Body body) {
            assertNotExecuted();
            this.body = java.util.Objects.requireNonNull(body);
        }

        @Override
        protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
            // Per-request factory: another job cannot change this exchange's timeout.
            var factory = new JdkClientHttpRequestFactory(client);
            factory.setReadTimeout(Duration.ofMillis(remaining.get()));
            var request = factory.createRequest(uri, method);
            request.getHeaders().putAll(headers);
            request.getAttributes().putAll(getAttributes());
            ((StreamingHttpOutputMessage) request).setBody(body == null ? bufferedBody::writeTo : body);
            // Spring cancels the exchange or closes an already-open response stream on expiry.
            return request.execute();
        }
    }

}
