package com.example.hms.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hms.payload.dto.InvoicePdfResponseDTO;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.service.BillingInvoiceService;
import com.example.hms.service.InvoiceEmailService;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = BillingInvoiceController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
class BillingInvoiceControllerPdfTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BillingInvoiceService billingInvoiceService;

    @MockitoBean
    private MessageSource messageSource;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private InvoiceEmailService invoiceEmailService;

    @MockitoBean
    private com.example.hms.controller.support.ControllerAuthUtils authUtils;

    /** QA D755: another hospital's (or an unknown) invoice is a 404 here too; the catch-all made it a 500. */
    @Test
    void emailOfAnInvoiceOutOfScopeIs404Not500() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        doThrow(new com.example.hms.exception.ResourceNotFoundException("billinginvoice.notfound", invoiceId))
            .when(invoiceEmailService).emailInvoice(eq(invoiceId), org.mockito.ArgumentMatchers.any());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/billing-invoices/{id}/email", invoiceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":[\"q@example.test\"],\"attachPdf\":true}"))
            .andExpect(status().isNotFound());
    }

    /** QA D758: a refused draft is a 4xx with its reason, not a 500. */
    @Test
    void emailOfADraftIsABadRequest() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        doThrow(new com.example.hms.exception.BusinessException("billing.invoice.emailDraft"))
            .when(invoiceEmailService).emailInvoice(eq(invoiceId), org.mockito.ArgumentMatchers.any());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/billing-invoices/{id}/email", invoiceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":[\"q@example.test\"],\"attachPdf\":true}"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void downloadInvoicePdfRespondsWithAttachment() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        byte[] pdfBytes = "pdf-bytes".getBytes(StandardCharsets.UTF_8);
    when(billingInvoiceService.getInvoicePdf(eq(invoiceId), nullable(Locale.class)))
        .thenReturn(new InvoicePdfResponseDTO(pdfBytes, "INV-2042", invoiceId));

        mockMvc.perform(get("/billing-invoices/{id}/pdf", invoiceId))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("invoice-INV-2042.pdf")))
            .andExpect(content().bytes(pdfBytes));
    }

    /** QA D646: the delete message read "supprim&eacute;e avec succ&egrave;s". */
    @Test
    void deleteMessageKeepsItsAccentsButStillEscapesMarkup() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        when(messageSource.getMessage(eq("billinginvoice.deleted"), org.mockito.ArgumentMatchers.any(),
                nullable(Locale.class)))
            .thenReturn("Facture <b>X</b> supprimée avec succès");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/billing-invoices/{id}", invoiceId).header(HttpHeaders.ACCEPT_LANGUAGE, "fr"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("supprimée avec succès")))
            .andExpect(content().string(containsString("&lt;b&gt;")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("&eacute;"))));
    }
}
