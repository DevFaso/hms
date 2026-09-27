package com.example.hms.mapper;

import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Role;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The admin audit surface ({@code /audit-logs}, super-admin audit search)
 * reads role names through this mapper. It used to stamp the English sentence
 * "Unknown Role" and pass {@code ROLE_X} through, so a French clinician read
 * English and the portal had to normalise per call site.
 */
@DisplayName("AuditEventLogMapper role name")
class AuditEventLogMapperRoleTest {

    private final AuditEventLogMapper mapper = new AuditEventLogMapper(mock(PatientRepository.class));

    @ParameterizedTest(name = "stored \"{0}\" -> {1}")
    @CsvSource({
        "ROLE_DOCTOR, DOCTOR",
        "NURSE, NURSE",
        "Unknown Role, ",
    })
    @DisplayName("a legacy row's stored spelling reaches the client bare, or null")
    void legacyRowsAreNormalised(String stored, String expected) {
        AuditEventLog event = new AuditEventLog();
        event.setRoleName(stored);

        assertThat(mapper.toDtoLite(event).getRoleName()).isEqualTo(expected);
        assertThat(mapper.toDto(event).getRoleName()).isEqualTo(expected);
    }

    @Test
    @DisplayName("no resolvable role is null, not an English sentence")
    void unresolvableRoleIsNull() {
        assertThat(mapper.toDtoLite(new AuditEventLog()).getRoleName()).isNull();
    }

    @Test
    @DisplayName("a role taken from the assignment is bare too")
    void assignmentRoleIsBare() {
        Role role = new Role();
        role.setName("ROLE_MIDWIFE");
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setRole(role);
        AuditEventLog event = new AuditEventLog();
        event.setAssignment(assignment);

        assertThat(mapper.toDtoLite(event).getRoleName()).isEqualTo("MIDWIFE");
    }
}
