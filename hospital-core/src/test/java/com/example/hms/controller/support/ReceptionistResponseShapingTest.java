package com.example.hms.controller.support;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.bed.BedBoardDTO;
import com.example.hms.payload.dto.clinical.AfterVisitSummaryDTO;
import com.example.hms.payload.dto.clinical.PatientTrackerBoardDTO;
import com.example.hms.payload.dto.clinical.PatientTrackerItemDTO;
import com.example.hms.payload.dto.isolation.IsolationPrecautionResponseDTO;
import com.example.hms.payload.dto.transfer.TransferOrderResponseDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RCPT-CLIN: what the desk keeps and what it loses, on the same endpoint. The
 * declared list ({@link ReceptionistResponseShaping#REMOVED}, which the guard
 * trusts) is proved against what {@code shape} really does, field by field.
 */
@DisplayName("RCPT-CLIN: a receptionist's answer has its clinical fields removed")
class ReceptionistResponseShapingTest {

    private final ReceptionistResponseShaping advice = new ReceptionistResponseShaping();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAs(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", roles));
    }

    private Object write(Object body, String path) {
        return advice.beforeBodyWrite(body, mock(MethodParameter.class), MediaType.APPLICATION_JSON,
            org.springframework.http.converter.StringHttpMessageConverter.class,
            new ServletServerHttpRequest(new MockHttpServletRequest("GET", path)),
            new ServletServerHttpResponse(new MockHttpServletResponse()));
    }

    private static PatientResponseDTO patient() {
        PatientResponseDTO patient = new PatientResponseDTO();
        patient.setFirstName("Awa");
        patient.setPhoneNumberPrimary("70000000");
        patient.setBloodType("O+");
        patient.setAllergies("Penicillin");
        patient.setChronicConditions(List.of("Diabetes"));
        patient.setFlags(List.of("Allergies noted"));
        patient.setRisks(List.of("Review history"));
        patient.setMedicalHistorySummary("Diabetes");
        patient.setCareTeamNotes("Notes");
        patient.setHr(80);
        patient.setBp("120/80");
        patient.setSpo2(98);
        patient.setChartRestrictionReason("VIP");
        return patient;
    }

    @Test
    @DisplayName("a receptionist reads the demographics, and not the clinical fields")
    void receptionistLosesTheClinicalFields() {
        signedInAs("ROLE_RECEPTIONIST");
        PatientResponseDTO shaped = (PatientResponseDTO) write(patient(), "/patients/1");
        assertThat(shaped.getFirstName()).isEqualTo("Awa");
        assertThat(shaped.getPhoneNumberPrimary()).isEqualTo("70000000");
        assertThat(shaped.getBloodType()).isNull();
        assertThat(shaped.getAllergies()).isNull();
        assertThat(shaped.getChronicConditions()).isEmpty();
        assertThat(shaped.getFlags()).isEmpty();
        assertThat(shaped.getRisks()).isEmpty();
        assertThat(shaped.getMedicalHistorySummary()).isNull();
        assertThat(shaped.getCareTeamNotes()).isNull();
        assertThat(shaped.getHr()).isNull();
        assertThat(shaped.getBp()).isNull();
        assertThat(shaped.getSpo2()).isNull();
        assertThat(shaped.getChartRestrictionReason()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN"})
    @DisplayName("no other role loses anything, alone or beside the receptionist role")
    void noOtherRoleIsNarrowed(String role) {
        signedInAs(role);
        assertThat(((PatientResponseDTO) write(patient(), "/patients/1")).getAllergies()).isEqualTo("Penicillin");
        signedInAs(role, "ROLE_RECEPTIONIST");
        assertThat(((PatientResponseDTO) write(patient(), "/patients/1")).getAllergies()).isEqualTo("Penicillin");
    }

    @Test
    @DisplayName("the caller's own record under /me/ stays whole, and no sign-in shapes nothing")
    void ownRecordAndAnonymous() {
        signedInAs("ROLE_RECEPTIONIST", "ROLE_PATIENT");
        assertThat(((PatientResponseDTO) write(patient(), "/api/me/patient/profile")).getAllergies()).isEqualTo("Penicillin");
        SecurityContextHolder.clearContext();
        assertThat(((PatientResponseDTO) write(patient(), "/patients/1")).getAllergies()).isEqualTo("Penicillin");
    }

    @Test
    @DisplayName("through a list, a page and the wrapper")
    void throughTheWrappers() {
        signedInAs("ROLE_RECEPTIONIST");
        PatientResponseDTO inList = patient();
        PatientResponseDTO inPage = patient();
        PatientResponseDTO wrapped = patient();
        write(List.of(inList), "/patients");
        write(new PageImpl<>(List.of(inPage)), "/patients");
        write(ApiResponseWrapper.success(wrapped), "/patients");
        assertThat(List.of(inList, inPage, wrapped)).allSatisfy(p -> assertThat(p.getAllergies()).isNull());
    }

    @Test
    @DisplayName("the check-out summary keeps the visit and its lists stay lists")
    void theSummaryStaysRenderable() {
        signedInAs("ROLE_RECEPTIONIST");
        AfterVisitSummaryDTO summary = new AfterVisitSummaryDTO();
        summary.setPatientName("Awa");
        summary.setChiefComplaint("Pain");
        summary.setDischargeDiagnoses(List.of("Malaria"));
        summary.setPrescriptionSummary("ACT");
        write(summary, "/encounters/1/avs");
        assertThat(summary.getPatientName()).isEqualTo("Awa");
        assertThat(summary.getChiefComplaint()).isNull();
        assertThat(summary.getDischargeDiagnoses()).isEmpty();
        assertThat(summary.getPrescriptionSummary()).isNull();
    }

    @Test
    @DisplayName("the board keeps who is in which bed, without the diagnosis")
    void theBoardKeepsPlacement() {
        signedInAs("ROLE_RECEPTIONIST");
        BedBoardDTO.OccupantDTO occupant = BedBoardDTO.OccupantDTO.builder().patientName("Awa").primaryDiagnosis("TB").build();
        BedBoardDTO board = BedBoardDTO.builder().wards(List.of(BedBoardDTO.WardBoardDTO.builder()
            .rooms(List.of(BedBoardDTO.RoomBoardDTO.builder()
                .beds(List.of(BedBoardDTO.BedBoardEntryDTO.builder().occupant(occupant).build())).build())).build())).build();
        write(board, "/bed-board");
        assertThat(occupant.getPatientName()).isEqualTo("Awa");
        assertThat(occupant.getPrimaryDiagnosis()).isNull();
    }

    // ---- User decision 2026-11-06, field by field: the desk does not get it, a doctor and a nurse still do.

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_DOCTOR", "ROLE_NURSE"})
    @DisplayName("blood type: gone for the desk, kept for a clinician")
    void bloodType(String clinician) {
        signedInAs("ROLE_RECEPTIONIST");
        assertThat(((PatientResponseDTO) write(patient(), "/patients/1")).getBloodType()).isNull();
        signedInAs(clinician);
        assertThat(((PatientResponseDTO) write(patient(), "/patients/1")).getBloodType()).isEqualTo("O+");
    }

    private static EncounterResponseDTO triagedEncounter(String deskTyped) {
        EncounterResponseDTO encounter = new EncounterResponseDTO();
        encounter.setChiefComplaint("Triage: chest pain");
        encounter.setDeskChiefComplaint(deskTyped);
        encounter.setAppointmentReason("Booked visit");
        return encounter;
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_DOCTOR", "ROLE_NURSE"})
    @DisplayName("chief complaint: triage's is gone for the desk, what the desk typed and the booked reason stay; a clinician reads triage's")
    void chiefComplaint(String clinician) {
        signedInAs("ROLE_RECEPTIONIST");
        EncounterResponseDTO none = (EncounterResponseDTO) write(triagedEncounter(null), "/encounters/1");
        assertThat(none.getChiefComplaint()).isNull();
        assertThat(none.getAppointmentReason()).isEqualTo("Booked visit");
        EncounterResponseDTO typed = (EncounterResponseDTO) write(triagedEncounter("Fever"), "/encounters/1");
        assertThat(typed.getChiefComplaint()).isEqualTo("Fever");
        signedInAs(clinician);
        assertThat(((EncounterResponseDTO) write(triagedEncounter("Fever"), "/encounters/1")).getChiefComplaint())
            .isEqualTo("Triage: chest pain");
    }

    private static com.example.hms.payload.dto.ReceptionQueueItemDTO queueRow() {
        return com.example.hms.payload.dto.ReceptionQueueItemDTO.builder()
            .appointmentReason("Triage: chest pain").deskAppointmentReason(null).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_DOCTOR", "ROLE_NURSE"})
    @DisplayName("queue and flow board: the fallback to triage's complaint reads empty for the desk, through every column")
    void queueRowAndFlowBoard(String clinician) {
        signedInAs("ROLE_RECEPTIONIST");
        assertThat(((com.example.hms.payload.dto.ReceptionQueueItemDTO) write(queueRow(), "/reception/queue")).getAppointmentReason()).isNull();
        var inList = queueRow();
        write(List.of(inList), "/reception/queue");
        assertThat(inList.getAppointmentReason()).isNull();
        List<com.example.hms.payload.dto.ReceptionQueueItemDTO> columns = List.of(queueRow(), queueRow(), queueRow(), queueRow(),
            queueRow(), queueRow(), queueRow());
        com.example.hms.payload.dto.FlowBoardDTO flow = com.example.hms.payload.dto.FlowBoardDTO.builder()
            .scheduled(List.of(columns.get(0))).confirmed(List.of(columns.get(1))).arrived(List.of(columns.get(2)))
            .inProgress(List.of(columns.get(3))).noShow(List.of(columns.get(4))).completed(List.of(columns.get(5)))
            .walkIn(List.of(columns.get(6))).build();
        write(flow, "/reception/flow-board");
        assertThat(columns).allSatisfy(row -> assertThat(row.getAppointmentReason()).isNull());
        signedInAs(clinician);
        assertThat(((com.example.hms.payload.dto.ReceptionQueueItemDTO) write(queueRow(), "/reception/queue")).getAppointmentReason())
            .isEqualTo("Triage: chest pain");
    }

    @Test
    @DisplayName("the serialized row never carries the desk's copy, for anyone")
    void theCarriersAreNotSerialized() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules();
        var row = com.example.hms.payload.dto.ReceptionQueueItemDTO.builder().appointmentReason("A").deskAppointmentReason("B").build();
        assertThat(mapper.writeValueAsString(row)).doesNotContain("deskAppointmentReason");
        EncounterResponseDTO encounter = new EncounterResponseDTO();
        encounter.setDeskChiefComplaint("B");
        assertThat(mapper.writeValueAsString(encounter)).doesNotContain("deskChiefComplaint");
    }

    private static com.example.hms.payload.dto.prenatal.PrenatalScheduleResponseDTO schedule() {
        return com.example.hms.payload.dto.prenatal.PrenatalScheduleResponseDTO.builder()
            .highRisk(true).currentGestationalWeek(24).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_DOCTOR", "ROLE_NURSE"})
    @DisplayName("prenatal schedule: the high-risk flag is gone for the desk, the gestational week stays; a clinician keeps both")
    void prenatalSchedule(String clinician) throws Exception {
        signedInAs("ROLE_RECEPTIONIST");
        var shaped = (com.example.hms.payload.dto.prenatal.PrenatalScheduleResponseDTO) write(schedule(), "/prenatal/schedule");
        assertThat(shaped.getHighRisk()).isNull();
        assertThat(shaped.getCurrentGestationalWeek()).isEqualTo(24);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(shaped))
            .doesNotContain("highRisk");
        signedInAs(clinician);
        assertThat(((com.example.hms.payload.dto.prenatal.PrenatalScheduleResponseDTO) write(schedule(), "/prenatal/schedule")).getHighRisk())
            .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_DOCTOR", "ROLE_NURSE"})
    @DisplayName("isolation: the desk keeps the precaution type, without organism or reason; a clinician keeps all three")
    void isolationPrecaution(String clinician) {
        signedInAs("ROLE_RECEPTIONIST");
        IsolationPrecautionResponseDTO shaped = (IsolationPrecautionResponseDTO) write(precaution(), "/isolation/1");
        assertThat(shaped.getPrecautionType()).isEqualTo(com.example.hms.enums.IsolationPrecautionType.CONTACT);
        assertThat(shaped.getSuspectedOrganism()).isNull();
        assertThat(shaped.getReason()).isNull();
        signedInAs(clinician);
        IsolationPrecautionResponseDTO whole = (IsolationPrecautionResponseDTO) write(precaution(), "/isolation/1");
        assertThat(whole.getSuspectedOrganism()).isEqualTo("MRSA");
        assertThat(whole.getReason()).isEqualTo("Wound");
    }

    private static IsolationPrecautionResponseDTO precaution() {
        IsolationPrecautionResponseDTO precaution = new IsolationPrecautionResponseDTO();
        precaution.setPrecautionType(com.example.hms.enums.IsolationPrecautionType.CONTACT);
        precaution.setSuspectedOrganism("MRSA");
        precaution.setReason("Wound");
        return precaution;
    }

    @Test
    @DisplayName("what the guard is told is removed is what is removed, type by type")
    void theDeclaredListIsTheTruth() {
        Map<Object, Set<String>> samples = Map.of(
            new PatientHospitalRegistrationResponseDTO(), ReceptionistResponseShaping.REMOVED.get(PatientHospitalRegistrationResponseDTO.class),
            new EncounterResponseDTO(), ReceptionistResponseShaping.REMOVED.get(EncounterResponseDTO.class),
            new TransferOrderResponseDTO(), ReceptionistResponseShaping.REMOVED.get(TransferOrderResponseDTO.class),
            new IsolationPrecautionResponseDTO(), ReceptionistResponseShaping.REMOVED.get(IsolationPrecautionResponseDTO.class));
        samples.forEach((sample, fields) -> {
            for (String field : fields) {
                Object value = marker(sample, field);
                ReflectionTestUtils.setField(sample, field, value);
                ReceptionistResponseShaping.shape(sample);
                assertThat(ReflectionTestUtils.getField(sample, field)).as(sample.getClass().getSimpleName() + "#" + field).isNull();
            }
        });
        PatientTrackerItemDTO item = new PatientTrackerItemDTO();
        item.setAcuityLevel("ESI_2");
        item.setPatientName("Awa");
        PatientTrackerBoardDTO tracker = PatientTrackerBoardDTO.builder().arrived(List.of(item)).triage(List.of())
            .waitingForPhysician(List.of()).inProgress(List.of()).awaitingResults(List.of()).readyForDischarge(List.of()).build();
        ReceptionistResponseShaping.shape(tracker);
        assertThat(item.getAcuityLevel()).isNull();
        assertThat(item.getPatientName()).isEqualTo("Awa");
    }

    /** A value of the field's own type: only that it is not null matters. */
    private static Object marker(Object sample, String field) {
        Class<?> type = org.springframework.util.ReflectionUtils.findField(sample.getClass(), field).getType();
        if (type == String.class) {
            return "x";
        }
        if (type == Integer.class) {
            return 1;
        }
        if (java.util.List.class.isAssignableFrom(type)) {
            return List.of();
        }
        if (java.util.Map.class.isAssignableFrom(type)) {
            return Map.of();
        }
        return org.mockito.Mockito.mock(type);
    }
}
