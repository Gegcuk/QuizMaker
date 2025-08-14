package uk.gegc.quizmaker.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ValidationException;

import java.beans.Introspector;
import java.util.Objects;

public class DifferentFromValidator implements ConstraintValidator<DifferentFrom, Object> {
    
    private String field;
    private String notEqualTo;
    
    @Override
    public void initialize(DifferentFrom constraintAnnotation) {
        this.field = constraintAnnotation.field();
        this.notEqualTo = constraintAnnotation.notEqualTo();
    }
    
    @Override
    public boolean isValid(Object bean, ConstraintValidatorContext context) {
        if (bean == null) {
            return true; // Let other validations handle null
        }
        
        try {
            Object a = getFieldValue(bean, field);
            Object b = getFieldValue(bean, notEqualTo);
            
            // Only evaluate when both are present and non-blank
            // This prevents duplicate error messages with @NotBlank
            if (!(a instanceof String sa) || !(b instanceof String sb) ||
                sa.isBlank() || sb.isBlank()) {
                return true;
            }
            
            if (Objects.equals(sa, sb)) {
                // Attach the violation to the specific field for better UX
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                       .addPropertyNode(field)  // e.g., "newPassword"
                       .addConstraintViolation();
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            // Fail fast on misconfiguration rather than silently accepting
            throw new ValidationException(
                "Invalid fields for @DifferentFrom: '%s' and '%s' on %s"
                    .formatted(field, notEqualTo, bean.getClass().getSimpleName()), e);
        }
    }
    
    /**
     * Uses direct method access for records and property descriptors for other types.
     * This approach is more reliable for Java records.
     */
    private Object getFieldValue(Object bean, String propertyName) throws Exception {
        // For records, use direct method access (more reliable)
        if (bean.getClass().isRecord()) {
            try {
                var method = bean.getClass().getMethod(propertyName);
                return method.invoke(bean);
            } catch (NoSuchMethodException e) {
                throw new NoSuchFieldException(propertyName);
            }
        }
        
        // For other types, use property descriptors
        var beanInfo = Introspector.getBeanInfo(bean.getClass());
        for (var pd : beanInfo.getPropertyDescriptors()) {
            if (pd.getName().equals(propertyName) && pd.getReadMethod() != null) {
                return pd.getReadMethod().invoke(bean);
            }
        }
        
        throw new NoSuchFieldException(propertyName);
    }
} 