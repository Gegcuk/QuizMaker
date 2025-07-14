package uk.gegc.quizmaker.security.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.exception.ForbiddenException;
import uk.gegc.quizmaker.exception.UnauthorizedException;
import uk.gegc.quizmaker.model.user.PermissionName;
import uk.gegc.quizmaker.model.user.RoleName;
import uk.gegc.quizmaker.security.PermissionEvaluator;
import uk.gegc.quizmaker.security.annotation.RequirePermission;
import uk.gegc.quizmaker.security.annotation.RequireResourceOwnership;
import uk.gegc.quizmaker.security.annotation.RequireRole;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.UUID;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionAspect {
    
    private final PermissionEvaluator permissionEvaluator;
    
    @Before("@annotation(requirePermission)")
    public void checkPermission(JoinPoint joinPoint, RequirePermission requirePermission) {
        PermissionName[] requiredPermissions = requirePermission.value();
        RequirePermission.LogicalOperator operator = requirePermission.operator();
        
        boolean hasAccess = false;
        
        if (operator == RequirePermission.LogicalOperator.OR) {
            hasAccess = permissionEvaluator.hasAnyPermission(requiredPermissions);
        } else if (operator == RequirePermission.LogicalOperator.AND) {
            hasAccess = permissionEvaluator.hasAllPermissions(requiredPermissions);
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
            hasAccess = permissionEvaluator.hasAnyRole(requiredRoles);
        } else if (operator == RequireRole.LogicalOperator.AND) {
            hasAccess = permissionEvaluator.hasAllRoles(requiredRoles);
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
                    // Try to extract owner ID from the resource object
                    try {
                        Method ownerMethod = resourceValue.getClass().getMethod("get" + 
                                capitalize(ownerField));
                        Object owner = ownerMethod.invoke(resourceValue);
                        if (owner instanceof UUID) {
                            resourceOwnerId = (UUID) owner;
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
        
        if (!permissionEvaluator.isResourceOwner(resourceOwnerId)) {
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
} 