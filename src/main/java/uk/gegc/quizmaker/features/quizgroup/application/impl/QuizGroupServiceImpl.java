package uk.gegc.quizmaker.features.quizgroup.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSummaryDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quizgroup.api.dto.*;
import uk.gegc.quizmaker.features.quizgroup.application.QuizGroupService;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroup;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroupMembership;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroupMembershipId;
import uk.gegc.quizmaker.features.quizgroup.domain.repository.QuizGroupMembershipRepository;
import uk.gegc.quizmaker.features.quizgroup.domain.repository.QuizGroupRepository;
import uk.gegc.quizmaker.features.quizgroup.domain.repository.projection.QuizGroupSummaryProjection;
import uk.gegc.quizmaker.features.quizgroup.infra.mapping.QuizGroupMapper;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AccessPolicy;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuizGroupServiceImpl implements QuizGroupService {

    private final QuizGroupRepository quizGroupRepository;
    private final QuizGroupMembershipRepository membershipRepository;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final AccessPolicy accessPolicy;
    private final QuizGroupMapper quizGroupMapper;

    @Transactional
    @Override
    public UUID create(String username, CreateQuizGroupRequest request) {
        User owner = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        Document document = null;
        if (request.documentId() != null) {
            document = documentRepository.findById(request.documentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Document " + request.documentId() + " not found"));

            if (document.getUploadedBy() == null || !document.getUploadedBy().getId().equals(owner.getId())) {
                throw new ForbiddenException("Cannot link document " + request.documentId() + " that is not owned by the user");
            }
        }

        QuizGroup group = quizGroupMapper.toEntity(request, owner, document);
        QuizGroup saved = quizGroupRepository.save(group);
        
        log.info("Created quiz group {} for user {}", saved.getId(), owner.getId());
        return saved.getId();
    }

    @Transactional(readOnly = true)
    @Override
    public QuizGroupDto get(UUID id, Authentication authentication) {
        QuizGroup group = quizGroupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz group " + id + " not found"));

        User user = getCurrentUser(authentication);
        if (user == null) {
            throw new ForbiddenException("Authentication required");
        }

        // Ownership check: user must be the owner or have QUIZ_GROUP_ADMIN permission
        accessPolicy.requireOwnerOrAny(user,
                group.getOwner() != null ? group.getOwner().getId() : null,
                PermissionName.QUIZ_GROUP_ADMIN);

        long quizCount = membershipRepository.countByGroupId(id);
        return quizGroupMapper.toDto(group, quizCount);
    }

    @Transactional(readOnly = true)
    @Override
    public Page<QuizGroupSummaryDto> list(Pageable pageable,
                                          Authentication authentication,
                                          boolean includeQuizPreviews,
                                          int previewSize) {
        User user = getCurrentUser(authentication);
        if (user == null) {
            throw new ForbiddenException("Authentication required");
        }

        // Owner-only in Phase 1; extend with QUIZ_GROUP_READ for admins later
        Page<QuizGroupSummaryProjection> projections = quizGroupRepository.findByOwnerIdProjected(user.getId(), pageable);
        List<QuizGroupSummaryProjection> projectionContent = projections.getContent();

        Map<UUID, List<QuizSummaryDto>> previews = includeQuizPreviews
                ? buildQuizPreviews(projectionContent, previewSize)
                : Collections.emptyMap();

        return projections.map(projection ->
                QuizGroupSummaryDto.fromProjection(
                        projection,
                        previews.getOrDefault(projection.getId(), List.of())
                ));
    }

    @Transactional
    @Override
    public QuizGroupDto update(String username, UUID id, UpdateQuizGroupRequest request) {
        QuizGroup group = quizGroupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz group " + id + " not found"));

        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        // Ownership check
        accessPolicy.requireOwnerOrAny(user,
                group.getOwner() != null ? group.getOwner().getId() : null,
                PermissionName.QUIZ_GROUP_ADMIN);

        quizGroupMapper.updateEntity(group, request);
        QuizGroup saved = quizGroupRepository.save(group);
        
        log.info("Updated quiz group {} by user {}", id, user.getId());
        
        long quizCount = membershipRepository.countByGroupId(id);
        return quizGroupMapper.toDto(saved, quizCount);
    }

    @Transactional
    @Override
    public void delete(String username, UUID id) {
        QuizGroup group = quizGroupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz group " + id + " not found"));

        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        // Ownership check
        accessPolicy.requireOwnerOrAny(user,
                group.getOwner() != null ? group.getOwner().getId() : null,
                PermissionName.QUIZ_GROUP_ADMIN);

        quizGroupRepository.deleteById(id);
        log.info("Deleted quiz group {} by user {}", id, user.getId());
    }

    @Transactional(readOnly = true)
    @Override
    public Page<QuizSummaryDto> getQuizzesInGroup(UUID groupId, Pageable pageable, Authentication authentication) {
        QuizGroup group = quizGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz group " + groupId + " not found"));

        User user = getCurrentUser(authentication);
        if (user == null) {
            throw new ForbiddenException("Authentication required");
        }

        // Ownership check
        accessPolicy.requireOwnerOrAny(user,
                group.getOwner() != null ? group.getOwner().getId() : null,
                PermissionName.QUIZ_GROUP_ADMIN);

        Page<QuizGroupMembership> membershipPage = membershipRepository.findPageByGroupId(groupId, pageable);

        if (membershipPage.isEmpty()) {
            return Page.empty(pageable);
        }

        List<UUID> quizIds = membershipPage.getContent().stream()
                .map(m -> m.getId().getQuizId())
                .toList();

        List<Quiz> quizzes = quizRepository.findByIdIn(quizIds);
        Map<UUID, Quiz> quizMap = quizzes.stream()
                .filter(q -> !Boolean.TRUE.equals(q.getIsDeleted()))
                .collect(Collectors.toMap(Quiz::getId, q -> q));

        Map<UUID, Long> questionCounts = fetchQuestionCounts(quizIds);

        List<QuizSummaryDto> dtos = membershipPage.getContent().stream()
                .map(membership -> {
                    Quiz quiz = quizMap.get(membership.getId().getQuizId());
                    if (quiz == null) {
                        return null;
                    }
                    long questionCount = questionCounts.getOrDefault(quiz.getId(), 0L);
                    return toQuizSummaryDto(quiz, questionCount);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new org.springframework.data.domain.PageImpl<>(dtos, pageable, membershipPage.getTotalElements());
    }

    @Transactional
    @Override
    public void addQuizzes(String username, UUID groupId, AddQuizzesToGroupRequest request) {
        QuizGroup group = quizGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz group " + groupId + " not found"));

        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        // Ownership check
        accessPolicy.requireOwnerOrAny(user,
                group.getOwner() != null ? group.getOwner().getId() : null,
                PermissionName.QUIZ_GROUP_ADMIN);

        if (request.quizIds().stream().anyMatch(Objects::isNull)) {
            throw new ValidationException("Quiz IDs must not contain null values");
        }

        List<QuizGroupMembership> existingMemberships = membershipRepository.findByGroupIdOrderByPositionAsc(groupId);
        Set<UUID> existingQuizIds = existingMemberships.stream()
                .map(m -> m.getId().getQuizId())
                .collect(Collectors.toSet());

        List<UUID> normalizedRequestIds = request.quizIds().stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        ArrayList::new
                ));

        List<UUID> newQuizIds = normalizedRequestIds.stream()
                .filter(id -> !existingQuizIds.contains(id))
                .collect(Collectors.toList());

        if (newQuizIds.isEmpty()) {
            log.info("All quizzes already in group {}, skipping add", groupId);
            return;
        }

        List<Quiz> quizzes = quizRepository.findAllById(newQuizIds);
        Map<UUID, Quiz> quizzesById = quizzes.stream()
                .collect(Collectors.toMap(Quiz::getId, q -> q));

        List<UUID> missingQuizzes = newQuizIds.stream()
                .filter(id -> !quizzesById.containsKey(id))
                .collect(Collectors.toList());
        if (!missingQuizzes.isEmpty()) {
            throw new ResourceNotFoundException("Quizzes not found: " + missingQuizzes);
        }

        for (Quiz quiz : quizzesById.values()) {
            if (quiz.getCreator() == null || !quiz.getCreator().getId().equals(user.getId())) {
                throw new ForbiddenException("Cannot add quiz " + quiz.getId() + " - not owned by user");
            }
        }

        int insertIndex = request.position() != null && request.position() >= 0
                ? Math.min(request.position(), existingMemberships.size())
                : existingMemberships.size();

        if (insertIndex < existingMemberships.size()) {
            int shiftBy = newQuizIds.size();
            // Create a copy to avoid modifying the original list's backing array issues
            List<QuizGroupMembership> toShift = new ArrayList<>(existingMemberships.subList(insertIndex, existingMemberships.size()));
            // Shift positions from right to left to avoid temporary constraint violations
            for (int i = toShift.size() - 1; i >= 0; i--) {
                QuizGroupMembership membership = toShift.get(i);
                membership.setPosition(membership.getPosition() + shiftBy);
            }
            membershipRepository.saveAll(toShift);
            membershipRepository.flush(); // Ensure shifts are persisted before inserts to avoid constraint violations
        }

        int nextPosition = insertIndex;
        for (UUID quizId : newQuizIds) {
            Quiz quiz = quizzesById.get(quizId);
            QuizGroupMembership membership = new QuizGroupMembership();
            membership.setId(new QuizGroupMembershipId(groupId, quizId));
            membership.setGroup(group);
            membership.setQuiz(quiz);
            membership.setPosition(nextPosition++);
            membershipRepository.save(membership);
        }

        log.info("Added {} quizzes to group {} by user {}", newQuizIds.size(), groupId, user.getId());
    }

    @Transactional
    @Override
    public void removeQuiz(String username, UUID groupId, UUID quizId) {
        QuizGroup group = quizGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz group " + groupId + " not found"));

        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        // Ownership check
        accessPolicy.requireOwnerOrAny(user,
                group.getOwner() != null ? group.getOwner().getId() : null,
                PermissionName.QUIZ_GROUP_ADMIN);

        QuizGroupMembershipId id = new QuizGroupMembershipId(groupId, quizId);
        QuizGroupMembership membership = membershipRepository.findById(id).orElse(null);
        if (membership == null) {
            log.debug("Quiz {} is not part of group {}, nothing to remove", quizId, groupId);
            return;
        }

        // Store position before deletion for renumbering
        int deletedPosition = membership.getPosition();

        // Delete the membership
        membershipRepository.deleteById(id);
        membershipRepository.flush(); // Ensure deletion is persisted before renumbering

        // Renumber remaining memberships: decrement positions greater than deleted position
        List<QuizGroupMembership> remainingMemberships = membershipRepository.findByGroupIdOrderByPositionAsc(groupId);
        List<QuizGroupMembership> toRenumber = remainingMemberships.stream()
                .filter(m -> m.getPosition() > deletedPosition)
                .collect(Collectors.toList());

        if (!toRenumber.isEmpty()) {
            for (QuizGroupMembership m : toRenumber) {
                m.setPosition(m.getPosition() - 1);
            }
            membershipRepository.saveAll(toRenumber);
        }

        log.info("Removed quiz {} from group {} by user {} and renumbered positions", quizId, groupId, user.getId());
    }

    @Transactional
    @Override
    public void reorder(String username, UUID groupId, ReorderGroupQuizzesRequest request) {
        QuizGroup group = quizGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz group " + groupId + " not found"));

        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        // Ownership check
        accessPolicy.requireOwnerOrAny(user,
                group.getOwner() != null ? group.getOwner().getId() : null,
                PermissionName.QUIZ_GROUP_ADMIN);

        // Get current memberships
        List<QuizGroupMembership> memberships = membershipRepository.findByGroupIdOrderByPositionAsc(groupId);
        Set<UUID> currentQuizIds = memberships.stream()
                .map(m -> m.getId().getQuizId())
                .collect(Collectors.toSet());

        // Validate that orderedQuizIds matches current membership
        if (request.orderedQuizIds().size() != currentQuizIds.size() ||
                !currentQuizIds.containsAll(request.orderedQuizIds())) {
            throw new IllegalArgumentException("Ordered quiz IDs must match current group membership");
        }

        // Retry on optimistic lock failure
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                // Reload group to get fresh version
                group = quizGroupRepository.findById(groupId)
                        .orElseThrow(() -> new ResourceNotFoundException("Quiz group " + groupId + " not found"));

                // Reorder memberships - renumber to dense 0..n-1 sequence
                memberships = membershipRepository.findByGroupIdOrderByPositionAsc(groupId);
                Map<UUID, QuizGroupMembership> membershipMap = memberships.stream()
                        .collect(Collectors.toMap(m -> m.getId().getQuizId(), m -> m));

                // Update positions according to new order
                List<Integer> newPositions = IntStream.range(0, request.orderedQuizIds().size())
                        .boxed()
                        .collect(Collectors.toList());

                for (int i = 0; i < request.orderedQuizIds().size(); i++) {
                    UUID quizId = request.orderedQuizIds().get(i);
                    QuizGroupMembership membership = membershipMap.get(quizId);
                    if (membership != null) {
                        membership.setPosition(newPositions.get(i));
                    }
                }

                membershipRepository.saveAll(memberships);
                log.info("Reordered quizzes in group {} by user {}", groupId, user.getId());
                return;

            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == maxRetries - 1) {
                    log.warn("Failed to reorder after {} retries due to optimistic lock failure", maxRetries);
                    throw new ValidationException("Group was modified concurrently. Please retry.");
                }
                // Wait briefly before retry
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during reorder retry", ie);
                }
            }
        }
    }

    @Transactional(readOnly = true)
    @Override
    public Page<QuizSummaryDto> getArchivedQuizzes(Pageable pageable, Authentication authentication) {
        User user = getCurrentUser(authentication);
        if (user == null) {
            throw new ForbiddenException("Authentication required");
        }

        // Query quizzes with status = ARCHIVED and creator = current user
        Page<Quiz> archivedQuizzes = quizRepository.findAll(
                (root, query, cb) -> cb.and(
                        cb.equal(root.get("creator").get("id"), user.getId()),
                        cb.equal(root.get("status"), QuizStatus.ARCHIVED),
                        cb.equal(root.get("isDeleted"), false)
                ),
                pageable
        );

        if (archivedQuizzes.isEmpty()) {
            return Page.empty(pageable);
        }

        List<UUID> archivedIds = archivedQuizzes.getContent().stream()
                .map(Quiz::getId)
                .toList();
        Map<UUID, Long> questionCounts = fetchQuestionCounts(archivedIds);

        return archivedQuizzes.map(quiz -> toQuizSummaryDto(quiz, questionCounts.getOrDefault(quiz.getId(), 0L)));
    }

    private User getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return userRepository.findByUsername(authentication.getName())
                .or(() -> userRepository.findByEmail(authentication.getName()))
                .orElse(null);
    }

    private Map<UUID, Long> fetchQuestionCounts(List<UUID> quizIds) {
        if (quizIds == null || quizIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return questionRepository.countQuestionsForQuizzes(quizIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }

    private Map<UUID, List<QuizSummaryDto>> buildQuizPreviews(List<QuizGroupSummaryProjection> projections,
                                                              int previewSize) {
        if (previewSize <= 0 || projections.isEmpty()) {
            return Collections.emptyMap();
        }

        int normalizedPreviewSize = Math.min(Math.max(previewSize, 1), 10);
        List<UUID> groupIds = projections.stream()
                .map(QuizGroupSummaryProjection::getId)
                .toList();

        List<QuizGroupMembership> memberships = membershipRepository.findByGroupIdsOrdered(groupIds);
        if (memberships.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<UUID, List<UUID>> groupToQuizIds = new LinkedHashMap<>();
        Set<UUID> completed = new HashSet<>();

        for (QuizGroupMembership membership : memberships) {
            UUID groupId = membership.getId().getGroupId();
            if (completed.contains(groupId)) {
                continue;
            }

            List<UUID> ids = groupToQuizIds.computeIfAbsent(groupId, k -> new ArrayList<>());
            if (ids.size() < normalizedPreviewSize) {
                ids.add(membership.getId().getQuizId());
                if (ids.size() == normalizedPreviewSize) {
                    completed.add(groupId);
                    if (completed.size() == groupIds.size()) {
                        break;
                    }
                }
            }
        }

        if (groupToQuizIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<UUID> quizIds = groupToQuizIds.values().stream()
                .flatMap(Collection::stream)
                .distinct()
                .toList();

        List<Quiz> quizzes = quizRepository.findByIdIn(quizIds);
        if (quizzes.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<UUID, Quiz> quizById = quizzes.stream()
                .filter(q -> !Boolean.TRUE.equals(q.getIsDeleted()))
                .collect(Collectors.toMap(Quiz::getId, q -> q));

        Map<UUID, Long> questionCounts = fetchQuestionCounts(new ArrayList<>(quizById.keySet()));

        Map<UUID, List<QuizSummaryDto>> previews = new HashMap<>();
        for (UUID groupId : groupIds) {
            List<UUID> ids = groupToQuizIds.get(groupId);
            if (ids == null || ids.isEmpty()) {
                continue;
            }
            List<QuizSummaryDto> summaries = ids.stream()
                    .map(quizById::get)
                    .filter(Objects::nonNull)
                    .map(quiz -> toQuizSummaryDto(quiz, questionCounts.getOrDefault(quiz.getId(), 0L)))
                    .collect(Collectors.toList());
            previews.put(groupId, summaries);
        }

        return previews;
    }

    private QuizSummaryDto toQuizSummaryDto(Quiz quiz, long questionCount) {
        return new QuizSummaryDto(
                quiz.getId(),
                quiz.getTitle(),
                quiz.getDescription(),
                quiz.getCreatedAt(),
                quiz.getUpdatedAt(),
                quiz.getStatus(),
                quiz.getVisibility(),
                quiz.getCreator() != null ? quiz.getCreator().getUsername() : null,
                quiz.getCreator() != null ? quiz.getCreator().getId() : null,
                quiz.getCategory() != null ? quiz.getCategory().getName() : null,
                quiz.getCategory() != null ? quiz.getCategory().getId() : null,
                questionCount,
                quiz.getTags() != null ? (long) quiz.getTags().size() : 0L,
                quiz.getEstimatedTime()
        );
    }
}
