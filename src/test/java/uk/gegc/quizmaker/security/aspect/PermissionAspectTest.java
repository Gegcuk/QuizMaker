package uk.gegc.quizmaker.security.aspect;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.UnauthorizedException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.security.annotation.RequirePermission;
import uk.gegc.quizmaker.shared.security.annotation.RequireResourceOwnership;
import uk.gegc.quizmaker.shared.security.annotation.RequireRole;
import uk.gegc.quizmaker.shared.security.aspect.PermissionAspect;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionAspectTest {

    @Mock
    private AppPermissionEvaluator appPermissionEvaluator;

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    @InjectMocks
    private PermissionAspect permissionAspect;

    @Test
    @DisplayName("checkPermission: allows access when user has required permission (OR)")
    void checkPermission_allowsAccess_OR() {
        // Given
        RequirePermission annotation = mock(RequirePermission.class);
        when(annotation.value()).thenReturn(new PermissionName[]{PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE});
        when(annotation.operator()).thenReturn(RequirePermission.LogicalOperator.OR);

        when(appPermissionEvaluator.hasAnyPermission(PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE))
                .thenReturn(true);

        // When & Then
        assertDoesNotThrow(() -> permissionAspect.checkPermission(joinPoint, annotation));
        verify(appPermissionEvaluator).hasAnyPermission(PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE);
    }

    @Test
    @DisplayName("checkPermission: denies access when user lacks required permission (OR)")
    void checkPermission_deniesAccess_OR() {
        // Given
        RequirePermission annotation = mock(RequirePermission.class);
        when(annotation.value()).thenReturn(new PermissionName[]{PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE});
        when(annotation.operator()).thenReturn(RequirePermission.LogicalOperator.OR);

        when(appPermissionEvaluator.hasAnyPermission(PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE))
                .thenReturn(false);

        // When & Then
        assertThrows(ForbiddenException.class, () ->
                permissionAspect.checkPermission(joinPoint, annotation)
        );
        verify(appPermissionEvaluator).hasAnyPermission(PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE);
    }

    @Test
    @DisplayName("checkPermission: allows access when user has all required permissions (AND)")
    void checkPermission_allowsAccess_AND() {
        // Given
        RequirePermission annotation = mock(RequirePermission.class);
        when(annotation.value()).thenReturn(new PermissionName[]{PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE});
        when(annotation.operator()).thenReturn(RequirePermission.LogicalOperator.AND);

        when(appPermissionEvaluator.hasAllPermissions(PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE))
                .thenReturn(true);

        // When & Then
        assertDoesNotThrow(() -> permissionAspect.checkPermission(joinPoint, annotation));
        verify(appPermissionEvaluator).hasAllPermissions(PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE);
    }

    @Test
    @DisplayName("checkPermission: denies access when user lacks all required permissions (AND)")
    void checkPermission_deniesAccess_AND() {
        // Given
        RequirePermission annotation = mock(RequirePermission.class);
        when(annotation.value()).thenReturn(new PermissionName[]{PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE});
        when(annotation.operator()).thenReturn(RequirePermission.LogicalOperator.AND);

        when(appPermissionEvaluator.hasAllPermissions(PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE))
                .thenReturn(false);

        // When & Then
        assertThrows(ForbiddenException.class, () ->
                permissionAspect.checkPermission(joinPoint, annotation)
        );
        verify(appPermissionEvaluator).hasAllPermissions(PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE);
    }

    @Test
    @DisplayName("checkRole: allows access when user has required role (OR)")
    void checkRole_allowsAccess_OR() {
        // Given
        RequireRole annotation = mock(RequireRole.class);
        when(annotation.value()).thenReturn(new RoleName[]{RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR});
        when(annotation.operator()).thenReturn(RequireRole.LogicalOperator.OR);

        when(appPermissionEvaluator.hasAnyRole(RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR))
                .thenReturn(true);

        // When & Then
        assertDoesNotThrow(() -> permissionAspect.checkRole(joinPoint, annotation));
        verify(appPermissionEvaluator).hasAnyRole(RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR);
    }

    @Test
    @DisplayName("checkRole: denies access when user lacks required role (OR)")
    void checkRole_deniesAccess_OR() {
        // Given
        RequireRole annotation = mock(RequireRole.class);
        when(annotation.value()).thenReturn(new RoleName[]{RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR});
        when(annotation.operator()).thenReturn(RequireRole.LogicalOperator.OR);

        when(appPermissionEvaluator.hasAnyRole(RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR))
                .thenReturn(false);

        // When & Then
        assertThrows(ForbiddenException.class, () ->
                permissionAspect.checkRole(joinPoint, annotation)
        );
        verify(appPermissionEvaluator).hasAnyRole(RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR);
    }

    @Test
    @DisplayName("checkRole: allows access when user has all required roles (AND)")
    void checkRole_allowsAccess_AND() {
        // Given
        RequireRole annotation = mock(RequireRole.class);
        when(annotation.value()).thenReturn(new RoleName[]{RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR});
        when(annotation.operator()).thenReturn(RequireRole.LogicalOperator.AND);

        when(appPermissionEvaluator.hasAllRoles(RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR))
                .thenReturn(true);

        // When & Then
        assertDoesNotThrow(() -> permissionAspect.checkRole(joinPoint, annotation));
        verify(appPermissionEvaluator).hasAllRoles(RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR);
    }

    @Test
    @DisplayName("checkResourceOwnership: allows access when user owns resource (UUID parameter)")
    void checkResourceOwnership_allowsAccess_UUIDParam() throws Exception {
        // Given
        RequireResourceOwnership annotation = mock(RequireResourceOwnership.class);
        when(annotation.resourceParam()).thenReturn("userId");
        when(annotation.resourceType()).thenReturn("user");
        when(annotation.ownerField()).thenReturn("userId");

        UUID resourceOwnerId = UUID.randomUUID();

        Method method = TestController.class.getMethod("testMethod", UUID.class);
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[]{resourceOwnerId};

        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);

        when(appPermissionEvaluator.isResourceOwner(resourceOwnerId)).thenReturn(true);

        // When & Then
        assertDoesNotThrow(() -> permissionAspect.checkResourceOwnership(joinPoint, annotation));
        verify(appPermissionEvaluator).isResourceOwner(resourceOwnerId);
    }

    @Test
    @DisplayName("checkResourceOwnership: denies access when user doesn't own resource")
    void checkResourceOwnership_deniesAccess() throws Exception {
        // Given
        RequireResourceOwnership annotation = mock(RequireResourceOwnership.class);
        when(annotation.resourceParam()).thenReturn("userId");
        when(annotation.resourceType()).thenReturn("user");
        when(annotation.ownerField()).thenReturn("userId");

        UUID resourceOwnerId = UUID.randomUUID();

        Method method = TestController.class.getMethod("testMethod", UUID.class);
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[]{resourceOwnerId};

        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);

        when(appPermissionEvaluator.isResourceOwner(resourceOwnerId)).thenReturn(false);

        // When & Then
        assertThrows(ForbiddenException.class, () ->
                permissionAspect.checkResourceOwnership(joinPoint, annotation)
        );
        verify(appPermissionEvaluator).isResourceOwner(resourceOwnerId);
    }

    @Test
    @DisplayName("checkResourceOwnership: extracts owner ID from resource object")
    void checkResourceOwnership_extractsFromObject() throws Exception {
        // Given
        RequireResourceOwnership annotation = mock(RequireResourceOwnership.class);
        when(annotation.resourceParam()).thenReturn("resource");
        when(annotation.resourceType()).thenReturn("quiz");
        when(annotation.ownerField()).thenReturn("userId");

        UUID resourceOwnerId = UUID.randomUUID();
        TestResource resource = new TestResource(resourceOwnerId);

        Method method = TestController.class.getMethod("testMethodWithObject", TestResource.class);
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[]{resource};

        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);

        when(appPermissionEvaluator.isResourceOwner(resourceOwnerId)).thenReturn(true);

        // When & Then
        assertDoesNotThrow(() -> permissionAspect.checkResourceOwnership(joinPoint, annotation));
        verify(appPermissionEvaluator).isResourceOwner(resourceOwnerId);
    }

    @Test
    @DisplayName("checkResourceOwnership: throws exception when owner ID cannot be determined")
    void checkResourceOwnership_cannotDetermineOwner() throws Exception {
        // Given
        RequireResourceOwnership annotation = mock(RequireResourceOwnership.class);
        when(annotation.resourceParam()).thenReturn("resource");
        when(annotation.resourceType()).thenReturn("quiz");
        when(annotation.ownerField()).thenReturn("nonExistentField");

        TestResource resource = new TestResource(UUID.randomUUID());

        Method method = TestController.class.getMethod("testMethodWithObject", TestResource.class);
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[]{resource};

        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);

        // When & Then
        assertThrows(UnauthorizedException.class, () ->
                permissionAspect.checkResourceOwnership(joinPoint, annotation)
        );
    }

    // Test helper classes
    static class TestController {
        public void testMethod(UUID userId) {
        }

        public void testMethodWithObject(TestResource resource) {
        }
    }

    static class TestResource {
        private final UUID userId;

        TestResource(UUID userId) {
            this.userId = userId;
        }

        public UUID getUserId() {
            return userId;
        }
    }
} 