import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Utility to generate quiz-import-template.xlsx file
 * Run with: javac -cp "target/classes:$(mvn dependency:build-classpath -q -DincludeScope=test | tail -1)" GenerateQuizImportTemplate.java && java -cp ".:target/classes:$(mvn dependency:build-classpath -q -DincludeScope=test | tail -1)" GenerateQuizImportTemplate
 * Or use Maven to compile and run
 */
public class GenerateQuizImportTemplate {
    
    public static void main(String[] args) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        
        // Create Quizzes sheet
        createQuizzesSheet(workbook);
        
        // Create question type sheets
        createMcqSingleSheet(workbook);
        createMcqMultiSheet(workbook);
        createTrueFalseSheet(workbook);
        createOpenSheet(workbook);
        createFillGapSheet(workbook);
        createOrderingSheet(workbook);
        createComplianceSheet(workbook);
        
        // Write to file
        try (FileOutputStream outputStream = new FileOutputStream("quiz-import-template.xlsx")) {
            workbook.write(outputStream);
        }
        
        workbook.close();
        System.out.println("Created quiz-import-template.xlsx");
    }
    
    private static void createQuizzesSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Quizzes");
        Row header = sheet.createRow(0);
        
        String[] headers = {
            "Quiz ID", "Title", "Description", "Visibility", "Difficulty",
            "Estimated Time (min)", "Tags", "Category", "Creator ID", "Created At", "Updated At"
        };
        
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        
        // Example quiz row
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue(""); // Quiz ID (empty for new quiz)
        row.createCell(1).setCellValue("Sample Quiz - Multiple Question Types");
        row.createCell(2).setCellValue("This is a comprehensive example quiz demonstrating all supported question types.");
        row.createCell(3).setCellValue("PRIVATE");
        row.createCell(4).setCellValue("MEDIUM");
        row.createCell(5).setCellValue(15);
        row.createCell(6).setCellValue("example,template,education");
        row.createCell(7).setCellValue("General Knowledge");
        row.createCell(8).setCellValue(""); // Creator ID
        row.createCell(9).setCellValue(""); // Created At
        row.createCell(10).setCellValue(""); // Updated At
    }
    
    private static void createMcqSingleSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("MCQ_SINGLE");
        Row header = sheet.createRow(0);
        
        int col = 0;
        header.createCell(col++).setCellValue("Quiz ID");
        header.createCell(col++).setCellValue("Question Text");
        header.createCell(col++).setCellValue("Question ID");
        header.createCell(col++).setCellValue("Difficulty");
        header.createCell(col++).setCellValue("Hint");
        header.createCell(col++).setCellValue("Explanation");
        header.createCell(col++).setCellValue("Attachment URL");
        
        // Option columns
        for (int i = 1; i <= 4; i++) {
            header.createCell(col++).setCellValue("Option " + i);
            header.createCell(col++).setCellValue("Option " + i + " Correct");
        }
        
        // Example question row
        Row row = sheet.createRow(1);
        col = 0;
        row.createCell(col++).setCellValue(""); // Quiz ID (must match Quizzes sheet)
        row.createCell(col++).setCellValue("What is the capital of France?");
        row.createCell(col++).setCellValue(""); // Question ID
        row.createCell(col++).setCellValue("EASY");
        row.createCell(col++).setCellValue("It's a city known for the Eiffel Tower.");
        row.createCell(col++).setCellValue("Paris is the capital and largest city of France.");
        row.createCell(col++).setCellValue(""); // Attachment URL
        
        // Options
        row.createCell(col++).setCellValue("London");
        row.createCell(col++).setCellValue(false);
        row.createCell(col++).setCellValue("Paris");
        row.createCell(col++).setCellValue(true);
        row.createCell(col++).setCellValue("Berlin");
        row.createCell(col++).setCellValue(false);
        row.createCell(col++).setCellValue("Madrid");
        row.createCell(col++).setCellValue(false);
    }
    
    private static void createMcqMultiSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("MCQ_MULTI");
        Row header = sheet.createRow(0);
        
        int col = 0;
        header.createCell(col++).setCellValue("Quiz ID");
        header.createCell(col++).setCellValue("Question Text");
        header.createCell(col++).setCellValue("Question ID");
        header.createCell(col++).setCellValue("Difficulty");
        header.createCell(col++).setCellValue("Hint");
        header.createCell(col++).setCellValue("Explanation");
        header.createCell(col++).setCellValue("Attachment URL");
        
        // Option columns (up to 6)
        for (int i = 1; i <= 6; i++) {
            header.createCell(col++).setCellValue("Option " + i);
            header.createCell(col++).setCellValue("Option " + i + " Correct");
        }
        
        // Example question row
        Row row = sheet.createRow(1);
        col = 0;
        row.createCell(col++).setCellValue(""); // Quiz ID
        row.createCell(col++).setCellValue("Which are programming languages? (Select all that apply)");
        row.createCell(col++).setCellValue(""); // Question ID
        row.createCell(col++).setCellValue("MEDIUM");
        row.createCell(col++).setCellValue("HTML and CSS are markup/styling languages.");
        row.createCell(col++).setCellValue("Java, Python, and JavaScript are programming languages.");
        row.createCell(col++).setCellValue(""); // Attachment URL
        
        // Options (multiple correct)
        row.createCell(col++).setCellValue("Java");
        row.createCell(col++).setCellValue(true);
        row.createCell(col++).setCellValue("Python");
        row.createCell(col++).setCellValue(true);
        row.createCell(col++).setCellValue("HTML");
        row.createCell(col++).setCellValue(false);
        row.createCell(col++).setCellValue("CSS");
        row.createCell(col++).setCellValue(false);
        row.createCell(col++).setCellValue("JavaScript");
        row.createCell(col++).setCellValue(true);
        row.createCell(col++).setCellValue(""); // Option 6
        row.createCell(col++).setCellValue(false);
    }
    
    private static void createTrueFalseSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("TRUE_FALSE");
        Row header = sheet.createRow(0);
        
        int col = 0;
        header.createCell(col++).setCellValue("Quiz ID");
        header.createCell(col++).setCellValue("Question Text");
        header.createCell(col++).setCellValue("Question ID");
        header.createCell(col++).setCellValue("Difficulty");
        header.createCell(col++).setCellValue("Hint");
        header.createCell(col++).setCellValue("Explanation");
        header.createCell(col++).setCellValue("Attachment URL");
        header.createCell(col++).setCellValue("Correct Answer");
        
        // Example question row
        Row row = sheet.createRow(1);
        col = 0;
        row.createCell(col++).setCellValue(""); // Quiz ID
        row.createCell(col++).setCellValue("The Earth is the third planet from the Sun.");
        row.createCell(col++).setCellValue(""); // Question ID
        row.createCell(col++).setCellValue("EASY");
        row.createCell(col++).setCellValue("Think about the order of planets.");
        row.createCell(col++).setCellValue("Yes, Earth is the third planet from the Sun.");
        row.createCell(col++).setCellValue(""); // Attachment URL
        row.createCell(col++).setCellValue(true);
    }
    
    private static void createOpenSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("OPEN");
        Row header = sheet.createRow(0);
        
        int col = 0;
        header.createCell(col++).setCellValue("Quiz ID");
        header.createCell(col++).setCellValue("Question Text");
        header.createCell(col++).setCellValue("Question ID");
        header.createCell(col++).setCellValue("Difficulty");
        header.createCell(col++).setCellValue("Hint");
        header.createCell(col++).setCellValue("Explanation");
        header.createCell(col++).setCellValue("Attachment URL");
        header.createCell(col++).setCellValue("Sample Answer");
        
        // Example question row
        Row row = sheet.createRow(1);
        col = 0;
        row.createCell(col++).setCellValue(""); // Quiz ID
        row.createCell(col++).setCellValue("Explain what photosynthesis is in one sentence.");
        row.createCell(col++).setCellValue(""); // Question ID
        row.createCell(col++).setCellValue("MEDIUM");
        row.createCell(col++).setCellValue("Think about what plants do with sunlight.");
        row.createCell(col++).setCellValue("Photosynthesis converts light energy into chemical energy.");
        row.createCell(col++).setCellValue(""); // Attachment URL
        row.createCell(col++).setCellValue("Photosynthesis is the process by which plants convert light energy into chemical energy.");
    }
    
    private static void createFillGapSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("FILL_GAP");
        Row header = sheet.createRow(0);
        
        int col = 0;
        header.createCell(col++).setCellValue("Quiz ID");
        header.createCell(col++).setCellValue("Question Text");
        header.createCell(col++).setCellValue("Question ID");
        header.createCell(col++).setCellValue("Difficulty");
        header.createCell(col++).setCellValue("Hint");
        header.createCell(col++).setCellValue("Explanation");
        header.createCell(col++).setCellValue("Attachment URL");
        
        // Gap columns (up to 10)
        for (int i = 1; i <= 10; i++) {
            header.createCell(col++).setCellValue("Gap " + i + " Answer");
        }
        
        // Example question row
        Row row = sheet.createRow(1);
        col = 0;
        row.createCell(col++).setCellValue(""); // Quiz ID
        row.createCell(col++).setCellValue("Java is a {1} language and Python is a {2} language.");
        row.createCell(col++).setCellValue(""); // Question ID
        row.createCell(col++).setCellValue("MEDIUM");
        row.createCell(col++).setCellValue("Think about how these languages are executed.");
        row.createCell(col++).setCellValue("Java is compiled to bytecode, Python is interpreted.");
        row.createCell(col++).setCellValue(""); // Attachment URL
        
        row.createCell(col++).setCellValue("compiled");
        row.createCell(col++).setCellValue("interpreted");
        // Remaining gaps empty
    }
    
    private static void createOrderingSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("ORDERING");
        Row header = sheet.createRow(0);
        
        int col = 0;
        header.createCell(col++).setCellValue("Quiz ID");
        header.createCell(col++).setCellValue("Question Text");
        header.createCell(col++).setCellValue("Question ID");
        header.createCell(col++).setCellValue("Difficulty");
        header.createCell(col++).setCellValue("Hint");
        header.createCell(col++).setCellValue("Explanation");
        header.createCell(col++).setCellValue("Attachment URL");
        
        // Item columns (up to 10)
        for (int i = 1; i <= 10; i++) {
            header.createCell(col++).setCellValue("Item " + i);
        }
        
        // Example question row
        Row row = sheet.createRow(1);
        col = 0;
        row.createCell(col++).setCellValue(""); // Quiz ID
        row.createCell(col++).setCellValue("Arrange these historical events in chronological order:");
        row.createCell(col++).setCellValue(""); // Question ID
        row.createCell(col++).setCellValue("HARD");
        row.createCell(col++).setCellValue("World War I happened first.");
        row.createCell(col++).setCellValue("The order is: WWI, WWII, Cold War, Fall of Berlin Wall.");
        row.createCell(col++).setCellValue(""); // Attachment URL
        
        row.createCell(col++).setCellValue("World War I");
        row.createCell(col++).setCellValue("World War II");
        row.createCell(col++).setCellValue("Cold War");
        row.createCell(col++).setCellValue("Fall of Berlin Wall");
        // Remaining items empty
    }
    
    private static void createComplianceSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("COMPLIANCE");
        Row header = sheet.createRow(0);
        
        int col = 0;
        header.createCell(col++).setCellValue("Quiz ID");
        header.createCell(col++).setCellValue("Question Text");
        header.createCell(col++).setCellValue("Question ID");
        header.createCell(col++).setCellValue("Difficulty");
        header.createCell(col++).setCellValue("Hint");
        header.createCell(col++).setCellValue("Explanation");
        header.createCell(col++).setCellValue("Attachment URL");
        
        // Statement columns (up to 10)
        for (int i = 1; i <= 10; i++) {
            header.createCell(col++).setCellValue("Statement " + i);
            header.createCell(col++).setCellValue("Statement " + i + " Compliant");
        }
        
        // Example question row
        Row row = sheet.createRow(1);
        col = 0;
        row.createCell(col++).setCellValue(""); // Quiz ID
        row.createCell(col++).setCellValue("Which statements comply with GDPR requirements?");
        row.createCell(col++).setCellValue(""); // Question ID
        row.createCell(col++).setCellValue("HARD");
        row.createCell(col++).setCellValue("GDPR emphasizes user rights and data protection.");
        row.createCell(col++).setCellValue("GDPR requires lawful processing, transparency, and user access rights.");
        row.createCell(col++).setCellValue(""); // Attachment URL
        
        row.createCell(col++).setCellValue("Personal data must be processed lawfully and transparently");
        row.createCell(col++).setCellValue(true);
        row.createCell(col++).setCellValue("Data can be stored indefinitely without user consent");
        row.createCell(col++).setCellValue(false);
        row.createCell(col++).setCellValue("Users have the right to access their personal data");
        row.createCell(col++).setCellValue(true);
        row.createCell(col++).setCellValue("Data can be sold to third parties without notification");
        row.createCell(col++).setCellValue(false);
        // Remaining statements empty
    }
}
