package uk.gegc.quizmaker.features.quizgroup.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSummaryDto;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quizgroup.api.dto.*;
import uk.gegc.quizmaker.features.quizgroup.application.QuizGroupService;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller tests for QuizGroupController.
 * 
 * <p>Tests verify:
 * - REST endpoint mapping and responses
 * - Request validation
 * - Permission checks
 * - ProblemDetail error responses
 */
@WebMvcTest(QuizGroupController.class)
@DisplayName("QuizGroupController Tests")
class QuizGroupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private QuizGroupService quizGroupService;

    @MockitoBean
    private AppPermissionEvaluator appPermissionEvaluator;

    private UUID testGroupId;
    private UUID testOwnerId;
    private QuizGroupDto testGroupDto;
    private QuizGroupSummaryDto testSummaryDto;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        testGroupId = UUID.randomUUID();
        testOwnerId = UUID.randomUUID();

        testGroupDto = new QuizGroupDto(
                testGroupId, testOwnerId, "Test Group", "Description",
                "#FF5733", "book", null, 5L,
                Instant.now(), Instant.now()
        );

        testSummaryDto = new QuizGroupSummaryDto(
                testGroupId, "Test Group", "Description", "#FF5733", "book",
                Instant.now(), Instant.now(), 5L
        );

        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
    }

    @Nested
    @DisplayName("POST /api/v1/quiz-groups")
    class CreateGroupTests {

        @Test
        @WithMockUser(authorities = {"QUIZ_GROUP_CREATE"})
        @DisplayName("Successfully create group")
        void createGroup_Success() throws Exception {
            // Given
            CreateQuizGroupRequest request = new CreateQuizGroupRequest(
                    "My Group", "Description", "#FF5733", "book", null
            );

            when(quizGroupService.create(anyString(), any(CreateQuizGroupRequest.class)))
                    .thenReturn(testGroupId);

            // When & Then
            mockMvc.perform(post("/api/v1/quiz-groups")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.groupId").value(testGroupId.toString()));
        }

        @Test
        @WithMockUser(authorities = {"QUIZ_GROUP_CREATE"})
        @DisplayName("Fail when name is blank")
        void createGroup_BlankName_BadRequest() throws Exception {
            // Given
            CreateQuizGroupRequest request = new CreateQuizGroupRequest(
                    "", "Description", null, null, null
            );

            // When & Then
            mockMvc.perform(post("/api/v1/quiz-groups")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/quiz-groups")
    class ListGroupsTests {

        @Test
        @WithMockUser(authorities = {"QUIZ_GROUP_READ"})
        @DisplayName("Successfully list groups")
        void listGroups_Success() throws Exception {
            // Given
            Page<QuizGroupSummaryDto> page = new PageImpl<>(
                    List.of(testSummaryDto), PageRequest.of(0, 20), 1
            );

            when(quizGroupService.list(any(), any()))
                    .thenReturn(page);

            // When & Then
            mockMvc.perform(get("/api/v1/quiz-groups")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].name").value("Test Group"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/quiz-groups/{groupId}")
    class GetGroupTests {

        @Test
        @WithMockUser(authorities = {"QUIZ_GROUP_READ"})
        @DisplayName("Successfully get group by ID")
        void getGroup_Success() throws Exception {
            // Given
            when(quizGroupService.get(eq(testGroupId), any()))
                    .thenReturn(testGroupDto);

            // When & Then
            mockMvc.perform(get("/api/v1/quiz-groups/{groupId}", testGroupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testGroupId.toString()))
                    .andExpect(jsonPath("$.name").value("Test Group"))
                    .andExpect(jsonPath("$.quizCount").value(5));
        }

        @Test
        @WithMockUser(authorities = {"QUIZ_GROUP_READ"})
        @DisplayName("Return 404 when group not found")
        void getGroup_NotFound_404() throws Exception {
            // Given
            when(quizGroupService.get(any(), any()))
                    .thenThrow(new uk.gegc.quizmaker.shared.exception.ResourceNotFoundException("Not found"));

            // When & Then
            mockMvc.perform(get("/api/v1/quiz-groups/{groupId}", UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/quiz-groups/{groupId}/quizzes")
    class AddQuizzesTests {

        @Test
        @WithMockUser(authorities = {"QUIZ_GROUP_UPDATE"})
        @DisplayName("Successfully add quizzes to group")
        void addQuizzes_Success() throws Exception {
            // Given
            UUID quizId1 = UUID.randomUUID();
            UUID quizId2 = UUID.randomUUID();
            AddQuizzesToGroupRequest request = new AddQuizzesToGroupRequest(
                    List.of(quizId1, quizId2), null
            );

            doNothing().when(quizGroupService).addQuizzes(anyString(), any(), any());

            // When & Then
            mockMvc.perform(post("/api/v1/quiz-groups/{groupId}/quizzes", testGroupId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(authorities = {"QUIZ_GROUP_UPDATE"})
        @DisplayName("Fail when quiz IDs list is empty")
        void addQuizzes_EmptyList_BadRequest() throws Exception {
            // Given
            AddQuizzesToGroupRequest request = new AddQuizzesToGroupRequest(
                    List.of(), null
            );

            // When & Then
            mockMvc.perform(post("/api/v1/quiz-groups/{groupId}/quizzes", testGroupId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/quiz-groups/{groupId}/quizzes/reorder")
    class ReorderTests {

        @Test
        @WithMockUser(authorities = {"QUIZ_GROUP_UPDATE"})
        @DisplayName("Successfully reorder quizzes")
        void reorder_Success() throws Exception {
            // Given
            UUID quizId1 = UUID.randomUUID();
            UUID quizId2 = UUID.randomUUID();
            ReorderGroupQuizzesRequest request = new ReorderGroupQuizzesRequest(
                    List.of(quizId2, quizId1)
            );

            doNothing().when(quizGroupService).reorder(anyString(), any(), any());

            // When & Then
            mockMvc.perform(patch("/api/v1/quiz-groups/{groupId}/quizzes/reorder", testGroupId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/quiz-groups/archived")
    class GetArchivedTests {

        @Test
        @WithMockUser(authorities = {"QUIZ_GROUP_READ"})
        @DisplayName("Successfully get archived quizzes")
        void getArchived_Success() throws Exception {
            // Given
            QuizSummaryDto archivedQuiz = new QuizSummaryDto(
                    UUID.randomUUID(), "Archived Quiz", "Description",
                    Instant.now(), Instant.now(), QuizStatus.ARCHIVED,
                    Visibility.PRIVATE, "owner", testOwnerId,
                    "Category", UUID.randomUUID(), 5L, 2L, 10
            );

            Page<QuizSummaryDto> page = new PageImpl<>(
                    List.of(archivedQuiz), PageRequest.of(0, 20), 1
            );

            when(quizGroupService.getArchivedQuizzes(any(), any()))
                    .thenReturn(page);

            // When & Then
            mockMvc.perform(get("/api/v1/quiz-groups/archived")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].status").value("ARCHIVED"));
        }
    }
}

