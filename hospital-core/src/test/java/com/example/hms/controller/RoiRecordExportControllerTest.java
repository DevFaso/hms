package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.service.PatientRecordPdfService;
import com.example.hms.service.roi.RoiRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = RoiRecordExportController.class, excludeFilters = @ComponentScan.Filter(
    type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@org.springframework.context.annotation.Import(RoiRecordExportControllerTest.MethodSecurity.class)
@org.springframework.security.test.context.support.WithMockUser(authorities = "ROLE_DOCTOR")
class RoiRecordExportControllerTest {
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class MethodSecurity { }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ROLE_PATIENT", "ROLE_HOSPITAL_ADMIN", "ROLE_RECEPTIONIST", "ROLE_ACCOUNTANT"})
    void aWorklistOrPatientRoleCannotDownloadAClinicalRecord(String role) throws Exception {
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "limited", "ignored", java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role)));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        mvc.perform(get("/roi-requests/{id}/record.pdf", UUID.randomUUID())).andExpect(status().isForbidden());
        verifyNoInteractions(requests, pdf);
    }

    @Autowired MockMvc mvc;
    @MockitoBean RoiRequestService requests;
    @MockitoBean PatientRecordPdfService pdf;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_SUPER_ADMIN"})
    void approvedCopyUsesExistingRedactedPdfAndAuditsBeforeReturningUncacheableBytes(String role) throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("clinical", "ignored",
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role))));
        UUID requestId = UUID.randomUUID(), patientId = UUID.randomUUID();
        byte[] bytes = "%PDF-1.4 cdx-fixture".getBytes(StandardCharsets.US_ASCII);
        var approved = new RoiRequestService.ApprovedCopy(patientId, requestId, UUID.randomUUID());
        when(requests.copyForExport(requestId)).thenReturn(approved);
        when(pdf.renderForRoi(approved)).thenReturn(bytes);
        mvc.perform(get("/roi-requests/{id}/record.pdf", requestId)).andExpect(status().isOk())
            .andExpect(content().contentType("application/pdf")).andExpect(content().bytes(bytes))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        var order = inOrder(requests, pdf);
        order.verify(requests).copyForExport(requestId); order.verify(pdf).renderForRoi(approved);
        verifyNoMoreInteractions(requests, pdf);
    }

    @Test
    void auditFailureDoesNotReturnPdfBytes() throws Exception {
        UUID requestId = UUID.randomUUID(), patientId = UUID.randomUUID();
        var approved = new RoiRequestService.ApprovedCopy(patientId, requestId, UUID.randomUUID());
        when(requests.copyForExport(requestId)).thenReturn(approved);
        when(pdf.renderForRoi(approved)).thenThrow(new com.example.hms.exception.RoiAuditUnavailableException("roi.request.disclosureNotRecorded"));
        mvc.perform(get("/roi-requests/{id}/record.pdf", requestId)).andExpect(status().isServiceUnavailable())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("%PDF-"))));
    }

    @Test
    void unresolvedRequestNeverRendersTheRecord() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(requests.copyForExport(requestId)).thenThrow(new BusinessException("roi.request.copyNotApproved"));
        mvc.perform(get("/roi-requests/{id}/record.pdf", requestId)).andExpect(status().is4xxClientError());
        verifyNoInteractions(pdf);
    }
}
