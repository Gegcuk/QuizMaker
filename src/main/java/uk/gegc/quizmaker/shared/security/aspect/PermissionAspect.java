package uk.gegc.quizmaker.shared.security.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.UnauthorizedException;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.security.annotation.RequirePermission;
import uk.gegc.quizmaker.shared.security.annotation.RequireResourceOwnership;
import uk.gegc.quizmaker.shared.security.annotation.RequireRole;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.UUID;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionAspect {

    private final AppPermissionEvaluator appPermissionEvaluator;

    @Before("@annotation(requirePermission)")
    public void checkPermission(JoinPoint joinPoint, RequirePermission requirePermission) {
        PermissionName[] requiredPermissions = requirePermission.value();
        RequirePermission.LogicalOperator operator = requirePermission.operator();

        boolean hasAccess = false;

        if (operator == RequirePermission.LogicalOperator.OR) {
            hasAccess = appPermissionEvaluator.hasAnyPermission(requiredPermissions);
        } else if (operator == RequirePermission.LogicalOperator.AND) {
            hasAccess = appPermissionEvaluator.hasAllPermissions(requiredPermissions);
        }

        if (!hasAccess) {
            log.warn("Access denied: User lacks required permissions. Required: {}, Operator: {}",
                    requiredPermissions, operator);
            throw new ForbiddenException("Insufficient permissions to access this resource");
        }
    }

    @Before("@annotation(requireRole)")
    public void checkRole(JoinPoint joinPoint, RequireRole requireRole) {
        RoleName[] requiredRoles = requireRole.value();
        RequireRole.LogicalOperator operator = requireRole.operator();

        boolean hasAccess = false;

        if (operator == RequireRole.LogicalOperator.OR) {
            hasAccess = appPermissionEvaluator.hasAnyRole(requiredRoles);
        } else if (operator == RequireRole.LogicalOperator.AND) {
            hasAccess = appPermissionEvaluator.hasAllRoles(requiredRoles);
        }

        if (!hasAccess) {
            log.warn("Access denied: User lacks required roles. Required: {}, Operator: {}",
                    requiredRoles, operator);
            throw new ForbiddenException("Insufficient roles to access this resource");
        }
    }

    @Before("@annotation(requireResourceOwnership)")
    public void checkResourceOwnership(JoinPoint joinPoint, RequireResourceOwnership requireResourceOwnership) {
        String resourceParam = requireResourceOwnership.resourceParam();
        String resourceType = requireResourceOwnership.resourceType();
        String ownerField = requireResourceOwnership.ownerField();

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        UUID resourceOwnerId = null;

        // Find the resource parameter
		for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getName().equals(resourceParam)) {
                Object resourceValue = args[i];

                if (resourceValue instanceof UUID) {
                    resourceOwnerId = (UUID) resourceValue;
                } else if (resourceValue != null) {
					// Try to extract owner ID from the resource object using a public or declared getter
					try {
						String getterName = "get" + capitalize(ownerField);
						Method ownerMethod = resolveAccessibleMethod(resourceValue.getClass(), getterName);
						if (ownerMethod != null) {
							ownerMethod.setAccessible(true);
							Object owner = ownerMethod.invoke(resourceValue);
							if (owner instanceof UUID) {
								resourceOwnerId = (UUID) owner;
							} else if (owner instanceof String) {
								try {
									resourceOwnerId = UUID.fromString((String) owner);
								} catch (IllegalArgumentException ignored) {
									// not a UUID string; ignore
								}
							}
						} else {
							log.warn("Could not extract owner ID from resource parameter: no accessible getter '{}' on {}",
									getterName, resourceValue.getClass().getName());
						}
					} catch (Exception e) {
						log.warn("Could not extract owner ID from resource parameter: {}", e.getMessage());
					}
                }
                break;
            }
        }

        if (resourceOwnerId == null) {
            log.warn("Could not determine resource owner for ownership check");
            throw new UnauthorizedException("Could not verify resource ownership");
        }

        if (!appPermissionEvaluator.isResourceOwner(resourceOwnerId)) {
            log.warn("Access denied: User is not the owner of the resource. Resource type: {}, Resource owner: {}",
                    resourceType, resourceOwnerId);
            throw new ForbiddenException("You can only access your own " + resourceType);
        }
    }

	private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

	private Method resolveAccessibleMethod(Class<?> type, String methodName) {
		// Try public method first
		try {
			return type.getMethod(methodName);
		} catch (NoSuchMethodException ignored) {
			// fall through
		}
		// Try declared methods up the hierarchy
		Class<?> current = type;
		while (current != null) {
			try {
				return current.getDeclaredMethod(methodName);
			} catch (NoSuchMethodException ignored) {
				current = current.getSuperclass();
			}
		}
		return null;
	}
} 