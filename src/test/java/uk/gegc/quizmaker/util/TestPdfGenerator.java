package uk.gegc.quizmaker.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class TestPdfGenerator {

    public static void generateTestPdf(String outputPath) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                // Set font and size
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 18);
                
                // Add title
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 750);
                contentStream.showText("Introduction to Computer Science");
                contentStream.endText();

                // Set font for content
                contentStream.setFont(PDType1Font.HELVETICA, 12);
                
                // Add introduction
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 720);
                contentStream.showText("This document provides an introduction to computer science concepts.");
                contentStream.endText();

                // Chapter 1
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 680);
                contentStream.showText("Chapter 1: Programming Fundamentals");
                contentStream.endText();

                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 660);
                contentStream.showText("Programming is the art of telling a computer what to do through a set of instructions called code. ");
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("Variables are containers that store data. In most programming languages, you need to declare the type ");
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("of data a variable will hold. Common data types include integers, floats, strings, and booleans.");
                contentStream.endText();

                // Chapter 2
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 580);
                contentStream.showText("Chapter 2: Object-Oriented Programming");
                contentStream.endText();

                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 560);
                contentStream.showText("Object-oriented programming (OOP) is a programming paradigm based on the concept of objects. ");
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("A class is a blueprint for creating objects. An object is an instance of a class. ");
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("Inheritance allows a class to inherit properties and methods from another class.");
                contentStream.endText();

                // Chapter 3
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 480);
                contentStream.showText("Chapter 3: Data Structures");
                contentStream.endText();

                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 460);
                contentStream.showText("Data structures are ways of organizing and storing data for efficient access and modification. ");
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("Arrays and lists store collections of items in a specific order. ");
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("Dictionaries and hash maps store key-value pairs for fast lookup.");
                contentStream.endText();

                // Chapter 4
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 380);
                contentStream.showText("Chapter 4: Algorithms");
                contentStream.endText();

                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 360);
                contentStream.showText("Algorithms are step-by-step procedures for solving problems. ");
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("Sorting algorithms arrange elements in a specific order. ");
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("Search algorithms find specific elements in a data structure.");
                contentStream.endText();

                // Chapter 5
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 280);
                contentStream.showText("Chapter 5: Software Development");
                contentStream.endText();

                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 260);
                contentStream.showText("Software development is the process of creating, deploying, and maintaining software. ");
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("Version control systems track changes to source code over time. ");
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("Testing ensures your code works correctly.");
                contentStream.endText();

                // Conclusion
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 180);
                contentStream.showText("Conclusion");
                contentStream.endText();

                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 160);
                contentStream.showText("Computer science is a vast field that continues to evolve. ");
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("The concepts covered in this document provide a foundation for further learning and exploration. ");
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("Practice is key to mastering programming. Write code regularly, solve problems, and never stop learning!");
                contentStream.endText();
            }

            document.save(outputPath);
        }
    }

    public static void generateTestPdfInTestResources() throws IOException {
        Path testResourcesPath = Paths.get("src", "test", "resources", "test-documents");
        Files.createDirectories(testResourcesPath);
        
        String pdfPath = testResourcesPath.resolve("sample-document.pdf").toString();
        generateTestPdf(pdfPath);
    }

    public static void main(String[] args) {
        try {
            generateTestPdfInTestResources();
            System.out.println("Test PDF generated successfully!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
} 