package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.dto.category.CreateCategoryRequest;
import uk.gegc.quizmaker.dto.category.UpdateCategoryRequest;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = AFTER_CLASS)
@TestPropertySource(
        properties = {
                "spring.jpa.hibernate.ddl-auto=create"
        }
)
@DisplayName("Integration Tests CategoryController")
public class CategoryControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    QuizRepository quizRepository;

    @BeforeEach
    void setUp() {
        quizRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /api/v1/categories → returns empty page when no categories present")
    void listInitiallyEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/categories")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Full CRUD flow as ADMIN → succeeds")
    void fullCategoryCrudFlow() throws Exception {

        CreateCategoryRequest createCategoryRequest =
                new CreateCategoryRequest("TestCategory", "Desc");
        String createJson = objectMapper.writeValueAsString(createCategoryRequest);

        // Create
        String response = mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").exists())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(
                objectMapper.readTree(response).get("categoryId").asText());

        // List
        mockMvc.perform(get("/api/v1/categories")
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].name", is("TestCategory")));

        // Get by ID
        mockMvc.perform(get("/api/v1/categories/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("TestCategory")))
                .andExpect(jsonPath("$.description", is("Desc")));

        // Update
        UpdateCategoryRequest updateCategoryRequest =
                new UpdateCategoryRequest("UpdatedCategory", "New description");
        String updatedJson = objectMapper.writeValueAsString(updateCategoryRequest);

        mockMvc.perform(patch("/api/v1/categories/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("UpdatedCategory")))
                .andExpect(jsonPath("$.description", is("New description")));

        // Delete
        mockMvc.perform(delete("/api/v1/categories/{id}", id))
                .andExpect(status().isNoContent());

        // Not Found after delete
        mockMvc.perform(get("/api/v1/categories/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/categories → with data -> returns non-empty page")
    void listCategories_withData_returnsPage() throws Exception {
        Category c1 = new Category();
        c1.setName("Cat A");
        c1.setDescription("Desc A");
        categoryRepository.save(c1);
        Category c2 = new Category();
        c2.setName("Cat B");
        c2.setDescription("Desc B");
        categoryRepository.save(c2);

        mockMvc.perform(get("/api/v1/categories")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.content[*].name", containsInAnyOrder("Cat A", "Cat B")));
    }

    @Test
    @DisplayName("GET /api/v1/categories/{id} existing ID -> returns 200 OK with CategoryDto")
    void getCategory_existingId_returns200() throws Exception {
        Category c = new Category();
        c.setName("TestCat");
        c.setDescription("TestDesc");
        categoryRepository.save(c);
        UUID id = c.getId();

        mockMvc.perform(get("/api/v1/categories/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.name", is("TestCat")))
                .andExpect(jsonPath("$.description", is("TestDesc")));
    }

    @Test
    @DisplayName("GET /api/v1/categories/{id} non-existent ID -> returns 404 NOT_FOUND")
    void getCategory_nonexistentId_returns404() throws Exception {
        UUID missing = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/categories/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString("Category " + missing + " not found"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/categories with blank name → returns 400 BAD_REQUEST")
    void create_BlankName_ShouldReturn400() throws Exception {
        CreateCategoryRequest req = new CreateCategoryRequest("", "desc");
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/categories with too short name → returns 400 BAD_REQUEST")
    void create_TooShortName_ShouldReturn400() throws Exception {
        CreateCategoryRequest req = new CreateCategoryRequest("ab", "desc");
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/categories with too long name → returns 400 BAD_REQUEST")
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
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/categories with too long description → returns 400 BAD_REQUEST")
    void create_TooLongDescription_ShouldReturn400() throws Exception {
        String longDesc = "d".repeat(1001);
        CreateCategoryRequest req = new CreateCategoryRequest("ValidName", longDesc);
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("description:"))));
    }

    @Test
    @DisplayName("POST /api/v1/categories without authentication -> returns 403 FORBIDDEN")
    void createCategory_anonymous_returns403() throws Exception {
        CreateCategoryRequest createCategoryRequest = new CreateCategoryRequest("ValidName", "Valid description");
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCategoryRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST /api/v1/categories with USER role -> returns 403 FORBIDDEN")
    void createCategory_userRole_returns403() throws Exception {
        var req = new CreateCategoryRequest("ValidName", "Valid description");
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /api/v1/categories with blank name → returns 400 BAD_REQUEST")
    void update_BlankName_ShouldReturn400() throws Exception {
        Category c = new Category();
        c.setName("OK");
        categoryRepository.save(c);

        UpdateCategoryRequest req = new UpdateCategoryRequest("", "d");
        mockMvc.perform(patch("/api/v1/categories/{id}", c.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /api/v1/categories with too short name → returns 400 BAD_REQUEST")
    void update_TooShortName_ShouldReturn400() throws Exception {
        Category c = new Category();
        c.setName("OK");
        categoryRepository.save(c);

        UpdateCategoryRequest req = new UpdateCategoryRequest("ab", "d");
        mockMvc.perform(patch("/api/v1/categories/{id}", c.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /api/v1/categories with too long name → returns 400 BAD_REQUEST")
    void update_TooLongName_ShouldReturn400() throws Exception {
        Category c = new Category();
        c.setName("OK");
        categoryRepository.save(c);

        String longName = "x".repeat(101);
        UpdateCategoryRequest req = new UpdateCategoryRequest(longName, "desc");
        mockMvc.perform(patch("/api/v1/categories/{id}", c.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("name:"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /api/v1/categories with too long description → returns 400 BAD_REQUEST")
    void update_TooLongDescription_ShouldReturn400() throws Exception {
        Category c = new Category();
        c.setName("OK");
        categoryRepository.save(c);

        String longDesc = "d".repeat(1001);
        UpdateCategoryRequest req = new UpdateCategoryRequest("Valid", longDesc);
        mockMvc.perform(patch("/api/v1/categories/{id}", c.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("description:"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /api/v1/categories/{id} when ID does not exist → returns 404 NOT_FOUND")
    void update_NotFound_ShouldReturn404() throws Exception {
        UpdateCategoryRequest req = new UpdateCategoryRequest("TESTCATEGORY", "TESTDESCRIPTION");
        mockMvc.perform(patch("/api/v1/categories/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /api/v1/categories/{id} when ID does not exist → returns 404 NOT_FOUND")
    void delete_NotFound_ShouldReturn404() throws Exception {
        mockMvc.perform(delete("/api/v1/categories/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /api/v1/categories/{id} anonymous -> returns 403 FORBIDDEN")
    void updateCategory_anonymous_returns403() throws Exception {
        Category c = new Category();
        c.setName("Orig");
        categoryRepository.save(c);
        UUID id = c.getId();
        String body = objectMapper.writeValueAsString(new UpdateCategoryRequest("New", "Desc"));

        mockMvc.perform(patch("/api/v1/categories/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("PATCH /api/v1/categories/{id} with USER role -> returns 403 FORBIDDEN")
    void updateCategory_userRole_returns403() throws Exception {
        Category c = new Category();
        c.setName("Orig");
        categoryRepository.save(c);
        UUID id = c.getId();
        String body = objectMapper.writeValueAsString(new UpdateCategoryRequest("New", "Desc"));

        mockMvc.perform(patch("/api/v1/categories/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/v1/categories/{id} anonymous -> returns 403 FORBIDDEN")
    void deleteCategory_anonymous_returns403() throws Exception {
        Category c = new Category();
        c.setName("ToDelete");
        categoryRepository.save(c);
        UUID id = c.getId();

        mockMvc.perform(delete("/api/v1/categories/{id}", id))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("DELETE /api/v1/categories/{id} with USER role returns 403 FORBIDDEN")
    void deleteCategory_userRole_returns403() throws Exception {
        Category c = new Category();
        c.setName("ToDelete");
        categoryRepository.save(c);
        UUID id = c.getId();

        mockMvc.perform(delete("/api/v1/categories/{id}", id))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/categories with duplicate name → returns 409 CONFLICT")
    void create_DuplicateName_ShouldReturn409() throws Exception {
        Category c = new Category();
        c.setName("DUP");
        categoryRepository.save(c);

        CreateCategoryRequest req = new CreateCategoryRequest("DUP", "desc");
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/categories?pageNumber={pageNumber}&size={size} → returns paginated and sorted categories")
    void paginationAndSorting() throws Exception {
        for (String name : new String[]{"AAAA", "BBBB", "CCCC"}) {
            CreateCategoryRequest r = new CreateCategoryRequest(name, "");
            mockMvc.perform(post("/api/v1/categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(r)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/categories")
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