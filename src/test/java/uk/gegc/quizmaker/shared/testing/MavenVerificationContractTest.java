package uk.gegc.quizmaker.shared.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MavenVerificationContractTest {

    @Test
    @DisplayName("default verification excludes live providers and bounds parallel workers")
    void defaultVerification_isOfflineAndBounded() throws Exception {
        Document pom = readPom();

        Element enforcer = findPlugin(pom, "maven-enforcer-plugin").orElseThrow();
        assertThat(textOfDescendant(enforcer, "version")).isEqualTo("3.5.0");
        assertThat(textOfDescendant(enforcer, "requireJavaVersion", "version")).isEqualTo("[17,18)");

        Element parallel = findExecution(pom, "tests-parallel").orElseThrow();
        assertThat(textOfDescendant(parallel, "excludedGroups")).isEqualTo("db-serial,real-provider");
        String parallelConfiguration = textOfDescendant(parallel, "configurationParameters");
        assertThat(parallelConfiguration)
                .contains("junit.jupiter.execution.parallel.enabled=true")
                .contains("junit.jupiter.execution.parallel.config.strategy=fixed")
                .contains("junit.jupiter.execution.parallel.config.fixed.parallelism=4")
                .contains("junit.jupiter.execution.parallel.config.fixed.max-pool-size=4");

        Element serial = findExecution(pom, "tests-db-serial").orElseThrow();
        assertThat(textOfDescendant(serial, "groups")).isEqualTo("db-serial");
        assertThat(textOfDescendant(serial, "excludedGroups")).isEqualTo("${real-provider-excluded-groups}");
        assertThat(textOfDescendant(serial, "quizmaker.tests.live-provider"))
                .isEqualTo("${live-provider-tests.enabled}");
        assertThat(projectProperty(pom, "real-provider-excluded-groups")).isEqualTo("real-provider");
        assertThat(projectProperty(pom, "live-provider-tests.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("live provider profile explicitly opts into provider calls")
    void liveProviderProfile_enablesOnlyTheDedicatedSerialExecution() throws Exception {
        Document pom = readPom();
        Element profile = findElementWithDirectChildText(pom.getDocumentElement(), "profile", "id", "live-provider-tests")
                .orElseThrow();

        assertThat(directChildText((Element) profile.getElementsByTagName("properties").item(0),
                "real-provider-excluded-groups")).contains("");
        assertThat(directChildText((Element) profile.getElementsByTagName("properties").item(0),
                "live-provider-tests.enabled")).contains("true");
    }

    private Document readPom() throws Exception {
        Path projectDirectory = Path.of(System.getProperty("maven.multiModuleProjectDirectory", System.getProperty("user.dir")));
        Path pomPath = projectDirectory.resolve("pom.xml");
        assertThat(Files.isRegularFile(pomPath)).as("Maven project descriptor").isTrue();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory.newDocumentBuilder().parse(pomPath.toFile());
    }

    private Optional<Element> findPlugin(Document document, String artifactId) {
        return findElementWithDirectChildText(document.getDocumentElement(), "plugin", "artifactId", artifactId);
    }

    private Optional<Element> findExecution(Document document, String executionId) {
        return findElementWithDirectChildText(document.getDocumentElement(), "execution", "id", executionId);
    }

    private String projectProperty(Document document, String propertyName) {
        NodeList properties = document.getDocumentElement().getElementsByTagName("properties");
        for (int index = 0; index < properties.getLength(); index++) {
            Element candidate = (Element) properties.item(index);
            if (candidate.getParentNode() == document.getDocumentElement()) {
                return directChildText(candidate, propertyName).orElseThrow();
            }
        }
        throw new AssertionError("Project properties element not found");
    }

    private Optional<Element> findElementWithDirectChildText(
            Element root,
            String elementName,
            String childName,
            String expectedText
    ) {
        NodeList candidates = root.getElementsByTagName(elementName);
        for (int index = 0; index < candidates.getLength(); index++) {
            Element candidate = (Element) candidates.item(index);
            if (directChildText(candidate, childName).filter(expectedText::equals).isPresent()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private String textOfDescendant(Element root, String elementName) {
        return textOfDescendant(root, null, elementName);
    }

    private String textOfDescendant(Element root, String parentName, String elementName) {
        NodeList parents = parentName == null ? root.getElementsByTagName(elementName) : root.getElementsByTagName(parentName);
        if (parentName == null) {
            assertThat(parents.getLength()).as("%s element", elementName).isGreaterThan(0);
            return parents.item(0).getTextContent().trim();
        }

        assertThat(parents.getLength()).as("%s element", parentName).isGreaterThan(0);
        return directChildText((Element) parents.item(0), elementName).orElseThrow();
    }

    private Optional<String> directChildText(Element root, String childName) {
        NodeList children = root.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && childName.equals(element.getTagName())) {
                return Optional.of(element.getTextContent().trim());
            }
        }
        return Optional.empty();
    }
}
