package uk.gegc.quizmaker.features.documentProcess.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DocumentStructurePersistenceConfigTest {
    private final EntityManagerFactory factory = mock(EntityManagerFactory.class);
    private final DocumentStructurePersistenceConfig config = new DocumentStructurePersistenceConfig();

    @Test
    void structurePostNeverOpensARequestPersistenceContext() {
        var interceptor = config.openEntityManagerInViewInterceptor(factory);
        var request = request("POST", "/api/v1/documentProcess/documents/{id}/structure");

        interceptor.preHandle(request);
        assertThat(TransactionSynchronizationManager.hasResource(factory)).isFalse();
        interceptor.afterConcurrentHandlingStarted(request);
        interceptor.afterCompletion(request, null);
        verifyNoInteractions(factory);
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/v1/documentProcess/documents/{id}/structure",
            "POST,/api/v1/documentProcess/documents",
            "POST,/api/v1/another-feature/{id}/structure"
    })
    void otherRoutesRetainTheRequestContextAndCloseIt(String method, String path) {
        EntityManager manager = mock(EntityManager.class);
        when(factory.createEntityManager()).thenReturn(manager);
        when(manager.isOpen()).thenReturn(true);
        var interceptor = config.openEntityManagerInViewInterceptor(factory);
        var request = request(method, path);

        interceptor.preHandle(request);
        try {
            assertThat(TransactionSynchronizationManager.hasResource(factory)).isTrue();
        } finally {
            interceptor.afterCompletion(request, null);
        }
        assertThat(TransactionSynchronizationManager.hasResource(factory)).isFalse();
        verify(manager).close();
    }

    @Test
    void globallyDisabledOsivIsNotReenabled() {
        new WebApplicationContextRunner().withUserConfiguration(DocumentStructurePersistenceConfig.class)
                .withPropertyValues("spring.jpa.open-in-view=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean("openEntityManagerInViewInterceptor")
                        .doesNotHaveBean("documentStructurePersistenceConfigurer"));
    }

    private ServletWebRequest request(String method, String pattern) {
        var servlet = new MockHttpServletRequest(method, pattern.replace("{id}", "example-id"));
        servlet.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        return new ServletWebRequest(servlet);
    }
}
