package com.example.hms.service.roi;

import com.example.hms.BaseIT;
import com.example.hms.enums.*;
import com.example.hms.exception.RoiAuditUnavailableException;
import com.example.hms.model.*;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.roi.RoiDecisionDTO;
import com.example.hms.repository.*;
import com.example.hms.service.RequiredAuditEventLogPort;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real service proxies, database rows and audit writes; no outer test transaction can hide a commit. */
class RoiWorkflowAuditIntegrationTest extends BaseIT {
    @Autowired RoiRequestService service;
    @Autowired RoiRequestRepository requests;
    @Autowired HospitalRepository hospitals;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    @Autowired com.example.hms.service.AuditEventLogService auditLog;
    @MockitoBean RoleValidator roleValidator;
    @MockitoSpyBean RequiredAuditEventLogPort audit;
    @MockitoSpyBean com.example.hms.mapper.AuditEventLogMapper auditMapper;
    private RoiRequest request;
    private RequiredAuditEventLogPort target;

    @BeforeEach
    void fixtures() {
        target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        reset(target);
        reset(auditMapper);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Hospital hospital = hospitals.save(Hospital.builder().name("ROI test " + tag).code("ROI" + tag)
            .city("Ouagadougou").country("Burkina Faso").address("Test address")
            .phoneNumber("+22670000000").email(tag + "@example.test").build());
        User actor = users.save(User.builder().username("roi" + tag).passwordHash("synthetic")
            .email(tag + "@example.test").firstName("Test").lastName(tag).isActive(true).build());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospital.getId());
        when(roleValidator.getCurrentUserId()).thenReturn(actor.getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            actor.getUsername(), "ignored", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DOCTOR"))));
        request = requests.saveAndFlush(RoiRequest.builder().hospital(hospital).patientId(UUID.randomUUID())
            .patientName("Synthetic patient").requesterType(RoiRequesterType.THIRD_PARTY)
            .requesterName("Synthetic recipient").purpose("Approved test release").scopeDescription("FULL_RECORD")
            .requestedOn(LocalDate.now()).status(RoiRequestStatus.PENDING).build());
    }

    @AfterEach
    void clear() {
        reset(target);
        reset(auditMapper);
        SecurityContextHolder.clearContext();
        com.example.hms.security.context.HospitalContextHolder.clear();
    }

    @Test
    void aLaterTrailFailureRollsBackTheAlreadyWrittenDisclosureAndDecision() {
        AtomicBoolean disclosureWritten = new AtomicBoolean();
        doAnswer(inv -> {
            AuditEventLog event = inv.getArgument(0);
            if (event.getEventType() == AuditEventType.ROI_FULFILLED) {
                assertThat(disclosureWritten).isTrue();
                entityManager.flush();
                assertThat(events("PATIENT_EXPORT")).isEqualTo(1);
                throw new IllegalStateException("synthetic later trail failure");
            }
            Object result = inv.callRealMethod();
            if (event.getEventType() == AuditEventType.PATIENT_EXPORT) disclosureWritten.set(true);
            return result;
        }).when(auditMapper).toDto(any());
        assertThatThrownBy(() -> service.fulfil(request.getId(), reason())).isInstanceOf(RoiAuditUnavailableException.class);
        assertThat(disclosureWritten).isTrue();
        RoiRequest reloaded = requests.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RoiRequestStatus.PENDING);
        assertThat(reloaded.getDecisionNote()).isNull();
        assertThat(events("PATIENT_EXPORT")).isZero();
        assertThat(events("ROI_FULFILLED")).isZero();
    }

    @Test
    void aSuccessfulDecisionCommitsExactlyOneDisclosureAndItsTrail() {
        service.fulfil(request.getId(), reason());
        assertThat(requests.findById(request.getId()).orElseThrow().getStatus()).isEqualTo(RoiRequestStatus.FULFILLED);
        assertThat(events("PATIENT_EXPORT")).isEqualTo(1);
        assertThat(events("ROI_FULFILLED")).isEqualTo(1);
    }

    @Test
    void repeatedRealPdfDownloadsDoNotMultiplyTheAccountedRelease() throws Exception {
        service.fulfil(request.getId(), reason());
        UUID patientId = request.getPatientId(), hospitalId = request.getHospital().getId();
        var patientRepo = mock(PatientRepository.class);
        var registrations = mock(PatientHospitalRegistrationRepository.class);
        var storyboard = mock(com.example.hms.service.PatientStoryboardService.class);
        var chart = mock(com.example.hms.service.ChartReviewService.class);
        Patient patient = Patient.builder().firstName("Synthetic").lastName("Patient").build();
        patient.setId(patientId);
        when(patientRepo.findByIdUnscoped(patientId)).thenReturn(java.util.Optional.of(patient));
        when(registrations.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(storyboard.getHospitalReleaseStoryboard(patientId, hospitalId)).thenReturn(
            com.example.hms.payload.dto.storyboard.PatientStoryboardDTO.builder().hospitalName("Synthetic hospital").build());
        when(chart.getHospitalReleaseChart(patientId, hospitalId)).thenReturn(
            com.example.hms.payload.dto.chartreview.ChartReviewDTO.builder().build());
        var pdf = new com.example.hms.service.PatientRecordPdfService(patientRepo, registrations, storyboard, chart,
            auditLog, users, com.example.hms.i18n.TestMessageSources.bundles());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new com.example.hms.controller.RoiRecordExportController(service, pdf,
                mock(com.example.hms.service.PatientPortalService.class))).build();
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder().activeHospitalId(hospitalId).build());
        for (int i = 0; i < 2; i++) {
            byte[] bytes = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/roi-requests/{id}/record.pdf", request.getId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
            try (var document = org.apache.pdfbox.pdmodel.PDDocument.load(bytes)) {
                assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document)).contains("Synthetic hospital");
            }
        }
        assertThat(events("PATIENT_EXPORT")).isEqualTo(1);
        assertThat(events("ROI_FULFILLED")).isEqualTo(1);
        assertThat(events("DATA_ACCESS")).isEqualTo(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"deny", "cancel"})
    void everyDecisionRollsBackWhenItsRequiredTrailFails(String operation) {
        doThrow(new IllegalStateException("synthetic audit failure")).when(target).logRequiredEvent(any());
        assertThatThrownBy(() -> {
            if (operation.equals("deny")) service.deny(request.getId(), reason());
            else service.cancel(request.getId(), reason());
        }).isInstanceOf(RoiAuditUnavailableException.class);
        assertThat(requests.findById(request.getId()).orElseThrow().getStatus()).isEqualTo(RoiRequestStatus.PENDING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support.audit_event_logs WHERE target_resource_id = ?",
            Integer.class, request.getId().toString())).isZero();
    }

    private RoiDecisionDTO reason() { return RoiDecisionDTO.builder().note("Synthetic authorised decision").build(); }
    private int events(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM support.audit_event_logs WHERE target_resource_id = ? AND event_type = ?",
            Integer.class, request.getId().toString(), type);
    }
}
