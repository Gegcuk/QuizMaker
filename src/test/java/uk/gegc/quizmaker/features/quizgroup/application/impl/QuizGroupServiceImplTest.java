package uk.gegc.quizmaker.features.quizgroup.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.quizgroup.domain.repository.projection.QuizGroupSummaryProjection;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSummaryDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quizgroup.api.dto.*;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroup;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroupMembership;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroupMembershipId;
import uk.gegc.quizmaker.features.quizgroup.domain.repository.QuizGroupMembershipRepository;
import uk.gegc.quizmaker.features.quizgroup.domain.repository.QuizGroupRepository;
import uk.gegc.quizmaker.features.quizgroup.infra.mapping.QuizGroupMapper;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AccessPolicy;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for QuizGroupServiceImpl.
 * 
 * <p>Tests verify:
 * - Group CRUD operations with ownership checks
 * - Quiz membership management (add, remove, reorder)
 * - Position management and ordering invariants
 * - Idempotency for membership operations
 * - Access control via AccessPolicy
 * - Virtual archived group queries
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuizGroupServiceImpl Tests")
class QuizGroupServiceImplTest {

    @Mock
    private QuizGroupRepository quizGroupRepository;

    @Mock
    private QuizGroupMembershipRepository membershipRepository;

    @Mock
    private QuizRepository quizRepository;

    @Mock
    private QuestionRepository questionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private AccessPolicy accessPolicy;

    @Mock
    private QuizGroupMapper quizGroupMapper;

    @InjectMocks
    private QuizGroupServiceImpl quizGroupService;

    private User owner;
    private User otherUser;
    private QuizGroup group;
    private Quiz quiz1;
    private Quiz quiz2;
    private Document document;

    @BeforeEach
    void setUp() {
        owner = createUser("owner", RoleName.ROLE_USER);
        otherUser = createUser("other", RoleName.ROLE_USER);

        group = new QuizGroup();
        group.setId(UUID.randomUUID());
        group.setOwner(owner);
        group.setName("Test Group");
        group.setDescription("Test Description");
        group.setIsDeleted(false);
        group.setMemberships(new HashSet<>());

        quiz1 = createQuiz(owner, "Quiz 1");
        quiz2 = createQuiz(owner, "Quiz 2");

        document = new Document();
        document.setId(UUID.randomUUID());
        document.setUploadedBy(owner);
    }

    // =============== CREATE Tests ===============

    @Nested
    @DisplayName("create Tests")
    class CreateTests {

        @Test
        @DisplayName("Successfully create group without document")
        void create_WithoutDocument_Success() {
            // Given
            CreateQuizGroupRequest request = new CreateQuizGroupRequest(
                    "My Group", "Description", "#FF5733", "book", null
            );

            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            when(quizGroupMapper.toEntity(any(), eq(owner), isNull()))
                    .thenReturn(group);
            when(quizGroupRepository.save(any(QuizGroup.class))).thenReturn(group);

            // When
            UUID groupId = quizGroupService.create("owner", request);

            // Then
            assertThat(groupId).isEqualTo(group.getId());
            verify(quizGroupRepository).save(any(QuizGroup.class));
            verify(documentRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Successfully create group with document")
        void create_WithDocument_Success() {
            // Given
            CreateQuizGroupRequest request = new CreateQuizGroupRequest(
                    "My Group", "Description", null, null, document.getId()
            );

            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            when(documentRepository.findById(document.getId()))
                    .thenReturn(Optional.of(document));
            when(quizGroupMapper.toEntity(any(), eq(owner), eq(document)))
                    .thenReturn(group);
            when(quizGroupRepository.save(any(QuizGroup.class))).thenReturn(group);

            // When
            UUID groupId = quizGroupService.create("owner", request);

            // Then
            assertThat(groupId).isEqualTo(group.getId());
            verify(documentRepository).findById(document.getId());
        }

        @Test
        @DisplayName("Fail when document not owned by user")
        void create_WithUnownedDocument_ThrowsForbidden() {
            // Given
            document.setUploadedBy(otherUser);
            CreateQuizGroupRequest request = new CreateQuizGroupRequest(
                    "My Group", null, null, null, document.getId()
            );

            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            when(documentRepository.findById(document.getId()))
                    .thenReturn(Optional.of(document));

            // When/Then
            assertThatThrownBy(() -> quizGroupService.create("owner", request))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("not owned by the user");
        }
    }

    // =============== ADD QUIZZES Tests ===============

    @Nested
    @DisplayName("addQuizzes Tests")
    class AddQuizzesTests {

        @Test
        @DisplayName("Successfully add quizzes to empty group")
        void addQuizzes_ToEmptyGroup_Success() {
            // Given
            AddQuizzesToGroupRequest request = new AddQuizzesToGroupRequest(
                    List.of(quiz1.getId(), quiz2.getId()), null
            );

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            when(membershipRepository.findByGroupIdOrderByPositionAsc(group.getId()))
                    .thenReturn(Collections.emptyList());
            when(quizRepository.findByIdIn(anyList())).thenReturn(List.of(quiz1, quiz2));
            when(membershipRepository.saveAll(anyList()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            quizGroupService.addQuizzes("owner", group.getId(), request);

            // Then
            ArgumentCaptor<List<QuizGroupMembership>> captor = ArgumentCaptor.forClass(List.class);
            verify(membershipRepository, times(1)).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(2);
            verify(membershipRepository, never()).save(any(QuizGroupMembership.class));
        }

        @Test
        @DisplayName("Add quizzes is idempotent - ignores existing memberships")
        void addQuizzes_ExistingMembership_Ignores() {
            // Given
            QuizGroupMembership existing = createMembership(group, quiz1, 0);
            AddQuizzesToGroupRequest request = new AddQuizzesToGroupRequest(
                    List.of(quiz1.getId(), quiz2.getId()), null
            );

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            when(membershipRepository.findByGroupIdOrderByPositionAsc(group.getId()))
                    .thenReturn(List.of(existing));
            when(quizRepository.findByIdIn(List.of(quiz2.getId())))
                    .thenReturn(List.of(quiz2));
            when(membershipRepository.saveAll(anyList()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            quizGroupService.addQuizzes("owner", group.getId(), request);

            // Then
            ArgumentCaptor<List<QuizGroupMembership>> captor = ArgumentCaptor.forClass(List.class);
            verify(membershipRepository, times(1)).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(1); // Only quiz2
            verify(membershipRepository, never()).save(any(QuizGroupMembership.class));
        }

        @Test
        @DisplayName("Fail when quiz not owned by user")
        void addQuizzes_UnownedQuiz_ThrowsForbidden() {
            // Given
            quiz1.setCreator(otherUser);
            AddQuizzesToGroupRequest request = new AddQuizzesToGroupRequest(
                    List.of(quiz1.getId()), null
            );

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            when(membershipRepository.findByGroupIdOrderByPositionAsc(group.getId()))
                    .thenReturn(Collections.emptyList());
            when(quizRepository.findByIdIn(anyList())).thenReturn(List.of(quiz1));

            // When/Then
            assertThatThrownBy(() -> quizGroupService.addQuizzes("owner", group.getId(), request))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("not owned by user");
        }

        @Test
        @DisplayName("Fail when null quiz ID in request")
        void addQuizzes_NullQuizId_ThrowsValidationException() {
            // Given
            AddQuizzesToGroupRequest request = new AddQuizzesToGroupRequest(
                    Arrays.asList(quiz1.getId(), null), null
            );

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());

            // When/Then
            assertThatThrownBy(() -> quizGroupService.addQuizzes("owner", group.getId(), request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("must not contain null values");
        }
    }

    // =============== REORDER Tests ===============

    @Nested
    @DisplayName("reorder Tests")
    class ReorderTests {

        @Test
        @DisplayName("Successfully reorder quizzes")
        void reorder_Success() {
            // Given
            QuizGroupMembership m1 = createMembership(group, quiz1, 0);
            QuizGroupMembership m2 = createMembership(group, quiz2, 1);
            ReorderGroupQuizzesRequest request = new ReorderGroupQuizzesRequest(
                    List.of(quiz2.getId(), quiz1.getId())
            );

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            when(membershipRepository.findByGroupIdOrderByPositionAsc(group.getId()))
                    .thenReturn(List.of(m1, m2));
            when(membershipRepository.saveAll(anyList()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            quizGroupService.reorder("owner", group.getId(), request);

            // Then
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<QuizGroupMembership>> captor = ArgumentCaptor.forClass(List.class);
            verify(membershipRepository).saveAll(captor.capture());
            List<QuizGroupMembership> saved = captor.getValue();
            assertThat(saved).hasSize(2);
            
            // Verify positions by quiz ID (order in list may not match position order)
            QuizGroupMembership quiz2Membership = saved.stream()
                    .filter(m -> m.getQuiz().getId().equals(quiz2.getId()))
                    .findFirst()
                    .orElseThrow();
            QuizGroupMembership quiz1Membership = saved.stream()
                    .filter(m -> m.getQuiz().getId().equals(quiz1.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(quiz2Membership.getPosition()).isEqualTo(0); // quiz2 at position 0
            assertThat(quiz1Membership.getPosition()).isEqualTo(1); // quiz1 at position 1
        }

        @Test
        @DisplayName("Fail when ordered IDs don't match membership")
        void reorder_InvalidOrder_ThrowsIllegalArgumentException() {
            // Given
            Quiz quiz3 = createQuiz(owner, "Quiz 3");
            ReorderGroupQuizzesRequest request = new ReorderGroupQuizzesRequest(
                    List.of(quiz3.getId()) // quiz3 is not in group
            );

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            when(membershipRepository.findByGroupIdOrderByPositionAsc(group.getId()))
                    .thenReturn(Collections.emptyList());

            // When/Then
            assertThatThrownBy(() -> quizGroupService.reorder("owner", group.getId(), request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must match current group membership");
        }
    }

    // =============== REMOVE QUIZ Tests ===============

    @Nested
    @DisplayName("removeQuiz Tests")
    class RemoveQuizTests {

        @Test
        @DisplayName("Successfully remove quiz from group")
        void removeQuiz_Success() {
            // Given
            QuizGroupMembershipId id = new QuizGroupMembershipId(group.getId(), quiz1.getId());
            QuizGroupMembership membership1 = createMembership(group, quiz1, 0);
            QuizGroupMembership membership2 = createMembership(group, quiz2, 1);

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            when(membershipRepository.findById(id)).thenReturn(Optional.of(membership1));
            doNothing().when(membershipRepository).deleteById(id);
            doNothing().when(membershipRepository).flush();
            when(membershipRepository.findByGroupIdOrderByPositionAsc(group.getId()))
                    .thenReturn(List.of(membership2)); // After deletion, only quiz2 remains
            when(membershipRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            quizGroupService.removeQuiz("owner", group.getId(), quiz1.getId());

            // Then
            verify(membershipRepository).deleteById(id);
            verify(membershipRepository).flush();
            verify(membershipRepository).saveAll(anyList());
            
            // Verify membership2 position was decremented from 1 to 0
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<QuizGroupMembership>> captor = ArgumentCaptor.forClass(List.class);
            verify(membershipRepository).saveAll(captor.capture());
            List<QuizGroupMembership> renumbered = captor.getValue();
            assertThat(renumbered).hasSize(1);
            assertThat(renumbered.get(0).getPosition()).isEqualTo(0);
        }

        @Test
        @DisplayName("Remove quiz from middle - renumbers later positions")
        void removeQuiz_FromMiddle_RenumbersPositions() {
            // Given - positions [0, 1, 2], remove position 1
            Quiz quiz3 = createQuiz(owner, "Quiz 3");
            QuizGroupMembershipId id = new QuizGroupMembershipId(group.getId(), quiz2.getId());
            QuizGroupMembership membership1 = createMembership(group, quiz1, 0);
            QuizGroupMembership membership2 = createMembership(group, quiz2, 1);
            QuizGroupMembership membership3 = createMembership(group, quiz3, 2);

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            when(membershipRepository.findById(id)).thenReturn(Optional.of(membership2));
            doNothing().when(membershipRepository).deleteById(id);
            doNothing().when(membershipRepository).flush();
            // After deletion, quiz1 at position 0, quiz3 at position 2
            when(membershipRepository.findByGroupIdOrderByPositionAsc(group.getId()))
                    .thenReturn(List.of(membership1, membership3));
            when(membershipRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            quizGroupService.removeQuiz("owner", group.getId(), quiz2.getId());

            // Then
            verify(membershipRepository).deleteById(id);
            verify(membershipRepository).flush();
            
            // Verify membership3 position was decremented from 2 to 1
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<QuizGroupMembership>> captor = ArgumentCaptor.forClass(List.class);
            verify(membershipRepository).saveAll(captor.capture());
            List<QuizGroupMembership> renumbered = captor.getValue();
            assertThat(renumbered).hasSize(1);
            assertThat(renumbered.get(0).getQuiz().getId()).isEqualTo(quiz3.getId());
            assertThat(renumbered.get(0).getPosition()).isEqualTo(1); // Was 2, now 1
        }

        @Test
        @DisplayName("Remove quiz from end - no renumbering needed")
        void removeQuiz_FromEnd_NoRenumbering() {
            // Given - positions [0, 1], remove position 1 (last)
            QuizGroupMembershipId id = new QuizGroupMembershipId(group.getId(), quiz2.getId());
            QuizGroupMembership membership1 = createMembership(group, quiz1, 0);
            QuizGroupMembership membership2 = createMembership(group, quiz2, 1);

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            when(membershipRepository.findById(id)).thenReturn(Optional.of(membership2));
            doNothing().when(membershipRepository).deleteById(id);
            doNothing().when(membershipRepository).flush();
            // After deletion, only quiz1 remains at position 0
            when(membershipRepository.findByGroupIdOrderByPositionAsc(group.getId()))
                    .thenReturn(List.of(membership1));

            // When
            quizGroupService.removeQuiz("owner", group.getId(), quiz2.getId());

            // Then
            verify(membershipRepository).deleteById(id);
            verify(membershipRepository).flush();
            // No renumbering should happen since we removed the last item
            verify(membershipRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("Remove is idempotent - no exception if membership doesn't exist")
        void removeQuiz_NonExistentMembership_NoException() {
            // Given
            QuizGroupMembershipId id = new QuizGroupMembershipId(group.getId(), quiz1.getId());

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            when(membershipRepository.findById(id)).thenReturn(Optional.empty());

            // When
            quizGroupService.removeQuiz("owner", group.getId(), quiz1.getId());

            // Then
            verify(membershipRepository, never()).deleteById(any());
            verify(membershipRepository, never()).flush();
            verify(membershipRepository, never()).saveAll(anyList());
        }
    }

    // =============== GET Tests ===============

    @Nested
    @DisplayName("get Tests")
    class GetTests {

        @Test
        @DisplayName("Successfully get group by ID")
        void get_Success() {
            // Given
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(true);
            when(auth.getName()).thenReturn("owner");

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            when(membershipRepository.countByGroupId(group.getId())).thenReturn(5L);

            QuizGroupDto expectedDto = new QuizGroupDto(
                    group.getId(), owner.getId(), group.getName(), group.getDescription(),
                    null, null, null, 5L, Instant.now(), Instant.now()
            );
            when(quizGroupMapper.toDto(eq(group), eq(5L))).thenReturn(expectedDto);

            // When
            QuizGroupDto result = quizGroupService.get(group.getId(), auth);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(group.getId());
            assertThat(result.quizCount()).isEqualTo(5L);
            verify(quizGroupRepository).findById(group.getId());
            verify(accessPolicy).requireOwnerOrAny(eq(owner), eq(owner.getId()), any());
        }

        @Test
        @DisplayName("Fail when group not found")
        void get_NotFound_ThrowsResourceNotFoundException() {
            // Given
            UUID nonExistentId = UUID.randomUUID();
            Authentication auth = mock(Authentication.class);

            when(quizGroupRepository.findById(nonExistentId))
                    .thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> quizGroupService.get(nonExistentId, auth))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Fail when user not authenticated")
        void get_Unauthenticated_ThrowsForbiddenException() {
            // Given
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(false);

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));

            // When/Then
            assertThatThrownBy(() -> quizGroupService.get(group.getId(), auth))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Authentication required");
        }
    }

    // =============== LIST Tests ===============

    @Nested
    @DisplayName("list Tests")
    class ListTests {

        @Test
        @DisplayName("Successfully list groups with pagination")
        void list_Success() {
            // Given
            Pageable pageable = PageRequest.of(0, 20);
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(true);
            when(auth.getName()).thenReturn("owner");

            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));

            QuizGroupSummaryProjection projection = mock(QuizGroupSummaryProjection.class);
            when(projection.getName()).thenReturn("Group 1");
            when(projection.getDescription()).thenReturn("Description 1");
            when(projection.getColor()).thenReturn("#FF5733");
            when(projection.getIcon()).thenReturn("book");
            when(projection.getCreatedAt()).thenReturn(Instant.now());
            when(projection.getUpdatedAt()).thenReturn(Instant.now());
            when(projection.getQuizCount()).thenReturn(3L);

            Page<QuizGroupSummaryProjection> projectionPage = new PageImpl<>(
                    List.of(projection), pageable, 1
            );

            when(quizGroupRepository.findByOwnerIdProjected(owner.getId(), pageable))
                    .thenReturn(projectionPage);

            // When
            Page<QuizGroupSummaryDto> result = quizGroupService.list(pageable, auth, false, 0);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).name()).isEqualTo("Group 1");
            verify(quizGroupRepository).findByOwnerIdProjected(owner.getId(), pageable);
        }

        @Test
        @DisplayName("Fail when user not authenticated")
        void list_Unauthenticated_ThrowsForbiddenException() {
            // Given
            Pageable pageable = PageRequest.of(0, 20);
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(false);

            // When/Then
            assertThatThrownBy(() -> quizGroupService.list(pageable, auth, false, 0))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Authentication required");
        }

        @Test
        @DisplayName("Successfully list groups with quiz previews")
        void list_WithQuizPreviews_Success() {
            // Given
            Pageable pageable = PageRequest.of(0, 20);
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(true);
            when(auth.getName()).thenReturn("owner");

            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));

            QuizGroupSummaryProjection projection = mock(QuizGroupSummaryProjection.class);
            when(projection.getId()).thenReturn(group.getId());
            when(projection.getName()).thenReturn("Group 1");
            when(projection.getDescription()).thenReturn("Description");
            when(projection.getColor()).thenReturn("#FF5733");
            when(projection.getIcon()).thenReturn("book");
            when(projection.getCreatedAt()).thenReturn(Instant.now());
            when(projection.getUpdatedAt()).thenReturn(Instant.now());
            when(projection.getQuizCount()).thenReturn(3L);

            Page<QuizGroupSummaryProjection> projectionPage = new PageImpl<>(
                    List.of(projection), pageable, 1
            );

            when(quizGroupRepository.findByOwnerIdProjected(owner.getId(), pageable))
                    .thenReturn(projectionPage);

            QuizGroupMembership m1 = createMembership(group, quiz1, 0);
            QuizGroupMembership m2 = createMembership(group, quiz2, 1);
            when(membershipRepository.findByGroupIdsOrdered(List.of(group.getId())))
                    .thenReturn(List.of(m1, m2));
            when(quizRepository.findByIdIn(List.of(quiz1.getId(), quiz2.getId())))
                    .thenReturn(List.of(quiz1, quiz2));
            when(questionRepository.countQuestionsForQuizzes(anyList()))
                    .thenReturn(List.of(new Object[]{quiz1.getId(), 5L}, new Object[]{quiz2.getId(), 3L}));

            // When
            Page<QuizGroupSummaryDto> result = quizGroupService.list(pageable, auth, true, 5);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).name()).isEqualTo("Group 1");
            assertThat(result.getContent().get(0).quizPreviews()).isNotNull();
            assertThat(result.getContent().get(0).quizPreviews()).hasSize(2);
            verify(quizGroupRepository).findByOwnerIdProjected(owner.getId(), pageable);
            verify(membershipRepository).findByGroupIdsOrdered(List.of(group.getId()));
            verify(quizRepository).findByIdIn(anyList());
            verify(questionRepository).countQuestionsForQuizzes(anyList());
        }
    }

    // =============== UPDATE Tests ===============

    @Nested
    @DisplayName("update Tests")
    class UpdateTests {

        @Test
        @DisplayName("Successfully update group")
        void update_Success() {
            // Given
            UpdateQuizGroupRequest request = new UpdateQuizGroupRequest(
                    "Updated Name", "Updated Description", "#00FF00", "star"
            );

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            when(quizGroupRepository.save(any(QuizGroup.class))).thenReturn(group);
            when(membershipRepository.countByGroupId(group.getId())).thenReturn(3L);

            QuizGroupDto updatedDto = new QuizGroupDto(
                    group.getId(), owner.getId(), "Updated Name", "Updated Description",
                    "#00FF00", "star", null, 3L, Instant.now(), Instant.now()
            );
            doNothing().when(quizGroupMapper).updateEntity(any(QuizGroup.class), eq(request));
            when(quizGroupMapper.toDto(eq(group), eq(3L))).thenReturn(updatedDto);

            // When
            QuizGroupDto result = quizGroupService.update("owner", group.getId(), request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("Updated Name");
            verify(quizGroupMapper).updateEntity(any(QuizGroup.class), eq(request));
            verify(quizGroupRepository).save(any(QuizGroup.class));
        }

        @Test
        @DisplayName("Fail when group not found")
        void update_NotFound_ThrowsResourceNotFoundException() {
            // Given
            UUID nonExistentId = UUID.randomUUID();
            UpdateQuizGroupRequest request = new UpdateQuizGroupRequest(
                    "Updated Name", null, null, null
            );

            when(quizGroupRepository.findById(nonExistentId))
                    .thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> quizGroupService.update("owner", nonExistentId, request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Fail when user not owner")
        void update_UnownedGroup_ThrowsForbiddenException() {
            // Given
            group.setOwner(otherUser);
            UpdateQuizGroupRequest request = new UpdateQuizGroupRequest(
                    "Updated Name", null, null, null
            );

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doThrow(new ForbiddenException("Forbidden"))
                    .when(accessPolicy).requireOwnerOrAny(eq(owner), eq(otherUser.getId()), any());

            // When/Then
            assertThatThrownBy(() -> quizGroupService.update("owner", group.getId(), request))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    // =============== GET QUIZZES IN GROUP Tests ===============

    @Nested
    @DisplayName("getQuizzesInGroup Tests")
    class GetQuizzesInGroupTests {

        @Test
        @DisplayName("Successfully get quizzes in group")
        void getQuizzesInGroup_Success() {
            // Given
            Pageable pageable = PageRequest.of(0, 20);
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(true);
            when(auth.getName()).thenReturn("owner");

            QuizGroupMembership m1 = createMembership(group, quiz1, 0);
            QuizGroupMembership m2 = createMembership(group, quiz2, 1);

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            Page<QuizGroupMembership> membershipPage = new PageImpl<>(List.of(m1, m2), pageable, 2);
            when(membershipRepository.findPageByGroupId(group.getId(), pageable))
                    .thenReturn(membershipPage);
            when(quizRepository.findByIdIn(anyList()))
                    .thenReturn(List.of(quiz1, quiz2));
            when(questionRepository.countQuestionsForQuizzes(anyList()))
                    .thenReturn(List.of(new Object[]{quiz1.getId(), 5L}, new Object[]{quiz2.getId(), 3L}));

            // When
            Page<QuizSummaryDto> result = quizGroupService.getQuizzesInGroup(group.getId(), pageable, auth);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(2);
            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent().get(0).id()).isEqualTo(quiz1.getId());
            assertThat(result.getContent().get(1).id()).isEqualTo(quiz2.getId());
            verify(membershipRepository).findPageByGroupId(group.getId(), pageable);
            verify(quizRepository).findByIdIn(anyList());
            verify(questionRepository).countQuestionsForQuizzes(anyList());
        }

        @Test
        @DisplayName("Return empty page when group has no quizzes")
        void getQuizzesInGroup_EmptyGroup_ReturnsEmptyPage() {
            // Given
            Pageable pageable = PageRequest.of(0, 20);
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(true);
            when(auth.getName()).thenReturn("owner");

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            Page<QuizGroupMembership> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
            when(membershipRepository.findPageByGroupId(group.getId(), pageable))
                    .thenReturn(emptyPage);

            // When
            Page<QuizSummaryDto> result = quizGroupService.getQuizzesInGroup(group.getId(), pageable, auth);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(0);
            assertThat(result.getContent()).isEmpty();
            verify(membershipRepository).findPageByGroupId(group.getId(), pageable);
        }

        @Test
        @DisplayName("Fail when group not found")
        void getQuizzesInGroup_NotFound_ThrowsResourceNotFoundException() {
            // Given
            UUID nonExistentId = UUID.randomUUID();
            Pageable pageable = PageRequest.of(0, 20);
            Authentication auth = mock(Authentication.class);

            when(quizGroupRepository.findById(nonExistentId))
                    .thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> quizGroupService.getQuizzesInGroup(nonExistentId, pageable, auth))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Fail when user not authenticated")
        void getQuizzesInGroup_Unauthenticated_ThrowsForbiddenException() {
            // Given
            Pageable pageable = PageRequest.of(0, 20);
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(false);

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));

            // When/Then
            assertThatThrownBy(() -> quizGroupService.getQuizzesInGroup(group.getId(), pageable, auth))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Authentication required");
        }
    }

    // =============== GET ARCHIVED QUIZZES Tests ===============

    @Nested
    @DisplayName("getArchivedQuizzes Tests")
    class GetArchivedQuizzesTests {

        @Test
        @DisplayName("Successfully get archived quizzes")
        void getArchivedQuizzes_Success() {
            // Given
            quiz1.setStatus(QuizStatus.ARCHIVED);
            Pageable pageable = PageRequest.of(0, 20);
            Page<Quiz> archivedPage = new PageImpl<>(List.of(quiz1), pageable, 1);

            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(true);
            when(auth.getName()).thenReturn("owner");
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            @SuppressWarnings("unchecked")
            Specification<Quiz> spec = any(Specification.class);
            when(quizRepository.findAll(spec, eq(pageable))).thenReturn(archivedPage);

            // When
            Page<QuizSummaryDto> result = quizGroupService.getArchivedQuizzes(pageable, auth);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(1);
        }
    }

    // =============== Helper Methods ===============

    private User createUser(String username, RoleName roleName) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setActive(true);
        user.setDeleted(false);
        return user;
    }

    private Quiz createQuiz(User creator, String title) {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setCreator(creator);
        quiz.setTitle(title);
        quiz.setDescription("Description for " + title);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setIsDeleted(false);
        quiz.setCreatedAt(Instant.now());
        quiz.setUpdatedAt(Instant.now());
        quiz.setEstimatedTime(10);
        quiz.setQuestions(new HashSet<>());
        quiz.setTags(new HashSet<>());
        return quiz;
    }

    private QuizGroupMembership createMembership(QuizGroup group, Quiz quiz, int position) {
        QuizGroupMembership membership = new QuizGroupMembership();
        membership.setId(new QuizGroupMembershipId(group.getId(), quiz.getId()));
        membership.setGroup(group);
        membership.setQuiz(quiz);
        membership.setPosition(position);
        membership.setCreatedAt(Instant.now());
        return membership;
    }
}

