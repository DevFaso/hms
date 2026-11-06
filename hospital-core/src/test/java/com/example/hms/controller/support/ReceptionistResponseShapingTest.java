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
        assertThat(shaped.getBloodType()).isEqualTo("O+");
        assertThat(shaped.getAllergies()).isNull();
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
