package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import uk.gegc.quizmaker.dto.category.CreateCategoryRequest;
import uk.gegc.quizmaker.dto.category.UpdateCategoryRequest;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.repository.category.CategoryRepository;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(
        properties = {
                "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "spring.jpa.hibernate.ddl-auto=create"
        }
)
public class CategoryControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    CategoryRepository categoryRepository;

    @BeforeEach
    void setUp(){
        categoryRepository.deleteAll();
    }

    @Test
    void listInitiallyEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/categories")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    void fullCategoryCrudFlow() throws Exception{

        CreateCategoryRequest createCategoryRequest = new CreateCategoryRequest("TestCategory", "Desc");
        String createJson = objectMapper.writeValueAsString(createCategoryRequest);

        //Create
        String response = mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").exists())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(response).get("categoryId").asText());

        //Get
        mockMvc.perform(get("/api/v1/categories")
                        .param("page","0").param("size","20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].name", is("TestCategory")));

        //Get by id
        mockMvc.perform(get("/api/v1/categories/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("TestCategory")))
                .andExpect(jsonPath("$.description", is("Desc")));

        //Update by id
        UpdateCategoryRequest updateCategoryRequest = new UpdateCategoryRequest("UpdatedCategory", "New description");
        String updatedCategoryJson = objectMapper.writeValueAsString(updateCategoryRequest);

        mockMvc.perform(patch("/api/v1/categories/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatedCategoryJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("UpdatedCategory")))
                .andExpect(jsonPath("$.description", is("New description")));

        //Delete
        mockMvc.perform(delete("/api/v1/categories/{id}", id))
                .andExpect(status().isNoContent());

        //404 on get
        mockMvc.perform(get("/api/v1/categories/{id}", id))
                .andExpect(status().isNotFound());
    }


    // -----------------------
    // CREATE: validation errors
    // -----------------------

    @Test
    void create_BlankName_ShouldReturn400() throws Exception {
        CreateCategoryRequest req = new CreateCategoryRequest("", "desc");
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    void create_TooShortName_ShouldReturn400() throws Exception {
        CreateCategoryRequest req = new CreateCategoryRequest("ab", "desc");
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    void create_TooLongName_ShouldReturn400() throws Exception {
        String longName = "x".repeat(101);
        CreateCategoryRequest req = new CreateCategoryRequest(longName, "desc");
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    void create_TooLongDescription_ShouldReturn400() throws Exception {
        String longDesc = "d".repeat(1001);
        CreateCategoryRequest req = new CreateCategoryRequest("ValidName", longDesc);
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("description:"))));
    }

    // -----------------------
    // PATCH: validation errors
    // -----------------------

    @Test
    void update_BlankName_ShouldReturn400() throws Exception {
        Category c = new Category(); c.setName("OK"); categoryRepository.save(c);
        UpdateCategoryRequest req = new UpdateCategoryRequest("", "d");
        mockMvc.perform(patch("/api/v1/categories/{id}", c.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    void update_TooShortName_ShouldReturn400() throws Exception {
        Category c = new Category(); c.setName("OK"); categoryRepository.save(c);
        UpdateCategoryRequest req = new UpdateCategoryRequest("ab", "d");
        mockMvc.perform(patch("/api/v1/categories/{id}", c.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    void update_TooLongName_ShouldReturn400() throws Exception {
        Category c = new Category(); c.setName("OK"); categoryRepository.save(c);
        String longName = "x".repeat(101);
        UpdateCategoryRequest req = new UpdateCategoryRequest(longName, "desc");
        mockMvc.perform(patch("/api/v1/categories/{id}", c.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    void update_TooLongDescription_ShouldReturn400() throws Exception {
        Category c = new Category(); c.setName("OK"); categoryRepository.save(c);
        String longDesc = "d".repeat(1001);
        UpdateCategoryRequest req = new UpdateCategoryRequest("Valid", longDesc);
        mockMvc.perform(patch("/api/v1/categories/{id}", c.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("description:"))));
    }

    // -----------------------
    // NOT-FOUND scenarios
    // -----------------------

    @Test
    void update_NotFound_ShouldReturn404() throws Exception {
        UpdateCategoryRequest req = new UpdateCategoryRequest("TESTCATEGORY", "TESTDESCRIPTION");
        mockMvc.perform(patch("/api/v1/categories/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_NotFound_ShouldReturn404() throws Exception {
        mockMvc.perform(delete("/api/v1/categories/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // -----------------------
    // CONFLICT on duplicate name
    // -----------------------

    @Test
    void create_DuplicateName_ShouldReturn409() throws Exception {
        Category c = new Category(); c.setName("DUP"); categoryRepository.save(c);
        CreateCategoryRequest req = new CreateCategoryRequest("DUP", "desc");
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    // -----------------------
    // PAGINATION & SORTING
    // -----------------------

    @Test
    void paginationAndSorting() throws Exception {
        for (String name : new String[]{"AAAA","BBBB","CCCC"}) {
            CreateCategoryRequest r = new CreateCategoryRequest(name, "");
            mockMvc.perform(post("/api/v1/categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(r)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/categories")
                        .param("page","0")
                        .param("size","2")
                        .param("sort","name,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.content[0].name", is("CCCC")))
                .andExpect(jsonPath("$.content[1].name", is("BBBB")));
    }



}
