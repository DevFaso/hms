package com.example.hms.model;

import com.example.hms.enums.DischargeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DischargeApprovalTest {

    private Patient patient;
    private PatientHospitalRegistration registration;
    private Hospital hospital;
    private Staff nurse;
    private Staff doctor;
    private UserRoleHospitalAssignment nurseAssignment;
    private UserRoleHospitalAssignment doctorAssignment;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setId(UUID.randomUUID());

        registration = new PatientHospitalRegistration();
        registration.setId(UUID.randomUUID());

        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());

        nurse = new Staff();
        nurse.setId(UUID.randomUUID());

        doctor = new Staff();
        doctor.setId(UUID.randomUUID());

        nurseAssignment = new UserRoleHospitalAssignment();
        nurseAssignment.setId(UUID.randomUUID());

        doctorAssignment = new UserRoleHospitalAssignment();
        doctorAssignment.setId(UUID.randomUUID());
    }

    // ─── No-arg constructor ──────────────────────────────────────

    @Test
    void noArgConstructor() {
        DischargeApproval da = new DischargeApproval();
        assertThat(da.getId()).isNull();
        assertThat(da.getPatient()).isNull();
        assertThat(da.getRegistration()).isNull();
        assertThat(da.getHospital()).isNull();
        assertThat(da.getNurse()).isNull();
        assertThat(da.getDoctor()).isNull();
        assertThat(da.getNurseAssignment()).isNull();
        assertThat(da.getDoctorAssignment()).isNull();
        assertThat(da.getStatus()).isIn(null, DischargeStatus.PENDING);
        assertThat(da.getNurseSummary()).isNull();
        assertThat(da.getDoctorNote()).isNull();
        assertThat(da.getRejectionReason()).isNull();
        assertThat(da.getRequestedAt()).isNull();
        assertThat(da.getApprovedAt()).isNull();
        assertThat(da.getResolvedAt()).isNull();
        assertThat(da.getVersion()).isNull();
    }

    // ─── All-args constructor ────────────────────────────────────

    @Test
    void allArgsConstructor() {
        LocalDateTime now = LocalDateTime.now();
        DischargeApproval da = new DischargeApproval(
                patient, registration, hospital, nurse, doctor,
                nurseAssignment, doctorAssignment,
                DischargeStatus.APPROVED, "nurse summary", "doctor note",
                "rejection", now, now.plusHours(1), now.plusHours(2), 1L
        );

        assertThat(da.getPatient()).isEqualTo(patient);
        assertThat(da.getRegistration()).isEqualTo(registration);
        assertThat(da.getHospital()).isEqualTo(hospital);
        assertThat(da.getNurse()).isEqualTo(nurse);
        assertThat(da.getDoctor()).isEqualTo(doctor);
        assertThat(da.getNurseAssignment()).isEqualTo(nurseAssignment);
        assertThat(da.getDoctorAssignment()).isEqualTo(doctorAssignment);
        assertThat(da.getStatus()).isEqualTo(DischargeStatus.APPROVED);
        assertThat(da.getNurseSummary()).isEqualTo("nurse summary");
        assertThat(da.getDoctorNote()).isEqualTo("doctor note");
        assertThat(da.getRejectionReason()).isEqualTo("rejection");
        assertThat(da.getRequestedAt()).isEqualTo(now);
        assertThat(da.getApprovedAt()).isEqualTo(now.plusHours(1));
        assertThat(da.getResolvedAt()).isEqualTo(now.plusHours(2));
        assertThat(da.getVersion()).isEqualTo(1L);
    }

    // ─── Builder ─────────────────────────────────────────────────

    @Test
    void builderWithDefaults() {
        DischargeApproval da = DischargeApproval.builder()
                .patient(patient)
                .registration(registration)
                .hospital(hospital)
                .nurse(nurse)
                .nurseAssignment(nurseAssignment)
                .build();

        // @Builder.Default status = PENDING
        assertThat(da.getStatus()).isEqualTo(DischargeStatus.PENDING);
        assertThat(da.getPatient()).isEqualTo(patient);
    }

    @Test
    void builderMinimal() {
        DischargeApproval da = DischargeApproval.builder().build();
        assertThat(da.getStatus()).isEqualTo(DischargeStatus.PENDING);
        assertThat(da.getPatient()).isNull();
    }

    @Test
    void builderAllFields() {
        LocalDateTime now = LocalDateTime.now();
        DischargeApproval da = DischargeApproval.builder()
                .patient(patient)
                .registration(registration)
                .hospital(hospital)
                .nurse(nurse)
                .doctor(doctor)
                .nurseAssignment(nurseAssignment)
                .doctorAssignment(doctorAssignment)
                .status(DischargeStatus.REJECTED)
                .nurseSummary("summary")
                .doctorNote("note")
                .rejectionReason("bad reason")
                .requestedAt(now)
                .approvedAt(now.plusHours(1))
                .resolvedAt(now.plusHours(2))
                .version(5L)
                .build();

        assertThat(da.getStatus()).isEqualTo(DischargeStatus.REJECTED);
        assertThat(da.getNurseSummary()).isEqualTo("summary");
        assertThat(da.getDoctorNote()).isEqualTo("note");
        assertThat(da.getRejectionReason()).isEqualTo("bad reason");
        assertThat(da.getVersion()).isEqualTo(5L);
    }

    // ─── Getters / Setters ───────────────────────────────────────

    @Test
    void setAndGetPatient() {
        DischargeApproval da = new DischargeApproval();
        da.setPatient(patient);
        assertThat(da.getPatient()).isEqualTo(patient);
    }

    @Test
    void setAndGetRegistration() {
        DischargeApproval da = new DischargeApproval();
        da.setRegistration(registration);
        assertThat(da.getRegistration()).isEqualTo(registration);
    }

    @Test
    void setAndGetHospital() {
        DischargeApproval da = new DischargeApproval();
        da.setHospital(hospital);
        assertThat(da.getHospital()).isEqualTo(hospital);
    }

    @Test
    void setAndGetNurse() {
        DischargeApproval da = new DischargeApproval();
        da.setNurse(nurse);
        assertThat(da.getNurse()).isEqualTo(nurse);
    }

    @Test
    void setAndGetDoctor() {
        DischargeApproval da = new DischargeApproval();
        da.setDoctor(doctor);
        assertThat(da.getDoctor()).isEqualTo(doctor);
    }

    @Test
    void setAndGetNurseAssignment() {
        DischargeApproval da = new DischargeApproval();
        da.setNurseAssignment(nurseAssignment);
        assertThat(da.getNurseAssignment()).isEqualTo(nurseAssignment);
    }

    @Test
    void setAndGetDoctorAssignment() {
        DischargeApproval da = new DischargeApproval();
        da.setDoctorAssignment(doctorAssignment);
        assertThat(da.getDoctorAssignment()).isEqualTo(doctorAssignment);
    }

    @Test
    void setAndGetStatus() {
        DischargeApproval da = new DischargeApproval();
        da.setStatus(DischargeStatus.CANCELLED);
        assertThat(da.getStatus()).isEqualTo(DischargeStatus.CANCELLED);
    }

    @Test
    void setAndGetNurseSummary() {
        DischargeApproval da = new DischargeApproval();
        da.setNurseSummary("summary");
        assertThat(da.getNurseSummary()).isEqualTo("summary");
    }

    @Test
    void setAndGetDoctorNote() {
        DischargeApproval da = new DischargeApproval();
        da.setDoctorNote("note");
        assertThat(da.getDoctorNote()).isEqualTo("note");
    }

    @Test
    void setAndGetRejectionReason() {
        DischargeApproval da = new DischargeApproval();
        da.setRejectionReason("reason");
        assertThat(da.getRejectionReason()).isEqualTo("reason");
    }

    @Test
    void setAndGetRequestedAt() {
        DischargeApproval da = new DischargeApproval();
        LocalDateTime now = LocalDateTime.now();
        da.setRequestedAt(now);
        assertThat(da.getRequestedAt()).isEqualTo(now);
    }

    @Test
    void setAndGetApprovedAt() {
        DischargeApproval da = new DischargeApproval();
        LocalDateTime now = LocalDateTime.now();
        da.setApprovedAt(now);
        assertThat(da.getApprovedAt()).isEqualTo(now);
    }

    @Test
    void setAndGetResolvedAt() {
        DischargeApproval da = new DischargeApproval();
        LocalDateTime now = LocalDateTime.now();
        da.setResolvedAt(now);
        assertThat(da.getResolvedAt()).isEqualTo(now);
    }

    @Test
    void setAndGetVersion() {
        DischargeApproval da = new DischargeApproval();
        da.setVersion(10L);
        assertThat(da.getVersion()).isEqualTo(10L);
    }

    // ─── toString (excludes relations) ───────────────────────────

    @Test
    void toStringExcludesRelations() {
        DischargeApproval da = DischargeApproval.builder()
                .patient(patient)
                .registration(registration)
                .hospital(hospital)
                .nurse(nurse)
                .doctor(doctor)
                .nurseAssignment(nurseAssignment)
                .doctorAssignment(doctorAssignment)
                .status(DischargeStatus.APPROVED)
                .nurseSummary("summary")
                .build();

        String s = da.toString();
        assertThat(s).contains("status=APPROVED")
            .contains("nurseSummary=summary")
            .doesNotContain("patient=")
            .doesNotContain("registration=")
            .doesNotContain("hospital=")
            .doesNotContain("nurse=")
            .doesNotContain("doctor=")
            .doesNotContain("nurseAssignment=")
            .doesNotContain("doctorAssignment=");
    }

    // ─── equals / hashCode (@EqualsAndHashCode(callSuper=true)) ─

    @Test
    void equalsSameId() {
        UUID id = UUID.randomUUID();
        DischargeApproval a = DischargeApproval.builder().build();
        a.setId(id);
        DischargeApproval b = DischargeApproval.builder().build();
        b.setId(id);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void notEqualDifferentIds() {
        DischargeApproval a = DischargeApproval.builder().build();
        a.setId(UUID.randomUUID());
        DischargeApproval b = DischargeApproval.builder().build();
        b.setId(UUID.randomUUID());
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void notEqualToNull() {
        DischargeApproval da = DischargeApproval.builder().build();
        assertThat(da).isNotEqualTo(null);
    }

    @Test
    void notEqualToDifferentType() {
        DischargeApproval da = DischargeApproval.builder().build();
        assertThat(da).isNotEqualTo("string");
    }

    // ─── BaseEntity id ──────────────────────────────────────────

    @Test
    void idFromBaseEntity() {
        DischargeApproval da = new DischargeApproval();
        UUID id = UUID.randomUUID();
        da.setId(id);
        assertThat(da.getId()).isEqualTo(id);
    }

    // ─── @PrePersist — beforeSave ────────────────────────────────

    @Nested
    class BeforeSaveTests {

        private void invokeBeforeSave(DischargeApproval da) throws Exception {
            Method m = DischargeApproval.class.getDeclaredMethod("beforeSave");
            m.setAccessible(true);
            try {
                m.invoke(da);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw new RuntimeException(e.getCause());
            }
        }

        @Test
        void setsRequestedAtAndStatusWhenBothNull() throws Exception {
            DischargeApproval da = new DischargeApproval();
            da.setStatus(null); // explicitly null out the field-level default
            // Both null
            assertThat(da.getRequestedAt()).isNull();
            assertThat(da.getStatus()).isNull();

            invokeBeforeSave(da);

            assertThat(da.getRequestedAt()).isNotNull();
            assertThat(da.getStatus()).isEqualTo(DischargeStatus.PENDING);
        }

        @Test
        void doesNotOverrideExistingRequestedAt() throws Exception {
            LocalDateTime fixed = LocalDateTime.of(2025, 6, 1, 10, 0);
            DischargeApproval da = new DischargeApproval();
            da.setRequestedAt(fixed);
            da.setStatus(null); // explicitly null out to test the null branch

            invokeBeforeSave(da);

            assertThat(da.getRequestedAt()).isEqualTo(fixed);
            assertThat(da.getStatus()).isEqualTo(DischargeStatus.PENDING);
        }

        @Test
        void doesNotOverrideExistingStatus() throws Exception {
            DischargeApproval da = new DischargeApproval();
            da.setRequestedAt(null);
            da.setStatus(DischargeStatus.APPROVED);

            invokeBeforeSave(da);

            assertThat(da.getRequestedAt()).isNotNull();
            assertThat(da.getStatus()).isEqualTo(DischargeStatus.APPROVED);
        }

        @Test
        void doesNotOverrideEitherWhenBothSet() throws Exception {
            LocalDateTime fixed = LocalDateTime.of(2025, 3, 15, 8, 30);
            DischargeApproval da = new DischargeApproval();
            da.setRequestedAt(fixed);
            da.setStatus(DischargeStatus.REJECTED);

            invokeBeforeSave(da);

            assertThat(da.getRequestedAt()).isEqualTo(fixed);
            assertThat(da.getStatus()).isEqualTo(DischargeStatus.REJECTED);
        }
    }
}
