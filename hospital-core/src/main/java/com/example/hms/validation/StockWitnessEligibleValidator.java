package com.example.hms.validation;

import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.pharmacy.ControlledStockWitness;
import com.example.hms.utility.RoleValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;

/**
 * The {@link StockWitnessEligible} check: the same "not the caller" and "a live witness role at this hospital"
 * tests as {@code ControlledStockWitness#confirmWitness}, with the same messages. Without the repository or the
 * caller's scope (a test slice, a validator built by hand, an unpinned caller) it says nothing and the service
 * check decides.
 */
public class StockWitnessEligibleValidator implements ConstraintValidator<StockWitnessEligible, UUID> {

    private static final String NOT_SELF = "{stockTransaction.witness.notSelf}";

    private final ObjectProvider<UserRoleHospitalAssignmentRepository> assignments;
    private final ObjectProvider<RoleValidator> roles;

    /** Built by Spring's validator factory, with the beans when the context has them. */
    @org.springframework.beans.factory.annotation.Autowired
    public StockWitnessEligibleValidator(ObjectProvider<UserRoleHospitalAssignmentRepository> assignments,
                                         ObjectProvider<RoleValidator> roles) {
        this.assignments = assignments;
        this.roles = roles;
    }

    /** Built by a plain Bean Validation factory: no beans, so the service check decides. */
    public StockWitnessEligibleValidator() {
        this.assignments = null;
        this.roles = null;
    }

    @Override
    public boolean isValid(UUID witnessId, ConstraintValidatorContext context) {
        if (witnessId == null || assignments == null || roles == null) {
            return true;
        }
        RoleValidator roleValidator = roles.getIfAvailable();
        UserRoleHospitalAssignmentRepository repository = assignments.getIfAvailable();
        if (roleValidator == null || repository == null) {
            return true;
        }
        UUID self;
        UUID hospitalId;
        try {
            self = roleValidator.getCurrentUserId();
            hospitalId = roleValidator.getCurrentHospitalId();
        } catch (RuntimeException noScope) {
            return true;
        }
        if (witnessId.equals(self)) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(NOT_SELF).addConstraintViolation();
            return false;
        }
        return hospitalId == null
            || repository.existsActiveWitness(witnessId, hospitalId, ControlledStockWitness.WITNESS_ROLE_CODES);
    }
}
