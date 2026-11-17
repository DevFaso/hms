package com.example.hms.controller;

import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.payload.dto.InvoiceItemRequestDTO;
import com.example.hms.service.InvoiceItemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D657: POST /invoice-items with quantity 1.5 was stored as 1 (total
 * 1000). A fraction of a line is refused before the service sees it; a
 * whole number written as 2.0 is still read as 2.
 */
class InvoiceItemControllerQuantityTest {

    private static final String LINE = "{\"billingInvoiceId\":\"%s\",\"itemDescription\":\"QA\","
        + "\"quantity\":%s,\"unitPrice\":1000,\"itemCategory\":\"SERVICE\"}";

    private final InvoiceItemService service = mock(InvoiceItemService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new InvoiceItemController(service, mock(MessageSource.class)))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @Test
    @DisplayName("D657: a decimal quantity is refused with 400 naming the field, not truncated")
    void aDecimalQuantityIsRefused() throws Exception {
        mvc.perform(post("/invoice-items").contentType(MediaType.APPLICATION_JSON)
                .content(LINE.formatted(java.util.UUID.randomUUID(), "1.5")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("quantity"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("D657: a whole quantity written with a zero fraction is still read")
    void aWholeQuantityWithAZeroFractionIsRead() throws Exception {
        mvc.perform(post("/invoice-items").contentType(MediaType.APPLICATION_JSON)
                .content(LINE.formatted(java.util.UUID.randomUUID(), "2.0")))
            .andExpect(status().isCreated());
        ArgumentCaptor<InvoiceItemRequestDTO> dto = ArgumentCaptor.forClass(InvoiceItemRequestDTO.class);
        verify(service).createInvoiceItem(dto.capture(), any());
        assertThat(dto.getValue().getQuantity()).isEqualTo(2);
    }
}
