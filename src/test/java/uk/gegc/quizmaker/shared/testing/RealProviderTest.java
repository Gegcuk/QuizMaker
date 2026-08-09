package uk.gegc.quizmaker.shared.testing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a human-run provider smoke test that is excluded from normal verification.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Tag("db-serial")
@Tag("real-provider")
@EnabledIfSystemProperty(
        named = "quizmaker.tests.live-provider",
        matches = "true",
        disabledReason = "Real provider tests require the explicit live-provider-tests Maven profile"
)
public @interface RealProviderTest {
}
