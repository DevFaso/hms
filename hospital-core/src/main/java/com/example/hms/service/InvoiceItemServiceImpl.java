package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.InvoiceItemMapper;
import com.example.hms.model.*;
import com.example.hms.payload.dto.InvoiceItemRequestDTO;
import com.example.hms.payload.dto.InvoiceItemResponseDTO;
import com.example.hms.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InvoiceItemServiceImpl implements InvoiceItemService {

    private final InvoiceItemRepository invoiceItemRepository;
    private final BillingInvoiceRepository billingInvoiceRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final TreatmentRepository treatmentRepository;
    private final InvoiceItemMapper invoiceItemMapper;
    private final BillingInvoiceService billingInvoiceService;
    private final MessageSource messageSource;

    @Override
    @Transactional
    public InvoiceItemResponseDTO createInvoiceItem(InvoiceItemRequestDTO dto, Locale locale) {
        BillingInvoice invoice = billingInvoiceRepository.findById(dto.getBillingInvoiceId())
            .orElseThrow(() -> new ResourceNotFoundException("billinginvoice.notfound"));

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(dto.getAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException("assignment.notfound"));

        // Ensure assignment hospital matches invoice hospital
        if (!assignment.getHospital().getId().equals(invoice.getHospital().getId())) {
            throw new BusinessException("assignment.hospital.mismatch");
        }

        Treatment related = null;
        if (dto.getRelatedServiceId() != null) {
            related = treatmentRepository.findById(dto.getRelatedServiceId())
                .orElseThrow(() -> new ResourceNotFoundException("treatment.notfound"));
        }

        InvoiceItem item = invoiceItemMapper.toInvoiceItem(dto, invoice, assignment, related);

        InvoiceItem saved = invoiceItemRepository.save(item);

        // Keep invoice totals in sync
        billingInvoiceService.recomputeAndPersistTotals(invoice.getId());

        return invoiceItemMapper.toInvoiceItemResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceItemResponseDTO getInvoiceItemById(UUID id, Locale locale) {
        InvoiceItem item = invoiceItemRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("invoiceitem.notfound"));
        return invoiceItemMapper.toInvoiceItemResponseDTO(item);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceItemResponseDTO> getItemsByInvoiceId(UUID invoiceId, Locale locale) {
        return invoiceItemRepository.findByBillingInvoice_Id(invoiceId).stream()
            .map(invoiceItemMapper::toInvoiceItemResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public InvoiceItemResponseDTO updateInvoiceItem(UUID id, InvoiceItemRequestDTO dto, Locale locale) {
        InvoiceItem item = invoiceItemRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("invoiceitem.notfound"));

        BillingInvoice invoice = billingInvoiceRepository.findById(dto.getBillingInvoiceId())
            .orElseThrow(() -> new ResourceNotFoundException("billinginvoice.notfound"));

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(dto.getAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException("assignment.notfound"));

        if (!assignment.getHospital().getId().equals(invoice.getHospital().getId())) {
            throw new BusinessException("assignment.hospital.mismatch");
        }

        Treatment related = null;
        if (dto.getRelatedServiceId() != null) {
            related = treatmentRepository.findById(dto.getRelatedServiceId())
                .orElseThrow(() -> new ResourceNotFoundException("treatment.notfound"));
        }

        // apply updates
        item.setBillingInvoice(invoice);
        item.setItemDescription(dto.getItemDescription());
        item.setQuantity(dto.getQuantity());
        item.setUnitPrice(dto.getUnitPrice());
        item.setItemCategory(dto.getItemCategory());
        item.setAssignment(assignment);
        item.setRelatedService(related);
        // totalPrice recalculated by @PreUpdate

        InvoiceItem saved = invoiceItemRepository.save(item);

        // recompute invoice total
        billingInvoiceService.recomputeAndPersistTotals(invoice.getId());

        return invoiceItemMapper.toInvoiceItemResponseDTO(saved);
    }

    @Override
    @Transactional
    public void deleteInvoiceItem(UUID id, Locale locale) {
        InvoiceItem item = invoiceItemRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("invoiceitem.notfound"));
        UUID invoiceId = item.getBillingInvoice().getId();
        invoiceItemRepository.delete(item);

        // recompute after deletion
        billingInvoiceService.recomputeAndPersistTotals(invoiceId);
    }
}
