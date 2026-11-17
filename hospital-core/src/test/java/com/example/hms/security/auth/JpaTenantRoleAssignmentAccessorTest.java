package com.example.hms.security.auth;

import com.example.hms.enums.FacilityType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The live assignment view carries the facility's RAW type (V180: NOT NULL).
 * A facility without one is refused, never read as "not a provider", so the
 * confinement fails closed.
 */
class JpaTenantRoleAssignmentAccessorTest {

    private final UserRoleHospitalAssignmentRepository repository = mock(UserRoleHospitalAssignmentRepository.class);
    private final JpaTenantRoleAssignmentAccessor accessor = new JpaTenantRoleAssignmentAccessor(repository);
    private final UUID userId = UUID.randomUUID();

    private UserRoleHospitalAssignment at(FacilityType type) {
        Hospital hospital = Hospital.builder().name("Facility").build();
        hospital.setId(UUID.randomUUID());
        hospital.setFacilityType(type);
        Role role = Role.builder().code("ROLE_PHARMACIST").name("PHARMACIST").build();
        return UserRoleHospitalAssignment.builder().hospital(hospital).role(role).active(true).build();
    }

    @Test
    @DisplayName("a pharmacy assignment carries PHARMACY")
    void carriesTheType() {
        when(repository.findAllDetailedByUserId(userId)).thenReturn(List.of(at(FacilityType.PHARMACY)));

        assertThat(accessor.findAssignmentsForUser(userId)).singleElement()
            .extracting(TenantRoleAssignment::facilityType).isEqualTo(FacilityType.PHARMACY);
    }

    @Test
    @DisplayName("a facility with no type is refused, not read as a hospital")
    void missingTypeFailsClosed() {
        when(repository.findAllDetailedByUserId(userId)).thenReturn(List.of(at(null)));

        assertThatThrownBy(() -> accessor.findAssignmentsForUser(userId))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
