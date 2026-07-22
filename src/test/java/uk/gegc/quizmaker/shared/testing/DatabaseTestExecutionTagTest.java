package uk.gegc.quizmaker.shared.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.support.AnnotationSupport;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class DatabaseTestExecutionTagTest {

    private static final String TEST_PACKAGE_PATH = "uk/gegc/quizmaker/";
    private static final String TEST_PACKAGE_NAME = "uk.gegc.quizmaker.";

    @Test
    void databaseContextTests_useTheDedicatedSerialExecutionLane() throws IOException {
        List<Class<?>> databaseContextTests = discoverTestClasses().stream()
                .filter(this::usesSharedMySqlSchema)
                .toList();

        assertThat(databaseContextTests)
                .as("Spring Boot and JPA tests sharing the CI MySQL schemas")
                .isNotEmpty()
                .allSatisfy(testClass -> assertThat(findDbSerialTag(testClass))
                        .as("%s must be tagged for the serial MySQL lane", testClass.getName())
                        .isPresent());
    }

    private List<Class<?>> discoverTestClasses() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:uk/gegc/quizmaker/**/*.class");
        List<Class<?>> testClasses = new ArrayList<>();

        for (Resource resource : resources) {
            className(resource.getURL()).flatMap(this::loadClass).ifPresent(testClasses::add);
        }

        return testClasses.stream()
                .sorted(Comparator.comparing(Class::getName))
                .toList();
    }

    private Optional<String> className(URL classUrl) {
        String externalForm = classUrl.toExternalForm();
        int packageStart = externalForm.indexOf(TEST_PACKAGE_PATH);

        if (packageStart < 0 || !externalForm.endsWith(".class")) {
            return Optional.empty();
        }

        String relativeClassPath = externalForm.substring(packageStart, externalForm.length() - ".class".length());
        return Optional.of(relativeClassPath.replace('/', '.').replace('\\', '.'));
    }

    private Optional<Class<?>> loadClass(String className) {
        try {
            return Optional.of(Class.forName(className, false, getClass().getClassLoader()));
        } catch (ClassNotFoundException | LinkageError exception) {
            return Optional.empty();
        }
    }

    private boolean usesSharedMySqlSchema(Class<?> testClass) {
        return AnnotationSupport.isAnnotated(testClass, SpringBootTest.class)
                || AnnotationSupport.isAnnotated(testClass, DataJpaTest.class);
    }

    private Optional<Tag> findDbSerialTag(Class<?> testClass) {
        return AnnotationSupport.findRepeatableAnnotations(testClass, Tag.class).stream()
                .filter(tag -> tag.value().equals("db-serial"))
                .findFirst();
    }
}
