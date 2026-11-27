package com.example.hms.service;

import com.example.hms.enums.FacilityType;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabFacilityAccessTest {
    @AfterEach
    void clear() { HospitalContextHolder.clear(); }

    @Test
    void liveAssignmentsNeverTransferALabRoleToAnotherPermittedHospital() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        HospitalContextHolder.setContext(ActingScopeResolver.liveContext(user, "staff", List.of(
            new TenantRoleAssignment(a, null, "LAB_SCIENTIST", "Scientist", true, FacilityType.HOSPITAL),
            new TenantRoleAssignment(b, null, "RECEPTIONIST", "Receptionist", true, FacilityType.HOSPITAL))));
        assertThatCode(() -> LabFacilityAccess.require(a, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> LabFacilityAccess.require(b, false)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> LabFacilityAccess.require(a, true)).isInstanceOf(AccessDeniedException.class);
    }
}
