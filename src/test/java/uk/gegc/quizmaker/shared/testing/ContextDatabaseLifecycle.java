package uk.gegc.quizmaker.shared.testing;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import java.util.function.BooleanSupplier;

/** Closes JPA contexts for schema isolation and JDBC slices to release their pools between worker suites. */
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
        if (AnnotatedElementUtils.hasAnnotation(context.getTestClass(), JdbcTest.class)
                || context.getApplicationContext().getBeanNamesForType(EntityManagerFactory.class, false, false).length > 0) {
            TestResourceEvidence.contextClose(context.getTestClass(), "context_close_start");
            boolean completed = false;
            try {
                context.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
                completed = true;
            } finally {
                TestResourceEvidence.contextClose(context.getTestClass(),
                        completed ? "context_close_end" : "context_close_failed");
            }
        }
    }
}
