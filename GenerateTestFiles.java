import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GenerateTestFiles {
    public static void main(String[] args) {
        try {
            System.out.println("Generating test files...");

            // Generate PDF file
            uk.gegc.quizmaker.util.TestPdfGenerator.generateTestPdfInTestResources();
            System.out.println("✓ Test PDF generated successfully!");

            // Check if text file exists
            Path textPath = Paths.get("src", "test", "resources", "test-documents", "sample-text.txt");
            if (Files.exists(textPath)) {
                System.out.println("✓ Test text file already exists!");
            } else {
                System.out.println("✗ Test text file not found!");
            }

            System.out.println("Test files are ready for testing!");

        } catch (Exception e) {
            System.err.println("Error generating test files: " + e.getMessage());
            e.printStackTrace();
        }
    }
} 