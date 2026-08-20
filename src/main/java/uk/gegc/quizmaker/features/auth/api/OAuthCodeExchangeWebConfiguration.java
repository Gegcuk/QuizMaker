package uk.gegc.quizmaker.features.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

/**
 * Applies the public-IP guard before MVC reads or deserializes exchange credentials.
 * The MVC configurer is exposed as a bean so unrelated {@code @WebMvcTest} slices do not
 * auto-import this OAuth-only configuration and its application-service dependencies.
 */
@Configuration(proxyBeanMethods = false)
public class OAuthCodeExchangeWebConfiguration {

    private static final String EXCHANGE_PATH = "/api/v1/auth/oauth/exchange";
    private static final int IP_ATTEMPTS_PER_MINUTE = 30;

    @Bean
    WebMvcConfigurer oauthCodeExchangeRateLimitConfigurer(
            RateLimitService rateLimitService,
            TrustedProxyUtil trustedProxyUtil
    ) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                HandlerInterceptor ipGuard = new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler
                    ) {
                        if (HttpMethod.POST.matches(request.getMethod())) {
                            rateLimitService.checkRateLimit(
                                    "oauth-code-exchange-ip",
                                    trustedProxyUtil.getClientIp(request),
                                    IP_ATTEMPTS_PER_MINUTE
                            );
                        }
                        return true;
                    }
                };
                registry.addInterceptor(ipGuard).addPathPatterns(EXCHANGE_PATH);
            }
        };
    }
}
