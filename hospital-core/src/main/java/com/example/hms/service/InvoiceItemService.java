package com.example.hms.service;

import com.example.hms.payload.dto.InvoiceItemRequestDTO;
import com.example.hms.payload.dto.InvoiceItemResponseDTO;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface InvoiceItemService {

    InvoiceItemResponseDTO createInvoiceItem(InvoiceItemRequestDTO requestDTO, Locale locale);

    InvoiceItemResponseDTO getInvoiceItemById(UUID id, Locale locale);

    List<InvoiceItemResponseDTO> getItemsByInvoiceId(UUID invoiceId, Locale locale);

    InvoiceItemResponseDTO updateInvoiceItem(UUID id, InvoiceItemRequestDTO requestDTO, Locale locale);

    void deleteInvoiceItem(UUID id, Locale locale);
}
