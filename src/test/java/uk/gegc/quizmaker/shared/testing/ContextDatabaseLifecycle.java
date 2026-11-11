package uk.gegc.quizmaker.shared.testing;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import java.util.function.BooleanSupplier;

/** Prevents cached JPA contexts from outliving a shared create-drop schema in a worker. */
public final class ContextDatabaseLifecycle extends AbstractTestExecutionListener {
    private final BooleanSupplier enabled;

    public ContextDatabaseLifecycle() {
        this(() -> System.getProperty("quizmaker.tests.context-fork") != null);
    }

    ContextDatabaseLifecycle(BooleanSupplier enabled) {
        this.enabled = enabled;
    }

    @Override
    public int getOrder() {
        // Spring invokes after callbacks in reverse order: run after transaction cleanup.
        return Integer.MIN_VALUE;
    }

    @Override
    public void afterTestClass(TestContext context) {
        if (!enabled.getAsBoolean() || context.getTestClass().getEnclosingClass() != null
                || !context.hasApplicationContext()) return;
        // Do not create a context or instantiate lazy beans merely to close it.
        if (context.getApplicationContext().getBeanNamesForType(EntityManagerFactory.class, false, false).length > 0)
            context.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
    }
}
