package uk.gegc.quizmaker.shared.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.annotation.Testable;
import org.junit.platform.commons.support.AnnotationSupport;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.ContextConfiguration;

import javax.xml.parsers.DocumentBuilderFactory;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Reconcile the committed selection with compiled tests, not annotation-text counts. */
class ContextTestManifestTest {
    private static final Path ROOT = Path.of(System.getProperty("maven.multiModuleProjectDirectory", "."));
    private static final Pattern PROGRAMMATIC = Pattern.compile(
            "new\\s+(?:\\w*ApplicationContextRunner|\\w*ApplicationContext|SpringApplicationBuilder)\\s*\\("
                    + "|SpringApplication\\.(?:run|from)\\(");

    @Test
    void everyContextAndDatabaseSuiteIsAssignedExactlyOnce() throws Exception {
        JsonNode manifest = new ObjectMapper().readTree(ROOT.resolve("tools/testing/context-test-manifest.json").toFile());
        Set<String> contexts = new TreeSet<>(), database = new TreeSet<>(), excluded = new TreeSet<>(), dormant = new TreeSet<>();
        Set<String> pomExcludes = new TreeSet<>();
        var pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ROOT.resolve("pom.xml").toFile());
        var excludes = pom.getElementsByTagName("exclude");
        for (int i = 0; i < excludes.getLength(); i++) {
            String value = excludes.item(i).getTextContent().trim();
            if (value.startsWith("**/") && value.endsWith(".java")) pomExcludes.add(value.substring(3, value.length() - 5));
        }
        Path compiled = ROOT.resolve("target/test-classes");
        try (var files = Files.walk(compiled.resolve("uk/gegc/quizmaker"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class") && !p.getFileName().toString().contains("$")).toList()) {
                String name = compiled.relativize(file).toString().replace('/', '.').replace('\\', '.').replaceAll("\\.class$", "");
                Class<?> type = Class.forName(name, false, getClass().getClassLoader());
                if (!runnable(type)) continue;
                boolean context = context(type) || PROGRAMMATIC.matcher(
                        Files.readString(ROOT.resolve("src/test/java/" + name.replace('.', '/') + ".java"))).find();
                boolean db = tagged(type, "db-serial");
                if (!context && !db) continue;
                if (pomExcludes.contains(type.getSimpleName()) || tagged(type, "real-provider")) excluded.add(name);
                else if (!type.getSimpleName().matches("Test.*|.*Test|.*Tests|.*TestCase")) dormant.add(name);
                else if (context) contexts.add(name);
                else database.add(name);
            }
        }
        assertSection(manifest, "contexts", contexts);
        assertSection(manifest, "database_companions", database);
        assertSection(manifest, "excluded", excluded);
        assertSection(manifest, "not_discovered", dormant);
        List<String> all = new ArrayList<>();
        for (String section : List.of("contexts", "database_companions", "excluded", "not_discovered"))
            manifest.get(section).forEach(n -> all.add(n.asText()));
        assertThat(all).doesNotHaveDuplicates();
        Set<String> selected = new TreeSet<>(contexts);
        selected.addAll(database);
        List<String> includes = Files.readAllLines(ROOT.resolve("tools/testing/context-suites.txt")).stream()
                .filter(s -> !s.isBlank() && !s.startsWith("#")).toList();
        assertThat(includes).containsExactlyElementsOf(selected.stream().map(n -> n.replace('.', '/') + ".java").toList());
        List<String> exclusions = Files.readAllLines(ROOT.resolve("tools/testing/context-excludes.txt")).stream()
                .filter(s -> !s.isBlank() && !s.startsWith("#")).toList();
        assertThat(exclusions).containsExactlyElementsOf(selected.stream().flatMap(n ->
                java.util.stream.Stream.of(n.replace('.', '/') + ".java", n.replace('.', '/') + "$*.java")).toList());
        List<String> exclusive = new ArrayList<>();
        manifest.required("exclusive_contexts").forEach(n -> exclusive.add(n.asText()));
        assertThat(exclusive).isNotEmpty().doesNotHaveDuplicates();
        assertThat(contexts).containsAll(exclusive);
        assertThat(Files.readAllLines(ROOT.resolve("tools/testing/context-exclusive-suites.txt")))
                .containsExactlyElementsOf(exclusive.stream().sorted().map(n -> n.replace('.', '/') + ".java").toList());
        assertThat(Files.readAllLines(ROOT.resolve("tools/testing/context-exclusive-excludes.txt")))
                .containsExactlyElementsOf(exclusive.stream().sorted().flatMap(n ->
                        java.util.stream.Stream.of(n.replace('.', '/') + ".java", n.replace('.', '/') + "$*.java")).toList());
    }

    private static void assertSection(JsonNode manifest, String key, Set<String> expected) {
        List<String> actual = new ArrayList<>();
        manifest.required(key).forEach(n -> actual.add(n.asText()));
        assertThat(actual).as("Manifest section %s must match discovered tests", key)
                .doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    private static boolean runnable(Class<?> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers()) || type.isAnnotation()) return false;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass())
            if (Arrays.stream(current.getDeclaredMethods()).anyMatch(m -> AnnotationSupport.isAnnotated(m, Testable.class))) return true;
        return Arrays.stream(type.getDeclaredClasses()).anyMatch(ContextTestManifestTest::runnable);
    }

    private static boolean context(Class<?> type) {
        return AnnotationSupport.isAnnotated(type, BootstrapWith.class)
                || AnnotationSupport.isAnnotated(type, ContextConfiguration.class)
                || Arrays.stream(type.getDeclaredClasses()).filter(ContextTestManifestTest::runnable)
                .anyMatch(ContextTestManifestTest::context);
    }

    private static boolean tagged(Class<?> type, String tag) {
        return AnnotationSupport.findRepeatableAnnotations(type, Tag.class).stream().anyMatch(t -> t.value().equals(tag))
                || Arrays.stream(type.getDeclaredClasses()).filter(ContextTestManifestTest::runnable).anyMatch(t -> tagged(t, tag));
    }
}
