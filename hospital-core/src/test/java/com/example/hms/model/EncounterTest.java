package com.example.hms.model;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.model.encounter.EncounterNote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class EncounterTest {

    private Hospital hospital;
    private Staff staff;
    private Patient patient;
    private UserRoleHospitalAssignment assignment;
    private UUID hospitalId;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setHospital(hospital);

        patient = new Patient();
        patient.setId(UUID.randomUUID());

        assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);
    }

    private Encounter validEncounter() {
        return Encounter.builder()
                .patient(patient)
                .staff(staff)
                .hospital(hospital)
                .assignment(assignment)
                .encounterType(EncounterType.CONSULTATION)
                .encounterDate(LocalDateTime.of(2025, 6, 15, 10, 30))
                .code("ENC-TEST-001")
                .build();
    }

    // ─── No-arg constructor ──────────────────────────────────────

    @Test
    void noArgConstructor() {
        Encounter e = new Encounter();
        assertThat(e.getPatient()).isNull();
        assertThat(e.getStaff()).isNull();
        assertThat(e.getHospital()).isNull();
        assertThat(e.getEncounterType()).isNull();
        assertThat(e.getEncounterDate()).isNull();
        assertThat(e.getCode()).isNull();
        assertThat(e.getNotes()).isNull();
        assertThat(e.getVersion()).isNull();
    }

    // ─── Builder defaults ────────────────────────────────────────

    @Test
    void builderDefaults() {
        Encounter e = Encounter.builder().build();
        assertThat(e.getStatus()).isIn(null, EncounterStatus.IN_PROGRESS);
        assertThat(e.getEncounterTreatments()).isNotNull().isEmpty();
    }

    // ─── All-args constructor ────────────────────────────────────

    @Test
    void allArgsConstructor() {
        LocalDateTime date = LocalDateTime.now();
        Encounter e = new Encounter(
                patient, new HashSet<>(), null, staff, hospital,
                EncounterType.EMERGENCY, null, null, date,
                EncounterStatus.COMPLETED, "notes", "CODE-1",
                "admin", "admin2", null, assignment, 1L
        );
        assertThat(e.getPatient()).isEqualTo(patient);
        assertThat(e.getStaff()).isEqualTo(staff);
        assertThat(e.getHospital()).isEqualTo(hospital);
        assertThat(e.getEncounterType()).isEqualTo(EncounterType.EMERGENCY);
        assertThat(e.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
        assertThat(e.getNotes()).isEqualTo("notes");
        assertThat(e.getCode()).isEqualTo("CODE-1");
        assertThat(e.getCreatedBy()).isEqualTo("admin");
        assertThat(e.getUpdatedBy()).isEqualTo("admin2");
        assertThat(e.getVersion()).isEqualTo(1L);
    }

    // ─── Getters/Setters ─────────────────────────────────────────

    @Test
    void setAndGetPatient() {
        Encounter e = new Encounter();
        e.setPatient(patient);
        assertThat(e.getPatient()).isEqualTo(patient);
    }

    @Test
    void setAndGetStaff() {
        Encounter e = new Encounter();
        e.setStaff(staff);
        assertThat(e.getStaff()).isEqualTo(staff);
    }

    @Test
    void setAndGetHospital() {
        Encounter e = new Encounter();
        e.setHospital(hospital);
        assertThat(e.getHospital()).isEqualTo(hospital);
    }

    @Test
    void setAndGetEncounterType() {
        Encounter e = new Encounter();
        e.setEncounterType(EncounterType.LAB);
        assertThat(e.getEncounterType()).isEqualTo(EncounterType.LAB);
    }

    @Test
    void setAndGetEncounterDate() {
        Encounter e = new Encounter();
        LocalDateTime dt = LocalDateTime.of(2025, 1, 1, 8, 0);
        e.setEncounterDate(dt);
        assertThat(e.getEncounterDate()).isEqualTo(dt);
    }

    @Test
    void setAndGetStatus() {
        Encounter e = new Encounter();
        e.setStatus(EncounterStatus.CANCELLED);
        assertThat(e.getStatus()).isEqualTo(EncounterStatus.CANCELLED);
    }

    @Test
    void setAndGetNotes() {
        Encounter e = new Encounter();
        e.setNotes("Patient stable");
        assertThat(e.getNotes()).isEqualTo("Patient stable");
    }

    @Test
    void setAndGetCode() {
        Encounter e = new Encounter();
        e.setCode("ENC-001");
        assertThat(e.getCode()).isEqualTo("ENC-001");
    }

    @Test
    void setAndGetCreatedBy() {
        Encounter e = new Encounter();
        e.setCreatedBy("admin");
        assertThat(e.getCreatedBy()).isEqualTo("admin");
    }

    @Test
    void setAndGetUpdatedBy() {
        Encounter e = new Encounter();
        e.setUpdatedBy("nurse");
        assertThat(e.getUpdatedBy()).isEqualTo("nurse");
    }

    @Test
    void setAndGetExtraFields() {
        Encounter e = new Encounter();
        Map<String, Object> extras = new HashMap<>();
        extras.put("key", "value");
        e.setExtraFields(extras);
        assertThat(e.getExtraFields()).containsEntry("key", "value");
    }

    @Test
    void setAndGetAppointment() {
        Encounter e = new Encounter();
        Appointment appt = new Appointment();
        e.setAppointment(appt);
        assertThat(e.getAppointment()).isEqualTo(appt);
    }

    @Test
    void setAndGetDepartment() {
        Encounter e = new Encounter();
        Department dept = new Department();
        e.setDepartment(dept);
        assertThat(e.getDepartment()).isEqualTo(dept);
    }

    @Test
    void setAndGetAssignment() {
        Encounter e = new Encounter();
        e.setAssignment(assignment);
        assertThat(e.getAssignment()).isEqualTo(assignment);
    }

    @Test
    void setAndGetVersion() {
        Encounter e = new Encounter();
        e.setVersion(5L);
        assertThat(e.getVersion()).isEqualTo(5L);
    }

    @Test
    void setAndGetEncounterTreatments() {
        Encounter e = new Encounter();
        HashSet<EncounterTreatment> treatments = new HashSet<>();
        e.setEncounterTreatments(treatments);
        assertThat(e.getEncounterTreatments()).isSameAs(treatments);
    }

    @Test
    void setAndGetEncounterNoteField() {
        Encounter e = new Encounter();
        EncounterNote note = new EncounterNote();
        e.setEncounterNote(note);
        assertThat(e.getEncounterNote()).isEqualTo(note);
    }

    // ─── generateEncounterCode ───────────────────────────────────

    @Test
    void generateEncounterCodeFormat() {
        Encounter e = new Encounter();
        String code = e.generateEncounterCode();
        assertThat(code).startsWith("ENC-").hasSize(19); // "ENC-" + 8 digit date + "-" + 6 char suffix
    }

    @Test
    void generateEncounterCodeUnique() {
        Encounter e = new Encounter();
        String code1 = e.generateEncounterCode();
        String code2 = e.generateEncounterCode();
        assertThat(code1).isNotEqualTo(code2);
    }

    // ─── Transient helpers ───────────────────────────────────────

    @Test
    void getDescriptionReturnsNotes() {
        Encounter e = new Encounter();
        e.setNotes("Some notes");
        assertThat(e.getDescription()).isEqualTo("Some notes");
    }

    @Test
    void getDescriptionNullWhenNotesNull() {
        Encounter e = new Encounter();
        assertThat(e.getDescription()).isNull();
    }

    @Test
    void getTypeReturnsEncounterType() {
        Encounter e = new Encounter();
        e.setEncounterType(EncounterType.SURGERY);
        assertThat(e.getType()).isEqualTo(EncounterType.SURGERY);
    }

    @Test
    void getTypeNullWhenNotSet() {
        Encounter e = new Encounter();
        assertThat(e.getType()).isNull();
    }

    @Test
    void getEncounterTimeFromDate() {
        Encounter e = new Encounter();
        LocalDateTime dt = LocalDateTime.of(2025, 3, 10, 14, 45);
        e.setEncounterDate(dt);
        assertThat(e.getEncounterTime()).isEqualTo(LocalTime.of(14, 45));
    }

    @Test
    void getEncounterTimeNullWhenDateNull() {
        Encounter e = new Encounter();
        assertThat(e.getEncounterTime()).isNull();
    }

    // ─── setEncounterNote helper ─────────────────────────────────

    @Test
    void setEncounterNoteSetsBackreferences() {
        Encounter e = validEncounter();
        EncounterNote note = new EncounterNote();

        e.setEncounterNote(note);

        assertThat(e.getEncounterNote()).isEqualTo(note);
        assertThat(note.getEncounter()).isEqualTo(e);
        assertThat(note.getPatient()).isEqualTo(patient);
        assertThat(note.getHospital()).isEqualTo(hospital);
    }

    @Test
    void setEncounterNoteWithNullPatientAndHospital() {
        Encounter e = new Encounter();
        EncounterNote note = new EncounterNote();

        e.setEncounterNote(note);

        assertThat(note.getEncounter()).isEqualTo(e);
        assertThat(note.getPatient()).isNull();
        assertThat(note.getHospital()).isNull();
    }

    @Test
    void setEncounterNoteNull() {
        Encounter e = new Encounter();
        e.setEncounterNote(null);
        assertThat(e.getEncounterNote()).isNull();
    }

    @Test
    void setEncounterNoteWithPatientNoHospital() {
        Encounter e = new Encounter();
        e.setPatient(patient);
        EncounterNote note = new EncounterNote();

        e.setEncounterNote(note);

        assertThat(note.getPatient()).isEqualTo(patient);
        assertThat(note.getHospital()).isNull();
    }

    @Test
    void setEncounterNoteWithHospitalNoPatient() {
        Encounter e = new Encounter();
        e.setHospital(hospital);
        EncounterNote note = new EncounterNote();

        e.setEncounterNote(note);

        assertThat(note.getPatient()).isNull();
        assertThat(note.getHospital()).isEqualTo(hospital);
    }

    // ─── toString ────────────────────────────────────────────────

    @Test
    void toStringExcludesRelations() {
        Encounter e = validEncounter();
        String s = e.toString();
        assertThat(s).contains("Encounter")
            .doesNotContain("patient=")
            .doesNotContain("staff=")
            .doesNotContain("hospital=");
    }

    // ─── validate (@PrePersist/@PreUpdate) ───────────────────────

    @Nested
    class Validate {

        private void invokeValidate(Encounter encounter) throws Exception {
            Method m = Encounter.class.getDeclaredMethod("validate");
            m.setAccessible(true);
            try {
                m.invoke(encounter);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        }

        @Test
        void happyPathValidates() throws Exception {
            Encounter e = validEncounter();
            invokeValidate(e);
            // No exception
            assertThat(e.getEncounterDate()).isNotNull();
        }

        @Test
        void setsEncounterDateIfNull() throws Exception {
            Encounter e = validEncounter();
            e.setEncounterDate(null);
            invokeValidate(e);
            assertThat(e.getEncounterDate()).isNotNull();
        }

        @Test
        void setsStatusIfNull() throws Exception {
            Encounter e = validEncounter();
            e.setStatus(null);
            invokeValidate(e);
            assertThat(e.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
        }

        // ── Staff hospital mismatch ──────────────────────────────

        @Test
        void staffNullThrows() {
            Encounter e = validEncounter();
            e.setStaff(null);
            assertThatThrownBy(() -> invokeValidate(e))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("staff must belong to encounter.hospital");
        }

        @Test
        void staffHospitalNullThrows() {
            Encounter e = validEncounter();
            Staff s = new Staff();
            s.setHospital(null);
            e.setStaff(s);
            assertThatThrownBy(() -> invokeValidate(e))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("staff must belong to encounter.hospital");
        }

        @Test
        void hospitalNullThrows() {
            Encounter e = validEncounter();
            e.setHospital(null);
            assertThatThrownBy(() -> invokeValidate(e))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("staff must belong to encounter.hospital");
        }

        @Test
        void staffHospitalIdMismatchThrows() {
            Encounter e = validEncounter();
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            Staff otherStaff = new Staff();
            otherStaff.setHospital(other);
            e.setStaff(otherStaff);
            assertThatThrownBy(() -> invokeValidate(e))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("staff must belong to encounter.hospital");
        }

        // ── Assignment hospital mismatch ─────────────────────────

        @Test
        void assignmentNullThrows() {
            Encounter e = validEncounter();
            e.setAssignment(null);
            assertThatThrownBy(() -> invokeValidate(e))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("assignment.hospital must match encounter.hospital");
        }

        @Test
        void assignmentHospitalNullThrows() {
            Encounter e = validEncounter();
            UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
            a.setHospital(null);
            e.setAssignment(a);
            assertThatThrownBy(() -> invokeValidate(e))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("assignment.hospital must match encounter.hospital");
        }

        @Test
        void assignmentHospitalIdMismatchThrows() {
            Encounter e = validEncounter();
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
            a.setHospital(other);
            e.setAssignment(a);
            assertThatThrownBy(() -> invokeValidate(e))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("assignment.hospital must match encounter.hospital");
        }

        // ── Department hospital mismatch ─────────────────────────

        @Test
        void departmentNullPasses() {
            Encounter e = validEncounter();
            e.setDepartment(null);
            assertThatNoException().isThrownBy(() -> invokeValidate(e));
        }

        @Test
        void departmentSameHospitalPasses() {
            Encounter e = validEncounter();
            Department dept = new Department();
            dept.setHospital(hospital);
            e.setDepartment(dept);
            assertThatNoException().isThrownBy(() -> invokeValidate(e));
        }

        @Test
        void departmentHospitalNullPasses() {
            Encounter e = validEncounter();
            Department dept = new Department();
            dept.setHospital(null);
            e.setDepartment(dept);
            assertThatNoException().isThrownBy(() -> invokeValidate(e));
        }

        @Test
        void departmentHospitalMismatchThrows() {
            Encounter e = validEncounter();
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            Department dept = new Department();
            dept.setHospital(other);
            e.setDepartment(dept);
            assertThatThrownBy(() -> invokeValidate(e))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("department must belong to encounter.hospital");
        }

        // ── Appointment validation ───────────────────────────────

        @Test
        void appointmentNullPasses() {
            Encounter e = validEncounter();
            e.setAppointment(null);
            assertThatNoException().isThrownBy(() -> invokeValidate(e));
        }

        @Test
        void appointmentMatchesPasses() {
            Encounter e = validEncounter();
            Appointment appt = new Appointment();
            appt.setHospital(hospital);
            appt.setPatient(patient);
            appt.setStaff(staff);
            e.setAppointment(appt);
            assertThatNoException().isThrownBy(() -> invokeValidate(e));
        }

        @Test
        void appointmentHospitalMismatchThrows() {
            Encounter e = validEncounter();
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            Appointment appt = new Appointment();
            appt.setHospital(other);
            appt.setPatient(patient);
            appt.setStaff(staff);
            e.setAppointment(appt);
            assertThatThrownBy(() -> invokeValidate(e))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("appointment.hospital must match encounter.hospital");
        }

        @Test
        void appointmentPatientMismatchThrows() {
            Encounter e = validEncounter();
            Patient otherPatient = new Patient();
            otherPatient.setId(UUID.randomUUID());
            Appointment appt = new Appointment();
            appt.setHospital(hospital);
            appt.setPatient(otherPatient);
            appt.setStaff(staff);
            e.setAppointment(appt);
            assertThatThrownBy(() -> invokeValidate(e))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("appointment.patient must match encounter.patient");
        }

        @Test
        void appointmentStaffMismatchThrows() {
            Encounter e = validEncounter();
            Staff otherStaff = new Staff();
            otherStaff.setId(UUID.randomUUID());
            otherStaff.setHospital(hospital);
            Appointment appt = new Appointment();
            appt.setHospital(hospital);
            appt.setPatient(patient);
            appt.setStaff(otherStaff);
            e.setAppointment(appt);
            assertThatThrownBy(() -> invokeValidate(e))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("appointment.staff must match encounter.staff");
        }
    }
}
