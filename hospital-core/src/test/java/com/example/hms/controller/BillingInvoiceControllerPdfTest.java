package com.example.hms.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;
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
}
