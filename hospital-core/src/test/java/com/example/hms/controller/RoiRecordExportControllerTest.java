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
class RoiRecordExportControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean RoiRequestService requests;
    @MockitoBean PatientRecordPdfService pdf;

    @Test
    void approvedCopyUsesExistingRedactedPdfAndAuditsBeforeReturningUncacheableBytes() throws Exception {
        UUID requestId = UUID.randomUUID(), patientId = UUID.randomUUID();
        byte[] bytes = "%PDF-1.4 cdx-fixture".getBytes(StandardCharsets.US_ASCII);
        when(requests.copyForExport(requestId)).thenReturn(patientId);
        when(pdf.render(patientId)).thenReturn(bytes);
        mvc.perform(get("/roi-requests/{id}/record.pdf", requestId)).andExpect(status().isOk())
            .andExpect(content().contentType("application/pdf")).andExpect(content().bytes(bytes))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        var order = inOrder(requests, pdf);
        order.verify(requests).copyForExport(requestId); order.verify(pdf).render(patientId);
        order.verify(requests).recordExport(requestId);
    }

    @Test
    void auditFailureDoesNotReturnPdfBytes() throws Exception {
        UUID requestId = UUID.randomUUID(), patientId = UUID.randomUUID();
        when(requests.copyForExport(requestId)).thenReturn(patientId);
        when(pdf.render(patientId)).thenReturn("%PDF cdx-secret".getBytes(StandardCharsets.US_ASCII));
        doThrow(new BusinessException("roi.request.disclosureNotRecorded")).when(requests).recordExport(requestId);
        mvc.perform(get("/roi-requests/{id}/record.pdf", requestId)).andExpect(status().is4xxClientError())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("cdx-secret"))));
    }

    @Test
    void unresolvedRequestNeverRendersTheRecord() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(requests.copyForExport(requestId)).thenThrow(new BusinessException("roi.request.copyNotApproved"));
        mvc.perform(get("/roi-requests/{id}/record.pdf", requestId)).andExpect(status().is4xxClientError());
        verifyNoInteractions(pdf); verify(requests, never()).recordExport(any());
    }
}
