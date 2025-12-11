package uk.gegc.quizmaker;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Common base for Mockito-powered unit tests.
 * Provides the MockitoExtension for field injections and verifications.
 */
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
public abstract class BaseUnitTest {
}
