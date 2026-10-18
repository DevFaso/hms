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
import com.example.hms.enums.PaymentStatus;
import com.example.hms.enums.AuditEventType;
import com.example.hms.payload.dto.PaymentTransactionResponseDTO;
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
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BillingInvoiceServiceImpl.class);

    /** Optional so the unit tests that build the service by hand keep working. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditEventLogService auditEventLogService;

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
            InvoiceNumberAllocator invoiceNumberAllocator,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
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
        this.createTx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.replayTx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.replayTx.setReadOnly(true);
    }

    /** The create runs in its own transaction so a lost race on the key can be answered after it rolls back. */
    private final org.springframework.transaction.support.TransactionTemplate createTx;
    private final org.springframework.transaction.support.TransactionTemplate replayTx;

    @Override
    public BillingInvoiceResponseDTO createInvoice(BillingInvoiceRequestDTO dto, Locale locale) {
        // The invoice is written at the caller's acting hospital: a hospital
        // named in the body is a claim, not a scope. Global view must pick one.
        UUID hospitalId = actingScopeResolver.requirePinned();
        try {
            return createTx.execute(status -> createAt(hospitalId, dto));
        } catch (org.springframework.dao.DataIntegrityViolationException lostRace) {
            // QA D94: two submits with the same key both missed the lookup and the
            // second hit uk_bi_hospital_client_request. Its transaction is rolled
            // back; answer with the invoice the first one committed.
            if (dto.getClientRequestId() == null) {
                throw lostRace;
            }
            java.util.Optional<BillingInvoiceResponseDTO> winner = replayTx.execute(status -> invoiceRepository
                .findByHospital_IdAndClientRequestId(hospitalId, dto.getClientRequestId())
                .map(invoiceMapper::toBillingInvoiceResponseDTO));
            if (winner.isEmpty()) {
                throw lostRace;
            }
            return winner.get();
        }
    }

    private BillingInvoiceResponseDTO createAt(UUID hospitalId, BillingInvoiceRequestDTO dto) {
        Hospital hospital = hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFoundByIdentifier", hospitalId));

        // QA D94: a retry or a double submit with the same key is the same invoice.
        if (dto.getClientRequestId() != null) {
            var existing = invoiceRepository.findByHospital_IdAndClientRequestId(hospitalId, dto.getClientRequestId());
            if (existing.isPresent()) {
                return invoiceMapper.toBillingInvoiceResponseDTO(existing.get());
            }
        }

        // QA D183: a hospital or a number in the body that is not what the
        // server will write is refused, not silently rewritten.
        rejectHospitalMismatch(dto, hospital);
        if (dto.getInvoiceNumber() != null && !dto.getInvoiceNumber().isBlank()) {
            throw new BusinessException("billing.invoice.numberAssignedByServer");
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

    /** QA D183: the hospital never moves; one named in the body must be the invoice's own. */
    private static void rejectHospitalMismatch(BillingInvoiceRequestDTO dto, Hospital hospital) {
        String named = dto.getHospitalName();
        if (named == null || named.isBlank()) {
            return;
        }
        String actual = hospital.getName();
        if (actual == null || !actual.trim().equalsIgnoreCase(named.trim())) {
            throw new BusinessException("billing.invoice.hospitalMismatch");
        }
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
        if (encounter == null || encounter.getHospital() == null) {
            return null;
        }
        // QA D70: an encounter code is a claim; one of another hospital is refused, not dropped.
        if (!hospitalId.equals(encounter.getHospital().getId())) {
            throw new BusinessException("The encounter does not belong to this hospital.");
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
        // QA D101: a DRAFT invoice is not yet issued to the patient.
        return invoiceRepository.findByPatient_IdAndStatusNot(patientId, InvoiceStatus.DRAFT, pageable)
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
        // Reversed, rejected or pending rows no longer lock the invoice
        // (QA D140, D142): only money still counted does.
        boolean hasPayments = paid.signum() > 0;

        // The hospital never moves and the number stays the server's (D94);
        // a body that says otherwise is refused (D183).
        rejectHospitalMismatch(dto, invoice.getHospital());
        if (dto.getInvoiceNumber() != null && !dto.getInvoiceNumber().isBlank()
                && !dto.getInvoiceNumber().trim().equals(invoice.getInvoiceNumber())) {
            throw new BusinessException("billing.invoice.numberAssignedByServer");
        }
        applyPatientChange(invoice, dto, hospitalId, hasPayments);

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

    private void applyPatientChange(BillingInvoice invoice, BillingInvoiceRequestDTO dto,
                                    UUID hospitalId, boolean hasPayments) {
        boolean patientNamed = dto.getPatientId() != null
            || (dto.getPatientEmail() != null && !dto.getPatientEmail().isBlank());
        if (!patientNamed) {
            return;
        }
        Patient patient = resolvePatient(dto, hospitalId);
        if (invoice.getPatient() != null && patient.getId().equals(invoice.getPatient().getId())) {
            return;
        }
        if (hasPayments) {
            throw new BusinessException("billing.invoice.patientLockedByPayments");
        }
        invoice.setPatient(patient);
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
        if (requested == InvoiceStatus.PAID || requested == InvoiceStatus.PARTIALLY_PAID) {
            throw new BusinessException("billing.invoice.statusFollowsPayments");
        }
        // QA D142: a paid or partly paid invoice is cancelled or re-drafted
        // only once its payments are reversed; the message says so.
        if (hasPayments || current == InvoiceStatus.PAID || current == InvoiceStatus.PARTIALLY_PAID) {
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

        // QA D141: the confirmed payments are printed under the totals.
        List<PaymentTransaction> payments = paymentTransactionRepository.findByInvoice_IdOrderByCreatedAtAsc(invoiceId);
        byte[] pdf = pdfInvoiceService.generateInvoicePdf(invoice, items,
            payments != null ? payments : List.of(), effectiveLocale);
        return new InvoicePdfResponseDTO(pdf, invoice.getInvoiceNumber(), invoice.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public InvoicePdfResponseDTO getPaymentReceiptPdf(UUID invoiceId, UUID paymentId, Locale locale) {
        BillingInvoice invoice = invoiceRepository.findByIdWithRefs(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId));
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && invoice.getHospital() != null
                && !activeHospitalId.equals(invoice.getHospital().getId())) {
            throw new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId);
        }
        PaymentTransaction row = loadPaymentRow(invoice, paymentId);
        // A receipt says money was received: never for a pending, rejected or reversed row.
        if (row.getStatus() != PaymentStatus.CONFIRMED) {
            throw new BusinessException("billing.payment.notConfirmed");
        }
        Locale effectiveLocale = locale != null ? locale : LocaleContextHolder.getLocale();
        byte[] pdf = pdfInvoiceService.generateReceiptPdf(invoice, row, effectiveLocale);
        return new InvoicePdfResponseDTO(pdf, invoice.getInvoiceNumber() + "-receipt", invoice.getId());
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
        requireWholeAmount(payment.getAmount());
        // QA D140: nothing verifies a patient's declaration (no payment
        // provider callback exists), so it settles nothing. It is recorded as
        // PENDING and the invoice moves only when a cashier confirms it.
        BillingInvoice invoice = loadPayable(invoiceId, patientId, payment.getAmount());
        savePaymentRow(invoice, payment.getAmount(), method,
            payment.getTransactionReference(), payment.getNotes(), recordedBy, PaymentStatus.PENDING);
        return invoiceMapper.toBillingInvoiceResponseDTO(invoice);
    }

    /** The ledger row a cashier reconciles against: one per recorded payment, patient or staff. */
    private PaymentTransaction savePaymentRow(BillingInvoice invoice, BigDecimal amount, PaymentMethod method,
            String reference, String notes, UUID recordedBy, PaymentStatus status) {
        boolean confirmed = status == PaymentStatus.CONFIRMED;
        PaymentTransaction row = PaymentTransaction.builder()
            .invoice(invoice)
            .amount(amount)
            .paymentDate(LocalDate.now(java.time.ZoneId.systemDefault()))
            .paymentMethod(method)
            .referenceNumber(blankToNull(reference))
            .notes(blankToNull(notes))
            .recordedBy(recordedBy)
            .status(status)
            .confirmedBy(confirmed ? recordedBy : null)
            .confirmedAt(confirmed ? java.time.LocalDateTime.now() : null)
            .build();
        PaymentTransaction saved = paymentTransactionRepository.save(row);
        return saved != null ? saved : row;
    }

    /**
     * QA D184: the franc CFA has no minor unit, so a payment is a whole
     * number of francs. 0.50 is refused rather than stored.
     */
    private static void requireWholeAmount(BigDecimal amount) {
        if (amount != null && amount.stripTrailingZeros().scale() > 0) {
            throw new BusinessException("billing.payment.wholeAmountOnly");
        }
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
        // Locked: two payments on one invoice must not both add to a stale amount paid.
        BillingInvoice invoice = invoiceRepository.findLockedById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId));
        return settle(requirePayable(invoice, patientId, amount), amount);
    }

    /** The invoice, checked to be this patient's, issued, open, and owing at least {@code amount}. */
    private BillingInvoice loadPayable(UUID invoiceId, UUID patientId, BigDecimal amount) {
        BillingInvoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId));
        return requirePayable(invoice, patientId, amount);
    }

    /** {@code invoice} checked to be this patient's, issued, open, and owing at least {@code amount}. */
    private static BillingInvoice requirePayable(BillingInvoice invoice, UUID patientId, BigDecimal amount) {
        UUID invoiceId = invoice.getId();
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
        return invoice;
    }

    /** Add a confirmed amount and settle the status. */
    private BillingInvoice settle(BillingInvoice invoice, BigDecimal amount) {
        BigDecimal currentPaid = invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO;
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
        BillingInvoice invoice = invoiceRepository.findLockedById(invoiceId)
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
        requireWholeAmount(payment.getAmount());
        BillingInvoice saved = applyPayment(invoiceId, invoice.getPatient().getId(), payment.getAmount());
        PaymentTransaction row = savePaymentRow(saved, payment.getAmount(), method, payment.getReference(),
            payment.getNotes(), recordedBy, PaymentStatus.CONFIRMED);
        auditPaymentAfterCommit(AuditEventType.PAYMENT_POSTED, recordedBy, saved, row.getId(), "Payment recorded");
        return invoiceMapper.toBillingInvoiceResponseDTO(saved);
    }

    /** The invoice in the caller's hospital, or 404 (never 403) for one elsewhere. */
    private BillingInvoice loadInScope(UUID invoiceId) {
        return requireInScope(invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId)));
    }

    /** As {@link #loadInScope} but with the invoice row locked: every payment transition goes through here. */
    private BillingInvoice loadInScopeForUpdate(UUID invoiceId) {
        return requireInScope(invoiceRepository.findLockedById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId)));
    }

    private BillingInvoice requireInScope(BillingInvoice invoice) {
        UUID invoiceId = invoice.getId();
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && invoice.getHospital() != null
                && !activeHospitalId.equals(invoice.getHospital().getId())) {
            throw new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId);
        }
        return invoice;
    }

    private PaymentTransaction loadPaymentRow(BillingInvoice invoice, UUID paymentId) {
        return paymentTransactionRepository.findByIdAndInvoice_Id(paymentId, invoice.getId())
            .orElseThrow(() -> new ResourceNotFoundException("billing.payment.notFound"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentTransactionResponseDTO> listPayments(UUID invoiceId) {
        BillingInvoice invoice = loadInScope(invoiceId);
        return paymentTransactionRepository.findByInvoice_IdOrderByCreatedAtAsc(invoice.getId()).stream()
            .map(PaymentTransactionResponseDTO::from)
            .toList();
    }

    @Override
    @Transactional
    public BillingInvoiceResponseDTO confirmPayment(UUID invoiceId, UUID paymentId, UUID staffId, Locale locale) {
        BillingInvoice invoice = loadInScopeForUpdate(invoiceId);
        PaymentTransaction row = loadPaymentRow(invoice, paymentId);
        if (row.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException("billing.payment.notPending");
        }
        // The balance may have moved since the patient declared it: the same
        // checks as a cashier's payment apply now.
        requirePayable(invoice, invoice.getPatient().getId(), row.getAmount());
        claimTransition(row, invoice, PaymentStatus.PENDING, PaymentStatus.CONFIRMED, "billing.payment.notPending");
        BillingInvoice saved = settle(invoice, row.getAmount());
        row.setStatus(PaymentStatus.CONFIRMED);
        row.setConfirmedBy(staffId);
        row.setConfirmedAt(java.time.LocalDateTime.now());
        paymentTransactionRepository.save(row);
        auditPaymentAfterCommit(AuditEventType.PAYMENT_POSTED, staffId, saved, row.getId(), "Pending payment confirmed");
        return invoiceMapper.toBillingInvoiceResponseDTO(saved);
    }

    @Override
    @Transactional
    public BillingInvoiceResponseDTO rejectPayment(UUID invoiceId, UUID paymentId, String reason, UUID staffId,
            Locale locale) {
        BillingInvoice invoice = loadInScopeForUpdate(invoiceId);
        PaymentTransaction row = loadPaymentRow(invoice, paymentId);
        if (row.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException("billing.payment.notPending");
        }
        String why = requireReason(reason);
        claimTransition(row, invoice, PaymentStatus.PENDING, PaymentStatus.REJECTED, "billing.payment.notPending");
        row.setStatus(PaymentStatus.REJECTED);
        row.setReversedBy(staffId);
        row.setReversedAt(java.time.LocalDateTime.now());
        row.setReversalReason(why);
        paymentTransactionRepository.save(row);
        auditPaymentAfterCommit(AuditEventType.PAYMENT_POSTED, staffId, invoice, row.getId(), "Pending payment rejected");
        return invoiceMapper.toBillingInvoiceResponseDTO(invoice);
    }

    /**
     * QA D142: a confirmed payment is reversed (refunded, or entered in
     * error) by billing staff with a reason. The row stays in the ledger as
     * REVERSED, and the amount paid and the status are recomputed from what
     * is still counted, so the totals stay consistent.
     */
    @Override
    @Transactional
    public BillingInvoiceResponseDTO reversePayment(UUID invoiceId, UUID paymentId, String reason, UUID staffId,
            Locale locale) {
        BillingInvoice invoice = loadInScopeForUpdate(invoiceId);
        PaymentTransaction row = loadPaymentRow(invoice, paymentId);
        if (row.getStatus() != PaymentStatus.CONFIRMED) {
            throw new BusinessException("billing.payment.notConfirmed");
        }
        String why = requireReason(reason);
        BigDecimal paid = invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal remaining = paid.subtract(row.getAmount());
        if (remaining.signum() < 0) {
            // A ledger that disagrees with the invoice is not settled by a guess.
            throw new BusinessException("billing.payment.ledgerMismatch");
        }
        claimTransition(row, invoice, PaymentStatus.CONFIRMED, PaymentStatus.REVERSED, "billing.payment.notConfirmed");
        invoice.setAmountPaid(remaining);
        if (remaining.signum() == 0) {
            invoice.setStatus(InvoiceStatus.SENT);
        } else {
            invoice.setStatus(remaining.compareTo(invoice.getTotalAmount()) >= 0
                ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID);
        }
        row.setStatus(PaymentStatus.REVERSED);
        row.setReversedBy(staffId);
        row.setReversedAt(java.time.LocalDateTime.now());
        row.setReversalReason(why);
        paymentTransactionRepository.save(row);
        BillingInvoice saved = invoiceRepository.save(invoice);
        auditPaymentAfterCommit(AuditEventType.REFUND_ISSUED, staffId, saved, row.getId(), "Payment reversed");
        return invoiceMapper.toBillingInvoiceResponseDTO(saved);
    }

    /**
     * The status check above is a read; this conditional UPDATE is the
     * guarantee. A concurrent or repeated transition on the same row updates
     * nothing and is refused, so its amount is never counted twice.
     */
    private void claimTransition(PaymentTransaction row, BillingInvoice invoice, PaymentStatus from,
            PaymentStatus to, String refusalKey) {
        if (paymentTransactionRepository.transitionStatus(row.getId(), invoice.getId(), from, to) != 1) {
            throw new BusinessException(refusalKey);
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("billing.payment.reasonRequired");
        }
        String trimmed = reason.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }

    /** Ids only in the audit row: no names, no amounts tied to a person (no PHI). */
    private void auditPaymentAfterCommit(AuditEventType type, UUID actorId, BillingInvoice invoice, UUID paymentId,
            String what) {
        if (auditEventLogService == null) {
            return;
        }
        String invoiceIdText = String.valueOf(invoice.getId());
        com.example.hms.utility.TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                    .eventType(type)
                    .status(com.example.hms.enums.AuditStatus.SUCCESS)
                    .userId(actorId)
                    .eventDescription(what + ": payment " + paymentId + " on invoice " + invoiceIdText)
                    .entityType("BillingInvoice")
                    .resourceId(invoiceIdText)
                    .build());
            } catch (RuntimeException ex) {
                LOG.warn("Payment audit not written for invoice {}: {}", invoiceIdText, ex.getClass().getSimpleName());
            }
        });
    }

    @Override
    @Transactional
    public void recordCheckInCopay(Encounter encounter, BigDecimal amount, UUID recordedBy) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        requireWholeAmount(amount);
        Hospital hospital = encounter.getHospital();
        LocalDate today = LocalDate.now(java.time.ZoneId.systemDefault());
        BillingInvoice invoice = BillingInvoice.builder()
            .patient(encounter.getPatient())
            .hospital(hospital)
            .encounter(encounter)
            .invoiceNumber(allocateNumber(hospital))
            .invoiceDate(today)
            .dueDate(today)
            .totalAmount(amount)
            .amountPaid(BigDecimal.ZERO)
            .status(InvoiceStatus.SENT)
            .build();
        BillingInvoice saved = invoiceRepository.saveAndFlush(invoice);
        BillingInvoice settled = settle(saved, amount);
        PaymentTransaction row = savePaymentRow(settled, amount, PaymentMethod.CASH, null, null, recordedBy,
            PaymentStatus.CONFIRMED);
        auditPaymentAfterCommit(AuditEventType.PAYMENT_POSTED, recordedBy, settled, row.getId(), "Check-in copay recorded");
    }
}
