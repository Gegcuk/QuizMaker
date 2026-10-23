package uk.gegc.quizmaker.features.documentProcess.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Structure builds own short persistence contexts instead of retaining one across provider calls. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "spring.jpa.open-in-view", havingValue = "true", matchIfMissing = true)
public class DocumentStructurePersistenceConfig {
    private static final String BUILD_PATH = "/api/v1/documentProcess/documents/{id}/structure";
    private static final String SKIPPED = DocumentStructurePersistenceConfig.class.getName() + ".SKIPPED";

    @Bean
    OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor(EntityManagerFactory factory) {
        var interceptor = new OpenEntityManagerInViewInterceptor() {
            @Override
            public void preHandle(WebRequest request) {
                if (request instanceof ServletWebRequest servlet
                        && HttpMethod.POST.equals(servlet.getHttpMethod())
                        && BUILD_PATH.equals(request.getAttribute(
                                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST))) {
                    request.setAttribute(SKIPPED, true, RequestAttributes.SCOPE_REQUEST);
                    return;
                }
                super.preHandle(request);
            }

            @Override
            public void afterCompletion(WebRequest request, Exception exception) {
                if (!Boolean.TRUE.equals(request.getAttribute(SKIPPED, RequestAttributes.SCOPE_REQUEST))) {
                    super.afterCompletion(request, exception);
                }
            }

            @Override
            public void afterConcurrentHandlingStarted(WebRequest request) {
                if (!Boolean.TRUE.equals(request.getAttribute(SKIPPED, RequestAttributes.SCOPE_REQUEST))) {
                    super.afterConcurrentHandlingStarted(request);
                }
            }
        };
        interceptor.setEntityManagerFactory(factory);
        return interceptor;
    }

    // Defining the interceptor makes Boot's default OSIV configuration back off; register it here.
    @Bean
    WebMvcConfigurer documentStructurePersistenceConfigurer(OpenEntityManagerInViewInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addWebRequestInterceptor(interceptor);
            }
        };
    }
}
