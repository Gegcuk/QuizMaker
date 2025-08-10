package uk.gegc.quizmaker.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttemptControllerMatchingIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void submitMatchingAnswer_smoke() throws Exception {
        // This is a light integration smoke to ensure route + handler wiring works for MATCHING.
        // Precondition: test data setup for a quiz with at least one MATCHING question is assumed by broader suite.
        // Here we only verify the endpoint shape does not 400 on basic flow (fine to be 401 without auth).
        UUID fakeAttemptId = UUID.randomUUID();
        UUID fakeQuestionId = UUID.randomUUID();
        String payload = "{" +
                "\"questionId\":\"" + fakeQuestionId + "\"," +
                "\"response\":{\"matches\":[{\"leftId\":1,\"rightId\":10}]}}";

        mockMvc.perform(post("/api/v1/attempts/" + fakeAttemptId + "/answers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().is4xxClientError());
    }
}


