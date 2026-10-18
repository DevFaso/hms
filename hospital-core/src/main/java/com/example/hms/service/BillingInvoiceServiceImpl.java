package com.example.hms.service;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.BillingInvoiceMapper;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.BillingInvoiceRequestDTO;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.payload.dto.BillingInvoiceSearchRequest;
import com.example.hms.payload.dto.InvoicePdfResponseDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PaymentTransactionRepository;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.model.PaymentTransaction;
import com.example.hms.payload.dto.portal.PatientPaymentRequestDTO;
import com.example.hms.utility.RoleValidator;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
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
public class BillingInvoiceServiceImpl implements BillingInvoiceService {

    private static final String BILLING_INVOICE_NOT_FOUND = "billinginvoice.notfound";

    private final BillingInvoiceRepository invoiceRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final EncounterRepository encounterRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final PdfInvoiceService pdfInvoiceService;
    private final BillingInvoiceMapper invoiceMapper;
    private final RoleValidator roleValidator;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ActingScopeResolver actingScopeResolver;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final InvoiceNumberAllocator invoiceNumberAllocator;

    public BillingInvoiceServiceImpl(
            BillingInvoiceRepository invoiceRepository,
            PatientRepository patientRepository,
            HospitalRepository hospitalRepository,
            EncounterRepository encounterRepository,
            InvoiceItemRepository invoiceItemRepository,
            PdfInvoiceService pdfInvoiceService,
            BillingInvoiceMapper invoiceMapper,
            RoleValidator roleValidator,
            PaymentTransactionRepository paymentTransactionRepository,
            ActingScopeResolver actingScopeResolver,
            PatientHospitalRegistrationRepository registrationRepository,
            InvoiceNumberAllocator invoiceNumberAllocator) {
        this.invoiceRepository = invoiceRepository;
        this.patientRepository = patientRepository;
        this.hospitalRepository = hospitalRepository;
        this.encounterRepository = encounterRepository;
        this.invoiceItemRepository = invoiceItemRepository;
        this.pdfInvoiceService = pdfInvoiceService;
        this.invoiceMapper = invoiceMapper;
        this.roleValidator = roleValidator;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.actingScopeResolver = actingScopeResolver;
        this.registrationRepository = registrationRepository;
        this.invoiceNumberAllocator = invoiceNumberAllocator;
    }

    @Override
    @Transactional
    public BillingInvoiceResponseDTO createInvoice(BillingInvoiceRequestDTO dto, Locale locale) {
        // The invoice is written at the caller's acting hospital: a hospital
        // named in the body is a claim, not a scope. Global view must pick one.
        UUID hospitalId = actingScopeResolver.requirePinned();
        Hospital hospital = hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFoundByIdentifier", hospitalId));

        // QA D94: a retry or a double submit with the same key is the same invoice.
        if (dto.getClientRequestId() != null) {
            var existing = invoiceRepository.findByHospital_IdAndClientRequestId(hospitalId, dto.getClientRequestId());
            if (existing.isPresent()) {
                return invoiceMapper.toBillingInvoiceResponseDTO(existing.get());
            }
        }

        Patient patient = resolvePatient(dto, hospitalId);
        Encounter encounter = resolveEncounter(dto, hospitalId);

        BillingInvoice invoice = invoiceMapper.toBillingInvoice(dto, patient, hospital, encounter);
        // QA D91: nothing is paid until a payment is recorded.
        invoice.setAmountPaid(BigDecimal.ZERO);
        invoice.setTotalAmount(dto.getTotalAmount() != null ? dto.getTotalAmount() : BigDecimal.ZERO);
        invoice.setStatus(requestedStatusOnCreate(dto.getStatus()));
        invoice.setClientRequestId(dto.getClientRequestId());
        // QA D94: the server numbers the invoice; a typed number is ignored.
        invoice.setInvoiceNumber(allocateNumber(hospital));

        BillingInvoice saved = invoiceRepository.saveAndFlush(invoice);
        return invoiceMapper.toBillingInvoiceResponseDTO(saved);
    }

    private String allocateNumber(Hospital hospital) {
        LocalDate today = LocalDate.now(java.time.ZoneId.systemDefault());
        try {
            return invoiceNumberAllocator.next(hospital, today);
        } catch (org.springframework.dao.DataIntegrityViolationException firstOfYearRace) {
            // Another create inserted this year's counter row first; it exists now.
            return invoiceNumberAllocator.next(hospital, today);
        }
    }

    /**
     * QA D92: the patient by id (picked in the portal, phone-only patients
     * included), else by the legacy e-mail/username key; either way they must
     * be registered at the invoicing hospital.
     */
    private Patient resolvePatient(BillingInvoiceRequestDTO dto, UUID hospitalId) {
        Object key;
        Patient patient;
        if (dto.getPatientId() != null) {
            key = dto.getPatientId();
            patient = patientRepository.findById(dto.getPatientId()).orElse(null);
        } else if (dto.getPatientEmail() != null && !dto.getPatientEmail().isBlank()) {
            key = dto.getPatientEmail();
            patient = patientRepository.findByUsernameOrEmail(dto.getPatientEmail()).orElse(null);
        } else {
            throw new BusinessException("billing.invoice.patientRequired");
        }
        // A patient of another hospital gets the answer of an unknown one.
        if (patient == null || !registrationRepository.existsByPatientIdAndHospitalId(patient.getId(), hospitalId)) {
            throw new ResourceNotFoundException("patient.notFoundByIdentifier", key);
        }
        return patient;
    }

    /** The encounter named by code, only when it belongs to the invoicing hospital. */
    private Encounter resolveEncounter(BillingInvoiceRequestDTO dto, UUID hospitalId) {
        if (dto.getEncounterReference() == null || dto.getEncounterReference().isBlank()) {
            return null;
        }
        Encounter encounter = encounterRepository.findByCode(dto.getEncounterReference()).orElse(null);
        if (encounter == null || encounter.getHospital() == null
                || !hospitalId.equals(encounter.getHospital().getId())) {
            return null;
        }
        return encounter;
    }

    /** QA D91: a new invoice is DRAFT or SENT; a paid status follows payments. */
    private static InvoiceStatus requestedStatusOnCreate(InvoiceStatus requested) {
        if (requested == null || requested == InvoiceStatus.DRAFT) {
            return InvoiceStatus.DRAFT;
        }
        if (requested == InvoiceStatus.SENT) {
            return InvoiceStatus.SENT;
        }
        throw new BusinessException("billing.invoice.statusFollowsPayments");
    }

    @Override
    @Transactional(readOnly = true)
    public BillingInvoiceResponseDTO getInvoiceById(UUID id, Locale locale) {
        BillingInvoice invoice = invoiceRepository.findWithAllById(id)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, id));
        // ── Tenant isolation: verify caller has access to this invoice's hospital ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && invoice.getHospital() != null
                && !activeHospitalId.equals(invoice.getHospital().getId())) {
            throw new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, id); // 404, not 403
        }
        return invoiceMapper.toBillingInvoiceResponseDTO(invoice);
    }



    @Override
    @Transactional(readOnly = true)
    public Page<BillingInvoiceResponseDTO> getInvoicesByPatientId(UUID patientId, Pageable pageable, Locale locale) {
        // ── Tenant isolation: scope by active hospital when present ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            return invoiceRepository.findByPatient_IdAndHospital_Id(patientId, activeHospitalId, pageable)
                .map(invoiceMapper::toBillingInvoiceResponseDTO);
        }
        return invoiceRepository.findByPatient_Id(patientId, pageable)
            .map(invoiceMapper::toBillingInvoiceResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BillingInvoiceResponseDTO> getInvoicesForPortalPatient(UUID patientId, Pageable pageable, Locale locale) {
        return invoiceRepository.findByPatient_Id(patientId, pageable)
            .map(invoiceMapper::toBillingInvoiceResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BillingInvoiceResponseDTO> getInvoicesByHospitalId(UUID hospitalId, Pageable pageable, Locale locale) {
        // ── Tenant isolation: the caller's active hospital overrides the requested
        //    path variable; only super-admin (null) may read the hospital they asked for ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        return invoiceRepository.findByHospital_Id(effectiveHospitalId, pageable)
            .map(invoiceMapper::toBillingInvoiceResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillingInvoiceResponseDTO> getOverdueInvoices(LocalDate referenceDate, Locale locale) {
        // ── Tenant isolation: super-admin sees all, others scoped ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<BillingInvoice> overdue = invoiceRepository.findOverdue(
            referenceDate,
            List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIALLY_PAID)
        );
        if (activeHospitalId != null) {
            overdue = overdue.stream()
                .filter(inv -> inv.getHospital() != null && activeHospitalId.equals(inv.getHospital().getId()))
                .toList();
        }
        return overdue.stream()
            .map(invoiceMapper::toBillingInvoiceResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public BillingInvoiceResponseDTO updateInvoice(UUID id, BillingInvoiceRequestDTO dto, Locale locale) {
        BillingInvoice invoice = invoiceRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, id));

        // ── Tenant isolation ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && invoice.getHospital() != null
                && !activeHospitalId.equals(invoice.getHospital().getId())) {
            throw new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, id);
        }

        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BusinessException("billing.invoice.cancelledLocked");
        }
        UUID hospitalId = invoice.getHospital().getId();
        BigDecimal paid = invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO;
        boolean hasPayments = paid.signum() > 0 || paymentTransactionRepository.existsByInvoice_Id(id);

        // The hospital never moves and the number stays the server's (D94).
        if (dto.getPatientId() != null || (dto.getPatientEmail() != null && !dto.getPatientEmail().isBlank())) {
            Patient patient = resolvePatient(dto, hospitalId);
            if (invoice.getPatient() == null || !patient.getId().equals(invoice.getPatient().getId())) {
                if (hasPayments) {
                    throw new BusinessException("billing.invoice.patientLockedByPayments");
                }
                invoice.setPatient(patient);
            }
        }

        invoice.setEncounter(resolveEncounter(dto, hospitalId));
        invoice.setInvoiceDate(dto.getInvoiceDate());
        invoice.setDueDate(dto.getDueDate());
        // QA D72: with lines the total is their sum; without, the typed amount.
        if (dto.getTotalAmount() != null && !invoiceRepository.hasItems(id)) {
            if (dto.getTotalAmount().compareTo(paid) < 0) {
                throw new BusinessException("billing.invoice.totalBelowPaid");
            }
            invoice.setTotalAmount(dto.getTotalAmount());
        }
        // QA D91: amountPaid is never taken from the form.
        invoice.setStatus(requestedStatusOnUpdate(invoice.getStatus(), dto.getStatus(), hasPayments));
        invoice.setNotes(dto.getNotes());

        BillingInvoice saved = invoiceRepository.save(invoice);
        return invoiceMapper.toBillingInvoiceResponseDTO(saved);
    }

    @Override
    @Transactional
    public void deleteInvoice(UUID id, Locale locale) {
        BillingInvoice invoice = invoiceRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, id));
        // ── Tenant isolation ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && invoice.getHospital() != null
                && !activeHospitalId.equals(invoice.getHospital().getId())) {
            throw new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, id);
        }
        // A payment row references the invoice (fk_pt_invoice, no cascade):
        // an invoice somebody has paid against is a financial record, so it
        // is refused with a reason rather than failing on the foreign key.
        if (paymentTransactionRepository.existsByInvoice_Id(id)) {
            throw new BusinessException("billing.invoice.hasPayments");
        }
        invoiceRepository.deleteById(id);
    }

    /**
     * QA D91: staff move an invoice between DRAFT, SENT and CANCELLED; PAID
     * and PARTIALLY_PAID follow the recorded payments, so they are accepted
     * only unchanged (the edit form echoes the current status). Once money
     * is recorded the invoice cannot go back to draft or be cancelled.
     */
    private static InvoiceStatus requestedStatusOnUpdate(InvoiceStatus current, InvoiceStatus requested,
            boolean hasPayments) {
        if (requested == null || requested == current) {
            return current;
        }
        if (requested == InvoiceStatus.PAID || requested == InvoiceStatus.PARTIALLY_PAID
                || current == InvoiceStatus.PAID || current == InvoiceStatus.PARTIALLY_PAID) {
            throw new BusinessException("billing.invoice.statusFollowsPayments");
        }
        if (hasPayments) {
            throw new BusinessException("billing.invoice.statusLockedByPayments");
        }
        return requested;
    }

    @Override
    @Transactional
    public void recomputeAndPersistTotals(UUID invoiceId) {
        BillingInvoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId));
        BigDecimal sum = invoiceRepository.sumItemsByInvoiceId(invoiceId);
        BigDecimal total = sum != null ? sum : BigDecimal.ZERO;
        BigDecimal paid = invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO;
        if (total.compareTo(paid) < 0) {
            throw new BusinessException("billing.invoice.totalBelowPaid");
        }
        invoice.setTotalAmount(total);
        // A line added after a payment re-opens the balance; one removed may settle it.
        if (paid.signum() > 0) {
            invoice.setStatus(paid.compareTo(total) >= 0 ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID);
        }
        invoiceRepository.save(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoicePdfResponseDTO getInvoicePdf(UUID invoiceId, Locale locale) {
        BillingInvoice invoice = invoiceRepository.findByIdWithRefs(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId));

        // ── Tenant isolation ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && invoice.getHospital() != null
                && !activeHospitalId.equals(invoice.getHospital().getId())) {
            throw new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId);
        }

        List<InvoiceItem> items = invoiceItemRepository.findByBillingInvoiceId(invoiceId);
        Locale effectiveLocale = locale != null ? locale : LocaleContextHolder.getLocale();

        byte[] pdf = pdfInvoiceService.generateInvoicePdf(invoice, items, effectiveLocale);
        return new InvoicePdfResponseDTO(pdf, invoice.getInvoiceNumber(), invoice.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BillingInvoiceResponseDTO> searchInvoices(BillingInvoiceSearchRequest searchRequest, Pageable pageable, Locale locale) {
        // ── Tenant isolation: force hospital scope for non-superadmin ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = (searchRequest != null ? searchRequest.getHospitalId() : null);
        if (activeHospitalId != null) {
            effectiveHospitalId = activeHospitalId; // override any client-supplied hospitalId
        }

        List<InvoiceStatus> statuses = null;
        if (searchRequest != null && searchRequest.getStatuses() != null) {
            statuses = searchRequest.getStatuses().stream()
                .map(InvoiceStatus::valueOf)
                .toList();
        }
        return invoiceRepository.findAllWithFilters(
            searchRequest != null ? searchRequest.getPatientId() : null,
            effectiveHospitalId,
            statuses,
            searchRequest != null ? searchRequest.getFromDate() : null,
            searchRequest != null ? searchRequest.getToDate() : null,
            pageable
        ).map(invoiceMapper::toBillingInvoiceResponseDTO);
    }

    @Override
    @Transactional
    public BillingInvoiceResponseDTO recordPayment(UUID invoiceId, UUID patientId, BigDecimal amount, Locale locale) {
        return invoiceMapper.toBillingInvoiceResponseDTO(applyPayment(invoiceId, patientId, amount));
    }

    @Override
    @Transactional
    public BillingInvoiceResponseDTO recordPatientPayment(UUID invoiceId, UUID patientId, UUID recordedBy,
            PatientPaymentRequestDTO payment, Locale locale) {
        // Parse the method first: an unknown one must refuse the payment
        // before the invoice total moves.
        PaymentMethod method = parsePaymentMethod(payment.getPaymentMethod());
        BillingInvoice saved = applyPayment(invoiceId, patientId, payment.getAmount());
        savePaymentRow(saved, payment.getAmount(), method,
            payment.getTransactionReference(), payment.getNotes(), recordedBy);
        return invoiceMapper.toBillingInvoiceResponseDTO(saved);
    }

    /** The ledger row a cashier reconciles against: one per recorded payment, patient or staff. */
    private void savePaymentRow(BillingInvoice invoice, BigDecimal amount, PaymentMethod method,
            String reference, String notes, UUID recordedBy) {
        paymentTransactionRepository.save(PaymentTransaction.builder()
            .invoice(invoice)
            .amount(amount)
            .paymentDate(LocalDate.now(java.time.ZoneId.systemDefault()))
            .paymentMethod(method)
            .referenceNumber(blankToNull(reference))
            .notes(blankToNull(notes))
            .recordedBy(recordedBy)
            .build());
    }

    private static PaymentMethod parsePaymentMethod(String raw) {
        if (raw != null) {
            for (PaymentMethod candidate : PaymentMethod.values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
        }
        throw new BusinessException("Unsupported payment method.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Validate the invoice is this patient's and payable, then add the amount and settle the status. */
    private BillingInvoice applyPayment(UUID invoiceId, UUID patientId, BigDecimal amount) {
        BillingInvoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId));

        // ── Ownership check: ensure invoice belongs to this patient ──
        if (!invoice.getPatient().getId().equals(patientId)) {
            throw new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId);
        }

        // ── Validate the invoice is payable ──
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new BusinessException("This invoice is already fully paid.");
        }
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BusinessException("Cannot make a payment on a cancelled invoice.");
        }
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new BusinessException("This invoice has not been issued yet.");
        }

        BigDecimal currentPaid = invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal balanceDue = invoice.getTotalAmount().subtract(currentPaid);

        if (amount.compareTo(balanceDue) > 0) {
            throw new BusinessException("Payment amount (" + amount + ") exceeds balance due (" + balanceDue + ").");
        }

        // ── Apply payment ──
        BigDecimal newAmountPaid = currentPaid.add(amount);
        invoice.setAmountPaid(newAmountPaid);

        if (newAmountPaid.compareTo(invoice.getTotalAmount()) >= 0) {
            invoice.setStatus(InvoiceStatus.PAID);
        } else {
            invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
        }

        return invoiceRepository.save(invoice);
    }

    @Override
    @Transactional
    public BillingInvoiceResponseDTO recordStaffPayment(UUID invoiceId,
            com.example.hms.payload.dto.StaffPaymentRequestDTO payment, UUID recordedBy, Locale locale) {
        BillingInvoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId));
        // ── Tenant isolation: staff record payments on their own hospital's invoices only ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && invoice.getHospital() != null
                && !activeHospitalId.equals(invoice.getHospital().getId())) {
            throw new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId);
        }
        // The staff form may omit the method (the portal sends the amount
        // only): OTHER says "not stated" rather than guessing cash.
        PaymentMethod method = payment.getMethod() == null || payment.getMethod().isBlank()
            ? PaymentMethod.OTHER
            : parsePaymentMethod(payment.getMethod());
        BillingInvoice saved = applyPayment(invoiceId, invoice.getPatient().getId(), payment.getAmount());
        savePaymentRow(saved, payment.getAmount(), method, payment.getReference(), payment.getNotes(), recordedBy);
        return invoiceMapper.toBillingInvoiceResponseDTO(saved);
    }
}
