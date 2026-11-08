package com.example.hms.payload.dto.discharge;

import com.example.hms.controller.DischargeApprovalController;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1372 (round 31): {@code POST /discharge-approvals/{id}/reject {}} named
 * the doctor ids, and the rejection reason only on the next request (a
 * service check). Each path's own required field is now named at the edge,
 * with the groups the controller handler declares.
 */
class DischargeApprovalDecisionDTOValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /** The validation groups the controller handler applies to its decision body. */
    private static Class<?>[] groupsOf(String handler) throws NoSuchMethodException {
        Method method = DischargeApprovalController.class.getMethod(handler, UUID.class,
            DischargeApprovalDecisionDTO.class);
        for (Parameter parameter : method.getParameters()) {
            Validated validated = parameter.getAnnotation(Validated.class);
            if (validated != null) {
                return validated.value();
            }
        }
        return new Class<?>[0];
    }

    private static Set<String> invalidFields(DischargeApprovalDecisionDTO body, Class<?>... groups) {
        Set<ConstraintViolation<DischargeApprovalDecisionDTO>> violations = validator.validate(body, groups);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void d1372_rejectNamesTheReasonWithTheDoctorIds() throws Exception {
        Class<?>[] reject = groupsOf("reject");
        assertThat(invalidFields(new DischargeApprovalDecisionDTO(), reject))
            .containsExactlyInAnyOrder("doctorStaffId", "doctorAssignmentId", "rejectionReason");
        DischargeApprovalDecisionDTO blank = DischargeApprovalDecisionDTO.builder()
            .doctorStaffId(UUID.randomUUID()).doctorAssignmentId(UUID.randomUUID()).rejectionReason("  ").build();
        assertThat(invalidFields(blank, reject)).containsExactly("rejectionReason");
        // The approve path never asks for a rejection reason.
        assertThat(invalidFields(new DischargeApprovalDecisionDTO(), groupsOf("approve")))
            .doesNotContain("rejectionReason");
    }

    /** QA D1372 class: the approve path's discharge diagnosis is named with the doctor ids too. */
    @Test
    void d1372_approveNamesTheDiagnosisWithTheDoctorIds() throws Exception {
        Class<?>[] approve = groupsOf("approve");
        assertThat(invalidFields(new DischargeApprovalDecisionDTO(), approve))
            .containsExactlyInAnyOrder("doctorStaffId", "doctorAssignmentId", "dischargeDiagnosis");
        assertThat(invalidFields(new DischargeApprovalDecisionDTO(), groupsOf("reject")))
            .doesNotContain("dischargeDiagnosis");
    }
}
