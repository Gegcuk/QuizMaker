package uk.gegc.quizmaker.shared.testing;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Process-wide observations around class containers, including nested and overlapping containers. */
public final class TestResourceEvidence implements BeforeAllCallback, AfterAllCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(TestResourceEvidence.class);

    private static final class Holder {
        private static final TestResourceRecorder RECORDER = TestResourceRecorder.create();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!Boolean.getBoolean("quizmaker.tests.resources.enabled")) return;
        try {
            Long token = Holder.RECORDER.begin(context.getRequiredTestClass());
            context.getStore(NAMESPACE).put(context.getRequiredTestClass(), token);
        } catch (RuntimeException ignored) {
            // Diagnostics must not change a test outcome. Never print exception messages or inputs.
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (!Boolean.getBoolean("quizmaker.tests.resources.enabled")) return;
        try {
            Long token = context.getStore(NAMESPACE).remove(context.getRequiredTestClass(), Long.class);
            if (token != null) Holder.RECORDER.end(token);
        } catch (RuntimeException ignored) {
            // An unavailable measurement does not mask the original test result.
        }
    }

    static void contextClose(Class<?> type, String phase) {
        try {
            // Do not start a recorder in executions without automatic extension discovery.
            if (Boolean.getBoolean("quizmaker.tests.resources.enabled"))
                Holder.RECORDER.marker(type, phase);
        } catch (RuntimeException ignored) {
            // Preserve Spring's original cleanup behavior and exception propagation.
        }
    }
}
