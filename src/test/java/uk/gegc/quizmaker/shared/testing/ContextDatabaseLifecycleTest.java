package uk.gegc.quizmaker.shared.testing;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContextDatabaseLifecycleTest {
    @Test
    void defaultExecutionDoesNotChangeContextLifecycle() {
        TestContext context = mock(TestContext.class);
        new ContextDatabaseLifecycle(() -> false).afterTestClass(context);
        verifyNoInteractions(context);
    }

    @Test
    void closesJpaContextAfterTopLevelSuiteAndAfterOtherCleanup() {
        TestContext context = contextFor(ContextDatabaseLifecycleTest.class);
        ApplicationContext application = mock(ApplicationContext.class);
        when(context.hasApplicationContext()).thenReturn(true);
        when(context.getApplicationContext()).thenReturn(application);
        when(application.getBeanNamesForType(EntityManagerFactory.class, false, false))
                .thenReturn(new String[]{"entityManagerFactory"});
        ContextDatabaseLifecycle listener = new ContextDatabaseLifecycle(() -> true);
        listener.afterTestClass(context);
        verify(context).markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
        assertThat(listener.getOrder()).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void closesJdbcSliceAfterTopLevelSuiteWithoutLookingUpJpaBeans() {
        TestContext context = contextFor(LifecycleJdbcSliceFixture.class);
        when(context.hasApplicationContext()).thenReturn(true);

        new ContextDatabaseLifecycle(() -> true).afterTestClass(context);

        verify(context).markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
        verify(context, never()).getApplicationContext();
    }

    @Test
    void doesNotRecreateAlreadyClosedContextOrCloseEnclosingSuiteEarly() {
        TestContext closed = contextFor(LifecycleJdbcSliceFixture.class);
        new ContextDatabaseLifecycle(() -> true).afterTestClass(closed);
        verify(closed, never()).getApplicationContext();
        verify(closed, never()).markApplicationContextDirty(any());
        TestContext nested = contextFor(NestedFixture.class);
        new ContextDatabaseLifecycle(() -> true).afterTestClass(nested);
        verify(nested, never()).hasApplicationContext();
        verify(nested, never()).markApplicationContextDirty(any());
    }

    @Test
    void preservesMvcAndOtherContextsWithoutJpa() {
        TestContext context = contextFor(ContextDatabaseLifecycleTest.class);
        ApplicationContext application = mock(ApplicationContext.class);
        when(context.hasApplicationContext()).thenReturn(true);
        when(context.getApplicationContext()).thenReturn(application);
        when(application.getBeanNamesForType(EntityManagerFactory.class, false, false)).thenReturn(new String[0]);
        new ContextDatabaseLifecycle(() -> true).afterTestClass(context);
        verify(context, never()).markApplicationContextDirty(any());
    }

    private TestContext contextFor(Class<?> type) {
        TestContext context = mock(TestContext.class);
        doReturn(type).when(context).getTestClass();
        return context;
    }

    @JdbcTest
    private static class NestedFixture {}
}

@JdbcTest
class LifecycleJdbcSliceFixture {}
