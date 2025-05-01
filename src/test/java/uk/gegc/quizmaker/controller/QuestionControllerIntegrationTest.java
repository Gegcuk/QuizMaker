package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase
@TestPropertySource(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
public class QuestionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    static Stream<Arguments> happyPathPayloads(){
        return Stream.of(
                Arguments.of("MCQ_SINGLE (2 opts, 1 correct)", """
                {
                  "type":"MCQ_SINGLE","difficulty":"EASY","questionText":"Pick one",
                  "content":{"options":[
                      {"text":"A","correct":false},
                      {"text":"B","correct":true}
                    ]}
                }
                """),
                Arguments.of("MCQ_MULTI (3 opts, >=1 correct)", """
                {
                  "type":"MCQ_MULTI","difficulty":"MEDIUM","questionText":"Pick many",
                  "content":{"options":[
                      {"text":"A","correct":true},
                      {"text":"B","correct":false},
                      {"text":"C","correct":true}
                    ]}
                }
                """),
                Arguments.of("TRUE_FALSE (true)", """
                {
                  "type":"TRUE_FALSE","difficulty":"EASY","questionText":"T or F?",
                  "content":{"answer":true}
                }
                """),
                Arguments.of("TRUE_FALSE (false)", """
                {
                  "type":"TRUE_FALSE","difficulty":"EASY","questionText":"T or F?",
                  "content":{"answer":false}
                }
                """),
                Arguments.of("OPEN", """
                {
                  "type":"OPEN","difficulty":"HARD","questionText":"Explain?",
                  "content":{"answer":"Because..."}
                }
                """),
                Arguments.of("FILL_GAP", """
                {
                  "type":"FILL_GAP","difficulty":"MEDIUM","questionText":"Fill:",
                  "content":{
                    "text":"___ is Java","gaps":[
                      {"id":1,"answer":"Java"}
                    ]
                  }
                }
                """),
                Arguments.of("ORDERING", """
                {
                  "type":"ORDERING","difficulty":"HARD","questionText":"Order these",
                  "content":{"items":[
                    {"id":1,"text":"First"},
                    {"id":2,"text":"Second"}
                  ]}
                }
                """),
                Arguments.of("HOTSPOT", """
                {
                  "type":"HOTSPOT","difficulty":"MEDIUM","questionText":"Click",
                  "content":{
                    "imageUrl":"http://img.png",
                    "regions":[{"x":10,"y":20,"width":30,"height":40}]
                  }
                }
                """),
                Arguments.of("COMPLIANCE", """
                {
                  "type":"COMPLIANCE","difficulty":"MEDIUM","questionText":"Agree?",
                  "content":{
                    "statements":[
                      {"text":"Yes","compliant":true}
                    ]
                  }
                }
                """)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("happyPathPayloads")
    void createQuestion_HappyPath_thanReturns201(String name, String jsonPayload) throws Exception{
        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.questionId").exists());
    }

    @Test
    void createQuestionUnknownQuizId_thanReturns404() throws Exception{
        String badQuizId = UUID.randomUUID().toString();
        String payload = """
                {
                    "type":"TRUE_FALSE",
                    "difficulty":"EASY",
                    "questionText":"Q?",
                    "content":{"answer":true},
                    "quizIds":["%s"]
                }
                """.formatted(badQuizId);

        mockMvc.perform(post("/api/v1/questions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
                .andExpect(status().isNotFound());
    }


}
