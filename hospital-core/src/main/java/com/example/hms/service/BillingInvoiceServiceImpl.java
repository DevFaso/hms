package com.example.hms.service;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.BillingInvoiceMapper;
import com.example.hms.model.*;
import com.example.hms.model.InvoiceItem;
import com.example.hms.payload.dto.BillingInvoiceRequestDTO;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.payload.dto.BillingInvoiceSearchRequest;
import com.example.hms.payload.dto.InvoicePdfResponseDTO;
import com.example.hms.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillingInvoiceServiceImpl implements BillingInvoiceService {

    private static final String BILLING_INVOICE_NOT_FOUND = "billinginvoice.notfound";

    private final BillingInvoiceRepository invoiceRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final EncounterRepository encounterRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final PdfInvoiceService pdfInvoiceService;
    private final BillingInvoiceMapper invoiceMapper;

    @Override
    @Transactional
    public BillingInvoiceResponseDTO createInvoice(BillingInvoiceRequestDTO dto, Locale locale) {
        Patient patient = patientRepository.findByUsernameOrEmail(dto.getPatientEmail())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notfound"));

        Hospital hospital = hospitalRepository.findByNameIgnoreCase(dto.getHospitalName())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));

        Encounter encounter = (dto.getEncounterReference() != null)
            ? encounterRepository.findByCode(dto.getEncounterReference()).orElse(null)
            : null;

        BillingInvoice invoice = invoiceMapper.toBillingInvoice(dto, patient, hospital, encounter);

        // Ensure sane defaults
        if (invoice.getAmountPaid() == null) invoice.setAmountPaid(BigDecimal.ZERO);
        if (invoice.getTotalAmount() == null) invoice.setTotalAmount(BigDecimal.ZERO);
        if (invoice.getStatus() == null) invoice.setStatus(InvoiceStatus.DRAFT);

        BillingInvoice saved = invoiceRepository.saveAndFlush(invoice);
        return invoiceMapper.toBillingInvoiceResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public BillingInvoiceResponseDTO getInvoiceById(UUID id, Locale locale) {
        BillingInvoice invoice = invoiceRepository.findWithAllById(id)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND));
        return invoiceMapper.toBillingInvoiceResponseDTO(invoice);
    }



    @Override
    @Transactional(readOnly = true)
    public Page<BillingInvoiceResponseDTO> getInvoicesByPatientId(UUID patientId, Pageable pageable, Locale locale) {
        return invoiceRepository.findByPatient_Id(patientId, pageable)
            .map(invoiceMapper::toBillingInvoiceResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BillingInvoiceResponseDTO> getInvoicesByHospitalId(UUID hospitalId, Pageable pageable, Locale locale) {
        return invoiceRepository.findByHospital_Id(hospitalId, pageable)
            .map(invoiceMapper::toBillingInvoiceResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillingInvoiceResponseDTO> getOverdueInvoices(LocalDate referenceDate, Locale locale) {
        List<BillingInvoice> overdue = invoiceRepository.findOverdue(
            referenceDate,
            List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIALLY_PAID)
        );
        return overdue.stream()
            .map(invoiceMapper::toBillingInvoiceResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public BillingInvoiceResponseDTO updateInvoice(UUID id, BillingInvoiceRequestDTO dto, Locale locale) {
        BillingInvoice invoice = invoiceRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND));

        Patient patient = patientRepository.findByUsernameOrEmail(dto.getPatientEmail())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notfound"));

        Hospital hospital = hospitalRepository.findByNameIgnoreCase(dto.getHospitalName())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));

        Encounter encounter = (dto.getEncounterReference() != null)
            ? encounterRepository.findByCode(dto.getEncounterReference()).orElse(null)
            : null;

        invoice.setPatient(patient);
        invoice.setHospital(hospital);
        invoice.setEncounter(encounter);
        invoice.setInvoiceNumber(dto.getInvoiceNumber());
        invoice.setInvoiceDate(dto.getInvoiceDate());
        invoice.setDueDate(dto.getDueDate());
        invoice.setTotalAmount(dto.getTotalAmount());
        invoice.setAmountPaid(dto.getAmountPaid());
        // IMPORTANT: Make sure your DTO uses the enum too
        invoice.setStatus(dto.getStatus());
        invoice.setNotes(dto.getNotes());

        BillingInvoice saved = invoiceRepository.save(invoice);
        return invoiceMapper.toBillingInvoiceResponseDTO(saved);
    }

    @Override
    @Transactional
    public void deleteInvoice(UUID id, Locale locale) {
        if (!invoiceRepository.existsById(id)) {
            throw new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND);
        }
        invoiceRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void recomputeAndPersistTotals(UUID invoiceId) {
        BillingInvoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND));
        BigDecimal sum = invoiceRepository.sumItemsByInvoiceId(invoiceId);
        invoice.setTotalAmount(sum != null ? sum : BigDecimal.ZERO);
        invoiceRepository.save(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoicePdfResponseDTO getInvoicePdf(UUID invoiceId, Locale locale) {
        BillingInvoice invoice = invoiceRepository.findByIdWithRefs(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND));

        List<InvoiceItem> items = invoiceItemRepository.findByBillingInvoiceId(invoiceId);
        Locale effectiveLocale = locale != null ? locale : LocaleContextHolder.getLocale();

        byte[] pdf = pdfInvoiceService.generateInvoicePdf(invoice, items, effectiveLocale);
        return new InvoicePdfResponseDTO(pdf, invoice.getInvoiceNumber(), invoice.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BillingInvoiceResponseDTO> searchInvoices(BillingInvoiceSearchRequest searchRequest, Pageable pageable, Locale locale) {
        List<InvoiceStatus> statuses = null;
        if (searchRequest != null && searchRequest.getStatuses() != null) {
            statuses = searchRequest.getStatuses().stream()
                .map(InvoiceStatus::valueOf)
                .toList();
        }
        return invoiceRepository.findAllWithFilters(
            searchRequest != null ? searchRequest.getPatientId() : null,
            searchRequest != null ? searchRequest.getHospitalId() : null,
            statuses,
            searchRequest != null ? searchRequest.getFromDate() : null,
            searchRequest != null ? searchRequest.getToDate() : null,
            pageable
        ).map(invoiceMapper::toBillingInvoiceResponseDTO);
    }
}
