package uk.gegc.quizmaker.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AccessPolicy.
 * Tests all ownership and permission checking logic in isolation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessPolicy Unit Tests")
class AccessPolicyTest {

    @Mock
    private AppPermissionEvaluator permissionEvaluator;
    
    @Mock
    private MembershipResolver membershipResolver;
    
    @InjectMocks
    private AccessPolicy accessPolicy;
    
    private User user;
    private UUID ownerId;
    private UUID differentOwnerId;
    
    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("testuser");
        
        ownerId = user.getId();
        differentOwnerId = UUID.randomUUID();
    }
    
    // =============== isOwner Tests ===============
    
    @Nested
    @DisplayName("isOwner Tests")
    class IsOwnerTests {
        
        @Test
        @DisplayName("isOwner: when user is owner then returns true")
        void isOwner_userIsOwner_returnsTrue() {
            // When
            boolean result = accessPolicy.isOwner(user, ownerId);
            
            // Then
            assertThat(result).isTrue();
        }
        
        @Test
        @DisplayName("isOwner: when user is not owner then returns false")
        void isOwner_userIsNotOwner_returnsFalse() {
            // When
            boolean result = accessPolicy.isOwner(user, differentOwnerId);
            
            // Then
            assertThat(result).isFalse();
        }
        
        @Test
        @DisplayName("isOwner: when ownerId is null then returns false")
        void isOwner_ownerIdNull_returnsFalse() {
            // When
            boolean result = accessPolicy.isOwner(user, null);
            
            // Then
            assertThat(result).isFalse();
        }
        
        @Test
        @DisplayName("isOwner: when user is null then returns false")
        void isOwner_userNull_returnsFalse() {
            // When
            boolean result = accessPolicy.isOwner(null, ownerId);
            
            // Then
            assertThat(result).isFalse();
        }
        
        @Test
        @DisplayName("isOwner: when both user and ownerId are null then returns false")
        void isOwner_bothNull_returnsFalse() {
            // When
            boolean result = accessPolicy.isOwner(null, null);
            
            // Then
            assertThat(result).isFalse();
        }
    }
    
    // =============== hasAny Tests ===============
    
    @Nested
    @DisplayName("hasAny Tests")
    class HasAnyTests {
        
        @Test
        @DisplayName("hasAny: when user has first permission then returns true")
        void hasAny_hasFirstPermission_returnsTrue() {
            // Given
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(true);
            
            // When
            boolean result = accessPolicy.hasAny(user, 
                PermissionName.QUIZ_MODERATE, 
                PermissionName.QUIZ_ADMIN);
            
            // Then
            assertThat(result).isTrue();
        }
        
        @Test
        @DisplayName("hasAny: when user has second permission then returns true")
        void hasAny_hasSecondPermission_returnsTrue() {
            // Given
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(false);
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            
            // When
            boolean result = accessPolicy.hasAny(user, 
                PermissionName.QUIZ_MODERATE, 
                PermissionName.QUIZ_ADMIN);
            
            // Then
            assertThat(result).isTrue();
        }
        
        @Test
        @DisplayName("hasAny: when user has both permissions then returns true")
        void hasAny_hasBothPermissions_returnsTrue() {
            // Given - Only need to mock the first one (hasAny returns early)
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(true);
            
            // When
            boolean result = accessPolicy.hasAny(user, 
                PermissionName.QUIZ_MODERATE, 
                PermissionName.QUIZ_ADMIN);
            
            // Then
            assertThat(result).isTrue();
        }
        
        @Test
        @DisplayName("hasAny: when user has no permissions then returns false")
        void hasAny_hasNoPermissions_returnsFalse() {
            // Given
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(false);
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            
            // When
            boolean result = accessPolicy.hasAny(user, 
                PermissionName.QUIZ_MODERATE, 
                PermissionName.QUIZ_ADMIN);
            
            // Then
            assertThat(result).isFalse();
        }
        
        @Test
        @DisplayName("hasAny: when user is null then returns false")
        void hasAny_userNull_returnsFalse() {
            // When
            boolean result = accessPolicy.hasAny(null, 
                PermissionName.QUIZ_MODERATE, 
                PermissionName.QUIZ_ADMIN);
            
            // Then
            assertThat(result).isFalse();
        }
        
        @Test
        @DisplayName("hasAny: when no permissions provided then returns false")
        void hasAny_noPermissions_returnsFalse() {
            // When
            boolean result = accessPolicy.hasAny(user);
            
            // Then
            assertThat(result).isFalse();
        }
        
        @Test
        @DisplayName("hasAny: when single permission and user has it then returns true")
        void hasAny_singlePermissionAndHasIt_returnsTrue() {
            // Given
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(true);
            
            // When
            boolean result = accessPolicy.hasAny(user, PermissionName.QUIZ_MODERATE);
            
            // Then
            assertThat(result).isTrue();
        }
    }
    
    // =============== requireOwner Tests ===============
    
    @Nested
    @DisplayName("requireOwner Tests")
    class RequireOwnerTests {
        
        @Test
        @DisplayName("requireOwner: when user is owner then succeeds")
        void requireOwner_userIsOwner_succeeds() {
            // When & Then
            assertThatCode(() -> 
                accessPolicy.requireOwner(user, ownerId)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireOwner: when user is not owner then throws ForbiddenException")
        void requireOwner_userIsNotOwner_throwsForbiddenException() {
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireOwner(user, differentOwnerId)
            )
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("Owner required");
        }
        
        @Test
        @DisplayName("requireOwner: when ownerId is null then throws ForbiddenException")
        void requireOwner_ownerIdNull_throwsForbiddenException() {
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireOwner(user, null)
            )
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("Owner required");
        }
        
        @Test
        @DisplayName("requireOwner: when user is null then throws ForbiddenException")
        void requireOwner_userNull_throwsForbiddenException() {
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireOwner(null, ownerId)
            )
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("Owner required");
        }
    }
    
    // =============== requireAny Tests ===============
    
    @Nested
    @DisplayName("requireAny Tests")
    class RequireAnyTests {
        
        @Test
        @DisplayName("requireAny: when user has first permission then succeeds")
        void requireAny_hasFirstPermission_succeeds() {
            // Given
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(true);
            
            // When & Then
            assertThatCode(() -> 
                accessPolicy.requireAny(user, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireAny: when user has second permission then succeeds")
        void requireAny_hasSecondPermission_succeeds() {
            // Given
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(false);
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            
            // When & Then
            assertThatCode(() -> 
                accessPolicy.requireAny(user, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireAny: when user has no permissions then throws ForbiddenException")
        void requireAny_hasNoPermissions_throwsForbiddenException() {
            // Given
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(false);
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireAny(user, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN)
            )
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("Required permission missing");
        }
        
        @Test
        @DisplayName("requireAny: when user is null then throws ForbiddenException")
        void requireAny_userNull_throwsForbiddenException() {
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireAny(null, PermissionName.QUIZ_MODERATE)
            )
            .isInstanceOf(ForbiddenException.class);
        }
    }
    
    // =============== requireOwnerOrAny Tests ===============
    
    @Nested
    @DisplayName("requireOwnerOrAny Tests")
    class RequireOwnerOrAnyTests {
        
        @Test
        @DisplayName("requireOwnerOrAny: when user is owner then succeeds (no permission check needed)")
        void requireOwnerOrAny_userIsOwner_succeeds() {
            // When & Then - Should not even check permissions
            assertThatCode(() -> 
                accessPolicy.requireOwnerOrAny(user, ownerId, 
                    PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireOwnerOrAny: when user is not owner but has moderate permission then succeeds")
        void requireOwnerOrAny_notOwnerButHasModeratePermission_succeeds() {
            // Given
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(true);
            
            // When & Then
            assertThatCode(() -> 
                accessPolicy.requireOwnerOrAny(user, differentOwnerId, 
                    PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireOwnerOrAny: when user is not owner but has admin permission then succeeds")
        void requireOwnerOrAny_notOwnerButHasAdminPermission_succeeds() {
            // Given
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(false);
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            
            // When & Then
            assertThatCode(() -> 
                accessPolicy.requireOwnerOrAny(user, differentOwnerId, 
                    PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireOwnerOrAny: when user is not owner and has no permissions then throws ForbiddenException")
        void requireOwnerOrAny_notOwnerAndNoPermissions_throwsForbiddenException() {
            // Given
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(false);
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireOwnerOrAny(user, differentOwnerId, 
                    PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN)
            )
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("Owner or elevated permission required");
        }
        
        @Test
        @DisplayName("requireOwnerOrAny: when ownerId is null and user has no permissions then throws ForbiddenException")
        void requireOwnerOrAny_ownerIdNullAndNoPermissions_throwsForbiddenException() {
            // Given
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(false);
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireOwnerOrAny(user, null, 
                    PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN)
            )
            .isInstanceOf(ForbiddenException.class);
        }
        
        @Test
        @DisplayName("requireOwnerOrAny: when ownerId is null but user has permission then succeeds")
        void requireOwnerOrAny_ownerIdNullButHasPermission_succeeds() {
            // Given
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(true);
            
            // When & Then
            assertThatCode(() -> 
                accessPolicy.requireOwnerOrAny(user, null, 
                    PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireOwnerOrAny: when user is null then throws ForbiddenException")
        void requireOwnerOrAny_userNull_throwsForbiddenException() {
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireOwnerOrAny(null, ownerId, 
                    PermissionName.QUIZ_MODERATE)
            )
            .isInstanceOf(ForbiddenException.class);
        }
    }
    
    // =============== Future Multi-Tenant Methods Tests ===============
    
    @Nested
    @DisplayName("Multi-Tenant requireOwnerOrMemberOrAny Tests (Future Ready)")
    class RequireOwnerOrMemberOrAnyTests {
        
        @Test
        @DisplayName("requireOwnerOrMemberOrAny: when user owns USER-type resource then succeeds")
        void requireOwnerOrMemberOrAny_ownerRefUser_succeeds() {
            // Given
            OwnerRef ownerRef = OwnerRef.user(ownerId);
            
            // When & Then
            assertThatCode(() -> 
                accessPolicy.requireOwnerOrMemberOrAny(user, ownerRef, 
                    PermissionName.QUIZ_MODERATE)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireOwnerOrMemberOrAny: when resource owned by GROUP and user is not member then throws")
        void requireOwnerOrMemberOrAny_ownerRefGroupNotMember_throwsForbiddenException() {
            // Given
            UUID groupId = UUID.randomUUID();
            OwnerRef ownerRef = OwnerRef.group(groupId);
            
            when(membershipResolver.isMemberOfGroup(user.getId(), groupId))
                .thenReturn(false);
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE))
                .thenReturn(false);
            
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireOwnerOrMemberOrAny(user, ownerRef, 
                    PermissionName.QUIZ_MODERATE)
            )
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("Owner/member or elevated permission required");
        }
        
        @Test
        @DisplayName("requireOwnerOrMemberOrAny: when resource owned by GROUP and user is member then succeeds")
        void requireOwnerOrMemberOrAny_ownerRefGroupIsMember_succeeds() {
            // Given
            UUID groupId = UUID.randomUUID();
            OwnerRef ownerRef = OwnerRef.group(groupId);
            
            when(membershipResolver.isMemberOfGroup(user.getId(), groupId))
                .thenReturn(true);
            
            // When & Then
            assertThatCode(() -> 
                accessPolicy.requireOwnerOrMemberOrAny(user, ownerRef, 
                    PermissionName.QUIZ_MODERATE)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireOwnerOrMemberOrAny: when resource owned by ORGANIZATION and user is member then succeeds")
        void requireOwnerOrMemberOrAny_ownerRefOrgIsMember_succeeds() {
            // Given
            UUID orgId = UUID.randomUUID();
            OwnerRef ownerRef = OwnerRef.organization(orgId);
            
            when(membershipResolver.isMemberOfOrganization(user.getId(), orgId))
                .thenReturn(true);
            
            // When & Then
            assertThatCode(() -> 
                accessPolicy.requireOwnerOrMemberOrAny(user, ownerRef, 
                    PermissionName.QUIZ_MODERATE)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireOwnerOrMemberOrAny: when not member but has permission then succeeds")
        void requireOwnerOrMemberOrAny_notMemberButHasPermission_succeeds() {
            // Given
            UUID groupId = UUID.randomUUID();
            OwnerRef ownerRef = OwnerRef.group(groupId);
            
            when(membershipResolver.isMemberOfGroup(user.getId(), groupId))
                .thenReturn(false);
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            
            // When & Then - Admin can access even without membership
            assertThatCode(() -> 
                accessPolicy.requireOwnerOrMemberOrAny(user, ownerRef, 
                    PermissionName.QUIZ_ADMIN)
            ).doesNotThrowAnyException();
        }
    }
    
    // =============== requireSameOrganization Tests ===============
    
    @Nested
    @DisplayName("requireSameOrganization Tests (Future Ready)")
    class RequireSameOrganizationTests {
        
        @Test
        @DisplayName("requireSameOrganization: when user is member then succeeds")
        void requireSameOrganization_isMember_succeeds() {
            // Given
            UUID orgId = UUID.randomUUID();
            when(membershipResolver.isMemberOfOrganization(user.getId(), orgId))
                .thenReturn(true);
            
            // When & Then
            assertThatCode(() -> 
                accessPolicy.requireSameOrganization(user, orgId)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireSameOrganization: when user is not member then throws ForbiddenException")
        void requireSameOrganization_notMember_throwsForbiddenException() {
            // Given
            UUID orgId = UUID.randomUUID();
            when(membershipResolver.isMemberOfOrganization(user.getId(), orgId))
                .thenReturn(false);
            
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireSameOrganization(user, orgId)
            )
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("Organization membership required");
        }
        
        @Test
        @DisplayName("requireSameOrganization: when orgId is null then throws ForbiddenException")
        void requireSameOrganization_orgIdNull_throwsForbiddenException() {
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireSameOrganization(user, null)
            )
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("Organization scope is required");
        }
    }
    
    // =============== requireOrganizationRoleOrAny Tests ===============
    
    @Nested
    @DisplayName("requireOrganizationRoleOrAny Tests (Future Ready)")
    class RequireOrganizationRoleOrAnyTests {
        
        @Test
        @DisplayName("requireOrganizationRoleOrAny: when user has org role then succeeds")
        void requireOrganizationRoleOrAny_hasOrgRole_succeeds() {
            // Given
            UUID orgId = UUID.randomUUID();
            when(membershipResolver.hasOrganizationRole(user.getId(), orgId, "ADMIN"))
                .thenReturn(true);
            
            // When & Then
            assertThatCode(() -> 
                accessPolicy.requireOrganizationRoleOrAny(user, orgId, 
                    new String[]{"ADMIN"}, 
                    PermissionName.QUIZ_ADMIN)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireOrganizationRoleOrAny: when user has global permission then succeeds")
        void requireOrganizationRoleOrAny_hasGlobalPermission_succeeds() {
            // Given
            UUID orgId = UUID.randomUUID();
            when(membershipResolver.hasOrganizationRole(user.getId(), orgId, "ADMIN"))
                .thenReturn(false);
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            
            // When & Then
            assertThatCode(() -> 
                accessPolicy.requireOrganizationRoleOrAny(user, orgId, 
                    new String[]{"ADMIN"}, 
                    PermissionName.QUIZ_ADMIN)
            ).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("requireOrganizationRoleOrAny: when user has neither org role nor permission then throws")
        void requireOrganizationRoleOrAny_hasNeitherOrgRoleNorPermission_throwsForbiddenException() {
            // Given
            UUID orgId = UUID.randomUUID();
            when(membershipResolver.hasOrganizationRole(user.getId(), orgId, "ADMIN"))
                .thenReturn(false);
            when(permissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireOrganizationRoleOrAny(user, orgId, 
                    new String[]{"ADMIN"}, 
                    PermissionName.QUIZ_ADMIN)
            )
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("Organization role or elevated permission required");
        }
    }
    
    // =============== Edge Cases and Defensive Programming Tests ===============
    
    @Nested
    @DisplayName("Edge Cases and Defensive Programming")
    class EdgeCaseTests {
        
        @Test
        @DisplayName("requireOwnerOrAny: handles empty permissions array gracefully")
        void requireOwnerOrAny_emptyPermissions_checksOwnershipOnly() {
            // When user is owner with no permissions to check
            assertThatCode(() -> 
                accessPolicy.requireOwnerOrAny(user, ownerId)
            ).doesNotThrowAnyException();
            
            // When user is not owner with no permissions to check
            assertThatThrownBy(() -> 
                accessPolicy.requireOwnerOrAny(user, differentOwnerId)
            ).isInstanceOf(ForbiddenException.class);
        }
        
        @Test
        @DisplayName("hasAny: handles many permissions efficiently")
        void hasAny_manyPermissions_checksAll() {
            // Given - User has the last permission in a long list
            when(permissionEvaluator.hasPermission(eq(user), any(PermissionName.class)))
                .thenReturn(false);
            when(permissionEvaluator.hasPermission(user, PermissionName.USER_DELETE))
                .thenReturn(true);
            
            // When
            boolean result = accessPolicy.hasAny(user, 
                PermissionName.QUIZ_CREATE,
                PermissionName.QUIZ_UPDATE,
                PermissionName.QUIZ_DELETE,
                PermissionName.QUIZ_MODERATE,
                PermissionName.USER_DELETE);
            
            // Then
            assertThat(result).isTrue();
        }
        
        @Test
        @DisplayName("requireOwner: error message is descriptive")
        void requireOwner_errorMessageIsDescriptive() {
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireOwner(user, differentOwnerId)
            )
            .isInstanceOf(ForbiddenException.class)
            .hasMessage("Owner required");
        }
        
        @Test
        @DisplayName("requireOwnerOrMemberOrAny: when ownerRef is null then throws")
        void requireOwnerOrMemberOrAny_ownerRefNull_throwsForbiddenException() {
            // When & Then
            assertThatThrownBy(() -> 
                accessPolicy.requireOwnerOrMemberOrAny(user, null, 
                    PermissionName.QUIZ_MODERATE)
            )
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("Owner reference missing");
        }
    }
}
