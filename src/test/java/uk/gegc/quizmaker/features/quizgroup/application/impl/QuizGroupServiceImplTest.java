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
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSummaryDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
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
            when(quizRepository.findAllById(anyList())).thenReturn(List.of(quiz1, quiz2));
            when(membershipRepository.save(any(QuizGroupMembership.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            quizGroupService.addQuizzes("owner", group.getId(), request);

            // Then
            verify(membershipRepository, times(2)).save(any(QuizGroupMembership.class));
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
            when(quizRepository.findAllById(List.of(quiz2.getId())))
                    .thenReturn(List.of(quiz2));
            when(membershipRepository.save(any(QuizGroupMembership.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            quizGroupService.addQuizzes("owner", group.getId(), request);

            // Then
            verify(membershipRepository, times(1)).save(any(QuizGroupMembership.class)); // Only quiz2
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
            when(quizRepository.findAllById(anyList())).thenReturn(List.of(quiz1));

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

            when(quizGroupRepository.findById(group.getId()))
                    .thenReturn(Optional.of(group));
            when(userRepository.findByUsername("owner"))
                    .thenReturn(Optional.of(owner));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(owner), any(), any());
            when(membershipRepository.existsById(id)).thenReturn(true);
            doNothing().when(membershipRepository).deleteById(id);

            // When
            quizGroupService.removeQuiz("owner", group.getId(), quiz1.getId());

            // Then
            verify(membershipRepository).deleteById(id);
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
            when(membershipRepository.existsById(id)).thenReturn(false);

            // When
            quizGroupService.removeQuiz("owner", group.getId(), quiz1.getId());

            // Then
            verify(membershipRepository, never()).deleteById(any());
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
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setIsDeleted(false);
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

