package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.dto.tag.CreateTagRequest;
import uk.gegc.quizmaker.dto.tag.UpdateTagRequest;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.tag.TagRepository;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@DirtiesContext(classMode = AFTER_CLASS)
@TestPropertySource(
        properties = {
                "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "spring.jpa.hibernate.ddl-auto=create"
        }
)
@DisplayName("TagController Integration Tests")
public class TagControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TagRepository tagRepository;
    @Autowired QuizRepository quizRepository;

    @BeforeEach
    void setUp() {
        quizRepository.deleteAll();
        tagRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /tags → empty page (public)")
    void listInitiallyEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/tags")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    @DisplayName("POST /tags forbidden for USER role")
    @WithMockUser(roles = "USER")
    void create_forbiddenForUser() throws Exception {
        CreateTagRequest req = new CreateTagRequest("TestUser", "Description");
        mockMvc.perform(post("/api/v1/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Full CRUD flow succeeds for ADMIN role")
    @WithMockUser(roles = "ADMIN")
    void fullTagCrudFlow() throws Exception {
        CreateTagRequest createTagRequest = new CreateTagRequest("TestTag", "Desc");
        String createJson = objectMapper.writeValueAsString(createTagRequest);

        // Create
        String response = mockMvc.perform(post("/api/v1/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tagId").exists())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(response).get("tagId").asText());

        // List
        mockMvc.perform(get("/api/v1/tags")
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].name", is("TestTag")));

        // Get by ID
        mockMvc.perform(get("/api/v1/tags/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("TestTag")))
                .andExpect(jsonPath("$.description", is("Desc")));

        // Update
        UpdateTagRequest updateTagRequest = new UpdateTagRequest("NewTag", "NewDesc");
        String updateTagJson = objectMapper.writeValueAsString(updateTagRequest);
        mockMvc.perform(patch("/api/v1/tags/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateTagJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("NewTag")))
                .andExpect(jsonPath("$.description", is("NewDesc")));

        // Delete
        mockMvc.perform(delete("/api/v1/tags/{id}", id))
                .andExpect(status().isNoContent());

        // Not Found after delete
        mockMvc.perform(get("/api/v1/tags/{id}", id))
                .andExpect(status().isNotFound());
    }

    // -----------------------
    // CREATE: validation errors
    // -----------------------

    @Test
    @DisplayName("POST /tags with blank name returns 400")
    @WithMockUser(roles = "ADMIN")
    void create_BlankName_ShouldReturn400() throws Exception {
        CreateTagRequest request = new CreateTagRequest("", "description");
        mockMvc.perform(post("/api/v1/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    @DisplayName("POST /tags with too short name returns 400")
    @WithMockUser(roles = "ADMIN")
    void create_TooShortName_ShouldReturn400() throws Exception {
        CreateTagRequest request = new CreateTagRequest("ab", "description");
        mockMvc.perform(post("/api/v1/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    @DisplayName("POST /tags with too long name returns 400")
    @WithMockUser(roles = "ADMIN")
    void create_TooLongName_ShouldReturn400() throws Exception {
        String longName = "x".repeat(101);
        CreateTagRequest request = new CreateTagRequest(longName, "description");
        mockMvc.perform(post("/api/v1/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    @DisplayName("POST /tags with too long description returns 400")
    @WithMockUser(roles = "ADMIN")
    void create_TooLongDescription_ShouldReturn400() throws Exception {
        String longDesc = "d".repeat(1001);
        CreateTagRequest req = new CreateTagRequest("ValidName", longDesc);
        mockMvc.perform(post("/api/v1/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("description:"))));
    }

    // -----------------------
    // PATCH: validation errors
    // -----------------------

    @Test
    @DisplayName("PATCH /tags blank name returns 400")
    @WithMockUser(roles = "ADMIN")
    void update_BlankName_ShouldReturn400() throws Exception {
        Tag tag = new Tag();
        tag.setName("OK"); tagRepository.save(tag);
        UpdateTagRequest req = new UpdateTagRequest("", "d");
        mockMvc.perform(patch("/api/v1/tags/{id}", tag.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    @DisplayName("PATCH /tags too short name returns 400")
    @WithMockUser(roles = "ADMIN")
    void update_TooShortName_ShouldReturn400() throws Exception {
        Tag tag = new Tag();
        tag.setName("OK"); tagRepository.save(tag);
        UpdateTagRequest req = new UpdateTagRequest("ab", "d");
        mockMvc.perform(patch("/api/v1/tags/{id}", tag.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    @DisplayName("PATCH /tags too long name returns 400")
    @WithMockUser(roles = "ADMIN")
    void update_TooLongName_ShouldReturn400() throws Exception {
        Tag tag = new Tag();
        tag.setName("OK"); tagRepository.save(tag);
        String longName = "x".repeat(101);
        UpdateTagRequest req = new UpdateTagRequest(longName, "desc");
        mockMvc.perform(patch("/api/v1/tags/{id}", tag.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    @DisplayName("PATCH /tags too long description returns 400")
    @WithMockUser(roles = "ADMIN")
    void update_TooLongDescription_ShouldReturn400() throws Exception {
        Tag tag = new Tag();
        tag.setName("OK"); tagRepository.save(tag);
        String longDesc = "d".repeat(1001);
        UpdateTagRequest req = new UpdateTagRequest("Valid", longDesc);
        mockMvc.perform(patch("/api/v1/tags/{id}", tag.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("description:"))));
    }

    // -----------------------
    // NOT-FOUND scenarios
    // -----------------------

    @Test
    @DisplayName("PATCH /tags non-existent ID returns 404")
    @WithMockUser(roles = "ADMIN")
    void update_NotFound_ShouldReturn404() throws Exception {
        UpdateTagRequest req = new UpdateTagRequest("TESTTAG", "TESTDESCRIPTION");
        mockMvc.perform(patch("/api/v1/tags/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /tags non-existent ID returns 404")
    @WithMockUser(roles = "ADMIN")
    void delete_NotFound_ShouldReturn404() throws Exception {
        mockMvc.perform(delete("/api/v1/tags/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // -----------------------
    // CONFLICT on duplicate name
    // -----------------------

    @Test
    @DisplayName("POST /tags duplicate name returns 409")
    @WithMockUser(roles = "ADMIN")
    void create_DuplicateName_ShouldReturn409() throws Exception {
        Tag tag = new Tag();
        tag.setName("DUP"); tagRepository.save(tag);
        CreateTagRequest req = new CreateTagRequest("DUP", "desc");
        mockMvc.perform(post("/api/v1/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    // -----------------------
    // PAGINATION & SORTING
    // -----------------------

    @Test
    @DisplayName("GET /tags supports pagination and sorting")
    @WithMockUser(roles = "ADMIN")
    void paginationAndSorting() throws Exception {
        for (String name : new String[]{"AAAA", "BBBB", "CCCC"}) {
            CreateTagRequest r = new CreateTagRequest(name, "");
            mockMvc.perform(post("/api/v1/tags")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(r)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/tags")
                        .param("page", "0")
                        .param("size", "2")
                        .param("sort", "name,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.content[0].name", is("CCCC")))
                .andExpect(jsonPath("$.content[1].name", is("BBBB")));
    }
}