package com.example.hms.mapper;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.Appointment;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.AfterVisitSummaryDTO;
import com.example.hms.payload.dto.clinical.CheckOutRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckOutMapperTest {

    private CheckOutMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CheckOutMapper();
    }

    private Encounter buildEncounter() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Jane");
        patient.setLastName("Doe");

        User user = new User();
        user.setFirstName("Dr. Smith");
        user.setLastName("Johnson");

        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user);

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("City Hospital");

        Department department = new Department();
        department.setId(UUID.randomUUID());
        department.setName("Internal Medicine");

        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setPatient(patient);
        encounter.setStaff(staff);
        encounter.setHospital(hospital);
        encounter.setDepartment(department);
        encounter.setEncounterDate(LocalDateTime.of(2025, 7, 1, 10, 0));
        encounter.setStatus(EncounterStatus.COMPLETED);
        encounter.setCheckoutTimestamp(LocalDateTime.of(2025, 7, 1, 11, 30));
        encounter.setChiefComplaint("Headache and fatigue");
        return encounter;
    }

    // ─── toAfterVisitSummary ─────────────────────────────────────

    @Nested
    class ToAfterVisitSummary {

        @Test
        void mapsEncounterFields() {
            Encounter encounter = buildEncounter();
            CheckOutRequestDTO request = CheckOutRequestDTO.builder()
                .followUpInstructions("Return in 2 weeks")
                .dischargeDiagnoses(List.of("Migraine", "Iron deficiency"))
                .prescriptionSummary("Ibuprofen 200mg")
                .referralSummary("Neurology referral")
                .patientEducationMaterials("Migraine management handout")
                .build();

            AfterVisitSummaryDTO avs = mapper.toAfterVisitSummary(encounter, request, null);

            assertThat(avs.getEncounterId()).isEqualTo(encounter.getId());
            assertThat(avs.getVisitDate()).isEqualTo(encounter.getEncounterDate());
            assertThat(avs.getCheckoutTimestamp()).isEqualTo(encounter.getCheckoutTimestamp());
            assertThat(avs.getChiefComplaint()).isEqualTo("Headache and fatigue");
            assertThat(avs.getProviderName()).isEqualTo("Dr. Smith Johnson");
            assertThat(avs.getDepartmentName()).isEqualTo("Internal Medicine");
            assertThat(avs.getHospitalName()).isEqualTo("City Hospital");
            assertThat(avs.getPatientName()).isEqualTo("Jane Doe");
        }

        @Test
        void mapsRequestFields() {
            Encounter encounter = buildEncounter();
            CheckOutRequestDTO request = CheckOutRequestDTO.builder()
                .followUpInstructions("Rest for 3 days")
                .dischargeDiagnoses(List.of("Common cold"))
                .prescriptionSummary("Acetaminophen 500mg")
                .referralSummary("None")
                .patientEducationMaterials("Cold recovery tips")
                .build();

            AfterVisitSummaryDTO avs = mapper.toAfterVisitSummary(encounter, request, null);

            assertThat(avs.getFollowUpInstructions()).isEqualTo("Rest for 3 days");
            assertThat(avs.getDischargeDiagnoses()).containsExactly("Common cold");
            assertThat(avs.getPrescriptionSummary()).isEqualTo("Acetaminophen 500mg");
            assertThat(avs.getReferralSummary()).isEqualTo("None");
            assertThat(avs.getPatientEducationMaterials()).isEqualTo("Cold recovery tips");
        }

        @Test
        void handlesNullRequest() {
            Encounter encounter = buildEncounter();
            encounter.setDischargeDiagnoses("[\"Hypertension\"]");
            encounter.setFollowUpInstructions("Monitor blood pressure");

            AfterVisitSummaryDTO avs = mapper.toAfterVisitSummary(encounter, null, null);

            assertThat(avs.getEncounterId()).isEqualTo(encounter.getId());
            assertThat(avs.getDischargeDiagnoses()).containsExactly("Hypertension");
            assertThat(avs.getFollowUpInstructions()).isEqualTo("Monitor blood pressure");
        }

        @Test
        void handlesLinkedAppointment() {
            Encounter encounter = buildEncounter();
            Appointment appointment = new Appointment();
            appointment.setId(UUID.randomUUID());
            appointment.setStatus(AppointmentStatus.COMPLETED);
            encounter.setAppointment(appointment);

            CheckOutRequestDTO request = CheckOutRequestDTO.builder().build();
            AfterVisitSummaryDTO avs = mapper.toAfterVisitSummary(encounter, request, null);

            assertThat(avs.getAppointmentId()).isEqualTo(appointment.getId());
            assertThat(avs.getAppointmentStatus()).isEqualTo("COMPLETED");
        }

        @Test
        void handlesFollowUpAppointmentId() {
            Encounter encounter = buildEncounter();
            UUID followUpId = UUID.randomUUID();
            CheckOutRequestDTO request = CheckOutRequestDTO.builder().build();

            AfterVisitSummaryDTO avs = mapper.toAfterVisitSummary(encounter, request, followUpId);

            assertThat(avs.getFollowUpAppointmentId()).isEqualTo(followUpId);
        }

        @Test
        void handlesNullPatient() {
            Encounter encounter = buildEncounter();
            encounter.setPatient(null);
            CheckOutRequestDTO request = CheckOutRequestDTO.builder().build();

            AfterVisitSummaryDTO avs = mapper.toAfterVisitSummary(encounter, request, null);

            assertThat(avs.getPatientId()).isNull();
            assertThat(avs.getPatientName()).isNull();
        }

        @Test
        void handlesNullStaff() {
            Encounter encounter = buildEncounter();
            encounter.setStaff(null);
            CheckOutRequestDTO request = CheckOutRequestDTO.builder().build();

            AfterVisitSummaryDTO avs = mapper.toAfterVisitSummary(encounter, request, null);

            assertThat(avs.getProviderName()).isNull();
        }

        @Test
        void handlesNullDepartmentAndHospital() {
            Encounter encounter = buildEncounter();
            encounter.setDepartment(null);
            encounter.setHospital(null);
            CheckOutRequestDTO request = CheckOutRequestDTO.builder().build();

            AfterVisitSummaryDTO avs = mapper.toAfterVisitSummary(encounter, request, null);

            assertThat(avs.getDepartmentName()).isNull();
            assertThat(avs.getHospitalName()).isNull();
        }
    }

    // ─── serializeDiagnoses / parseDiagnoses ────────────────────

    @Nested
    class DiagnosesSerialisation {

        @Test
        void serializeNullReturnsNull() {
            assertThat(mapper.serializeDiagnoses(null)).isNull();
        }

        @Test
        void serializeEmptyReturnsNull() {
            assertThat(mapper.serializeDiagnoses(List.of())).isNull();
        }

        @Test
        void serializeAndParseRoundTrips() {
            List<String> diagnoses = List.of("Migraine", "Iron deficiency anemia");
            String json = mapper.serializeDiagnoses(diagnoses);

            assertThat(json).isNotNull().contains("Migraine");

            List<String> parsed = mapper.parseDiagnoses(json);
            assertThat(parsed).containsExactly("Migraine", "Iron deficiency anemia");
        }

        @Test
        void parseNullReturnsEmptyList() {
            assertThat(mapper.parseDiagnoses(null)).isEmpty();
        }

        @Test
        void parseBlankReturnsEmptyList() {
            assertThat(mapper.parseDiagnoses("  ")).isEmpty();
        }

        @Test
        void parseInvalidJsonReturnsSingleItem() {
            List<String> result = mapper.parseDiagnoses("not-json");
            assertThat(result).hasSize(1).containsExactly("not-json");
        }
    }
}
