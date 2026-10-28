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
    private AuditEventLogService auditEventLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAuditEventLogService(AuditEventLogService auditEventLogService) {
        this.auditEventLogService = auditEventLogService;
    }

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

    /**
     * QA D537: reads the creator for the create response. createdByUser is a
     * read-only association, so the freshly built entity had it null and the
     * create response said createdByName=null.
     */
    private com.example.hms.repository.UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUserRepository(com.example.hms.repository.UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    private void attachCreator(BillingInvoice invoice, UUID creatorId) {
        if (creatorId != null && userRepository != null) {
            userRepository.findById(creatorId).ifPresent(invoice::setCreatedByUser);
        }
    }

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
        // QA D307: a typed total is whole francs, like the payments that settle it.
        requireWholeAmount(dto.getTotalAmount());
        requireCoherentDates(dto.getInvoiceDate(), dto.getDueDate(), null, hospital);
        Patient patient = resolvePatient(dto, hospitalId);
        Encounter encounter = resolveEncounter(dto, hospitalId);
        // QA D403: the visit billed must be this patient's visit.
        rejectEncounterOfOtherPatient(encounter, patient);
        rejectUnconfirmedSecondInvoice(encounter, dto);

        BillingInvoice invoice = invoiceMapper.toBillingInvoice(dto, patient, hospital, encounter);
        // QA D91: nothing is paid until a payment is recorded.
        invoice.setAmountPaid(BigDecimal.ZERO);
        invoice.setTotalAmount(dto.getTotalAmount() != null ? dto.getTotalAmount() : BigDecimal.ZERO);
        invoice.setStatus(requestedStatusOnCreate(dto.getStatus()));
        requireIssuableTotal(invoice.getStatus(), invoice.getTotalAmount());
        invoice.setClientRequestId(dto.getClientRequestId());
        // QA D428: no auditing listener stamps it, so the creator is set here.
        invoice.setCreatedBy(roleValidator.getCurrentUserId());
        attachCreator(invoice, invoice.getCreatedBy());
        // QA D94: the server numbers the invoice; a typed number is ignored.
        invoice.setInvoiceNumber(allocateNumber(hospital));

        BillingInvoice saved = invoiceRepository.saveAndFlush(invoice);
        creditCheckInCopays(saved);
        return invoiceMapper.toBillingInvoiceResponseDTO(saved);
    }

    /**
     * QA D272: an issued visit invoice takes the copay whether or not it has
     * been part-paid; a copay paid after the invoice was issued is taken when
     * the clerk records the next payment on it. A DRAFT is not issued yet.
     */
    private static final java.util.Set<InvoiceStatus> COPAY_CREDITABLE =
        java.util.EnumSet.of(InvoiceStatus.SENT, InvoiceStatus.PARTIALLY_PAID);

    /**
     * QA D210: once a visit's invoice is issued, the copay already paid at
     * check-in for that visit is moved onto it as a payment, and the
     * stand-alone copay invoice is closed, so the patient is not billed twice.
     * A copay larger than what the invoice still owes stays where it is.
     */
    private void creditCheckInCopays(BillingInvoice target) {
        if (target.getEncounter() == null || target.getEncounter().getId() == null
                || target.isCheckInCopay() || !COPAY_CREDITABLE.contains(target.getStatus())) {
            return;
        }
        List<BillingInvoice> copays = invoiceRepository
            .findByEncounter_IdAndCheckInCopayTrueAndStatus(target.getEncounter().getId(), InvoiceStatus.PAID);
        if (copays == null) {
            return;
        }
        for (BillingInvoice candidate : copays) {
            BillingInvoice copay = lockCreditableCopay(candidate, target);
            if (copay != null) {
                moveCopayOnto(copay, target);
            }
        }
    }

    /** The copay, locked and re-read, when it may be credited to the target; else null. */
    private BillingInvoice lockCreditableCopay(BillingInvoice candidate, BillingInvoice target) {
        if (candidate.getId() == null || candidate.getId().equals(target.getId())) {
            return null;
        }
        // Locked and re-read: two invoices issued at once must not both take the same copay.
        BillingInvoice copay = invoiceRepository.findLockedById(candidate.getId()).orElse(null);
        if (copay == null || copay.getStatus() != InvoiceStatus.PAID || copay.getPatient() == null
                || target.getPatient() == null || !copay.getPatient().getId().equals(target.getPatient().getId())) {
            return null;
        }
        BigDecimal credit = copayCredit(copay);
        BigDecimal paid = target.getAmountPaid() != null ? target.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal owed = (target.getTotalAmount() != null ? target.getTotalAmount() : BigDecimal.ZERO).subtract(paid);
        return credit.signum() <= 0 || credit.compareTo(owed) > 0 ? null : copay;
    }

    private static BigDecimal copayCredit(BillingInvoice copay) {
        return copay.getAmountPaid() != null ? copay.getAmountPaid() : BigDecimal.ZERO;
    }

    private void moveCopayOnto(BillingInvoice copay, BillingInvoice target) {
        BigDecimal credit = copayCredit(copay);
        for (PaymentTransaction row : paymentTransactionRepository.findByInvoice_IdOrderByCreatedAtAsc(copay.getId())) {
            if (row.getStatus() == PaymentStatus.CONFIRMED) {
                row.setInvoice(target);
                paymentTransactionRepository.save(row);
            }
        }
        copay.setAmountPaid(BigDecimal.ZERO);
        copay.setStatus(InvoiceStatus.CANCELLED);
        copay.setCreditedToInvoice(target); // QA D428
        invoiceRepository.save(copay);
        settle(target, credit);
    }

    /** QA D183: the hospital never moves; one named in the body must be the invoice's own. */
    private static void rejectHospitalMismatch(BillingInvoiceRequestDTO dto, Hospital hospital) {
        // QA D230: a hospital id in the body is a claim; one that is not the
        // acting hospital is refused, never rewritten.
        if (dto.getHospitalId() != null && !dto.getHospitalId().equals(hospital.getId())) {
            throw new BusinessException("billing.invoice.hospitalMismatch");
        }
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
            key = dto.getPatientEmail().trim();
            patient = patientByLegacyKey(dto.getPatientEmail().trim(), hospitalId);
        } else {
            throw new BusinessException("billing.invoice.patientRequired");
        }
        // A patient of another hospital gets the answer of an unknown one.
        if (patient == null || !registrationRepository.existsByPatientIdAndHospitalId(patient.getId(), hospitalId)) {
            throw new ResourceNotFoundException("patient.notFoundByIdentifier", key);
        }
        return patient;
    }

    /**
     * QA D269: most patients here have a phone number and no e-mail, so the
     * legacy key also takes the patient's id or their MRN at this hospital
     * before the e-mail/username.
     */
    private Patient patientByLegacyKey(String key, UUID hospitalId) {
        UUID asId = parseUuid(key);
        if (asId != null) {
            return patientRepository.findById(asId).orElse(null);
        }
        List<Patient> byMrn = registrationRepository.findActiveByHospitalIdAndIdentifier(hospitalId, key).stream()
            .filter(r -> r.getMrn() != null && r.getMrn().equalsIgnoreCase(key))
            .map(com.example.hms.model.PatientHospitalRegistration::getPatient)
            .distinct()
            .toList();
        if (byMrn.size() == 1) {
            return byMrn.get(0);
        }
        return patientRepository.findByUsernameOrEmail(key).orElse(null);
    }

    private static UUID parseUuid(String value) {
        if (value.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /**
     * QA D529: a second invoice for a stay already invoiced (and paid) was
     * created with no warning, billing the patient twice. A check-in copay is
     * folded into the visit invoice and does not count; a cancelled invoice
     * does not either.
     */
    private void rejectUnconfirmedSecondInvoice(Encounter encounter, BillingInvoiceRequestDTO dto) {
        if (encounter == null || encounter.getId() == null || Boolean.TRUE.equals(dto.getSupplementary())) {
            return;
        }
        invoiceRepository.findByEncounter_IdAndStatusNot(encounter.getId(), InvoiceStatus.CANCELLED).stream()
            .filter(existing -> !existing.isCheckInCopay())
            .findFirst()
            .ifPresent(existing -> {
                throw new com.example.hms.exception.ConflictException(com.example.hms.utility.MessageUtil.resolve("billing.invoice.encounterAlreadyInvoiced",
                    existing.getInvoiceNumber(), String.valueOf(existing.getStatus())));
            });
    }

    private static void rejectEncounterOfOtherPatient(Encounter encounter, Patient patient) {
        if (encounter == null || patient == null || encounter.getPatient() == null) {
            return;
        }
        if (!java.util.Objects.equals(encounter.getPatient().getId(), patient.getId())) {
            throw new BusinessException("billing.encounter.otherPatient");
        }
    }

    /** The encounter named by code, only when it belongs to the invoicing hospital. */
    private Encounter resolveEncounter(BillingInvoiceRequestDTO dto, UUID hospitalId) {
        if (dto.getEncounterReference() == null || dto.getEncounterReference().isBlank()) {
            return null;
        }
        // QA D219: an unknown code is refused, not silently dropped.
        Encounter encounter = encounterRepository.findByCode(dto.getEncounterReference().trim())
            .filter(e -> e.getHospital() != null)
            .orElseThrow(() -> new BusinessException("billing.invoice.encounterNotFound"));
        // QA D70: an encounter code is a claim; one of another hospital is refused, not dropped.
        if (!hospitalId.equals(encounter.getHospital().getId())) {
            throw new BusinessException("billing.encounter.otherHospital");
        }
        return encounter;
    }

    /**
     * QA D767: an invoice issued at 0 showed as sent and outstanding forever
     * (nothing can ever be paid on it). Issuing needs a positive total; a
     * draft may still be 0 while its lines are added.
     */
    private static void requireIssuableTotal(InvoiceStatus status, BigDecimal total) {
        if (status == InvoiceStatus.SENT && (total == null || total.signum() <= 0)) {
            throw new BusinessException("billing.invoice.zeroTotal");
        }
    }

    /** QA D768: methods whose money can only be traced by its transaction reference. */
    private static final java.util.Set<PaymentMethod> REFERENCE_REQUIRED =
        java.util.EnumSet.of(PaymentMethod.MOBILE_MONEY, PaymentMethod.INSURANCE, PaymentMethod.BANK_TRANSFER);

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
            .map(this::toPortalResponse);
    }

    private com.example.hms.mapper.InvoiceItemMapper invoiceItemMapper;

    /** QA D974: the adjustments ledger. Setter-injected, like the other late additions. */
    private com.example.hms.repository.InvoiceAdjustmentRepository adjustmentRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAdjustmentRepository(com.example.hms.repository.InvoiceAdjustmentRepository adjustmentRepository) {
        this.adjustmentRepository = adjustmentRepository;
    }

    /** QA D974: an adjustment lowers what is owed on an issued invoice; a draft's lines are edited instead. */
    private static final java.util.Set<InvoiceStatus> ADJUSTABLE =
        java.util.EnumSet.of(InvoiceStatus.SENT, InvoiceStatus.PARTIALLY_PAID);

    @Override
    @Transactional
    public BillingInvoiceResponseDTO addAdjustment(UUID invoiceId,
                                                   com.example.hms.payload.dto.InvoiceAdjustmentRequestDTO request,
                                                   UUID staffId, Locale locale) {
        requireAdjustmentLedger();
        BillingInvoice invoice = loadInScopeForUpdate(invoiceId);
        if (!ADJUSTABLE.contains(invoice.getStatus())) {
            throw new BusinessException("billing.adjustment.notIssued");
        }
        String reason = request.getReason() != null ? request.getReason().trim() : "";
        if (reason.isEmpty()) {
            throw new BusinessException("billing.adjustment.reasonRequired");
        }
        InvoiceItem line = null;
        BigDecimal amount = request.getAmount();
        boolean creditNote = request.getType() == com.example.hms.enums.InvoiceAdjustmentType.CREDIT_NOTE;
        if (creditNote && request.getInvoiceItemId() == null) {
            throw new BusinessException("billing.adjustment.lineRequired");
        }
        BigDecimal total = invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO;
        // A discount or a waiver may name a line too: it is then held against that line.
        if (request.getInvoiceItemId() != null) {
            line = invoiceItemRepository.findById(request.getInvoiceItemId())
                .filter(item -> item.getBillingInvoice() != null && invoiceId.equals(item.getBillingInvoice().getId()))
                .orElseThrow(() -> new BusinessException("billing.adjustment.lineNotOnInvoice"));
            BigDecimal lineTotal = line.getTotalPrice() != null ? line.getTotalPrice() : BigDecimal.ZERO;
            BigDecimal credited = adjustmentRepository.sumByInvoiceItemId(line.getId());
            BigDecimal left = lineTotal.subtract(credited != null ? credited : BigDecimal.ZERO);
            if (left.signum() <= 0) {
                throw new BusinessException("billing.adjustment.lineFullyCredited");
            }
            if (amount == null && creditNote) {
                BigDecimal alreadyPaid = invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO;
                BigDecimal declared = paymentTransactionRepository.sumAmountByInvoiceAndStatus(invoice.getId(),
                    PaymentStatus.PENDING);
                BigDecimal room = total.subtract(alreadyPaid).subtract(declared != null ? declared : BigDecimal.ZERO);
                amount = left.min(room);
            }
            if (amount != null && amount.compareTo(left) > 0) {
                throw new BusinessException("billing.adjustment.moreThanLine", formatFrancs(left));
            }
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("billing.adjustment.amountPositive");
        }
        requireWholeAmount(amount);
        // Earlier discounts may already have lowered the total under the line.
        if (amount.compareTo(total) > 0) {
            throw new BusinessException("billing.adjustment.moreThanTotal", formatFrancs(total));
        }
        BigDecimal paid = invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal newTotal = total.subtract(amount);
        // Money already received is never written off silently: reverse the payment first.
        if (newTotal.compareTo(paid) < 0) {
            throw new BusinessException("billing.adjustment.belowPaid");
        }
        // QA D724: nor is a payment the patient declared and the cashier has not
        // confirmed yet; lowering the total under it would orphan that payment.
        BigDecimal pending = paymentTransactionRepository.sumAmountByInvoiceAndStatus(invoice.getId(),
            PaymentStatus.PENDING);
        if (pending != null && newTotal.compareTo(paid.add(pending)) < 0) {
            throw new BusinessException("billing.adjustment.belowPending");
        }
        adjustmentRepository.save(com.example.hms.model.InvoiceAdjustment.builder()
            .invoice(invoice)
            .invoiceItem(line)
            .type(request.getType())
            .amount(amount)
            .reason(reason)
            .createdBy(staffId)
            .build());
        invoice.setTotalAmount(newTotal);
        if (paid.compareTo(newTotal) >= 0) {
            invoice.setStatus(InvoiceStatus.PAID);
        } else if (paid.signum() > 0) {
            invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
        }
        BillingInvoice saved = invoiceRepository.save(invoice);
        auditAdjustmentAfterCommit(staffId, saved, request.getType(), amount);
        return toPortalResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.InvoiceAdjustmentResponseDTO> listAdjustments(UUID invoiceId) {
        requireAdjustmentLedger();
        BillingInvoice invoice = loadInScope(invoiceId);
        return adjustmentRepository.findByInvoice_IdOrderByCreatedAtAsc(invoice.getId()).stream()
            .map(com.example.hms.payload.dto.InvoiceAdjustmentResponseDTO::from)
            .toList();
    }

    /** The ledger is a Spring bean in every running context; a test that builds the service by hand sets it. */
    private void requireAdjustmentLedger() {
        java.util.Objects.requireNonNull(adjustmentRepository, "adjustmentRepository");
    }

    /** Whole francs grouped as the reader writes them ("4 000", "4,000", "4.000"). */
    private static String formatFrancs(BigDecimal amount) {
        java.text.NumberFormat format = java.text.NumberFormat.getIntegerInstance(LocaleContextHolder.getLocale());
        format.setGroupingUsed(true);
        return format.format(amount.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact())
            .replace('\u00a0', ' ').replace('\u202f', ' ');
    }

    /** QA D974: who lowered which invoice and how (ids and the kind only, as the payment audit: no amount, no reason text). */
    private void auditAdjustmentAfterCommit(UUID actorId, BillingInvoice invoice,
                                            com.example.hms.enums.InvoiceAdjustmentType type, BigDecimal amount) {
        if (auditEventLogService == null) {
            return;
        }
        String invoiceIdText = String.valueOf(invoice.getId());
        com.example.hms.utility.TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.INVOICE_UPDATED)
                    .status(com.example.hms.enums.AuditStatus.SUCCESS)
                    .userId(actorId)
                    .eventDescription("Invoice adjustment " + type + " on invoice " + invoiceIdText)
                    .entityType("BillingInvoice")
                    .resourceId(invoiceIdText)
                    .build());
            } catch (RuntimeException ex) {
                LOG.warn("Adjustment audit not written for invoice {}: {}", invoiceIdText, ex.getClass().getSimpleName());
            }
        });
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setInvoiceItemMapper(com.example.hms.mapper.InvoiceItemMapper invoiceItemMapper) {
        this.invoiceItemMapper = invoiceItemMapper;
    }

    /**
     * QA D982: the patient's own issued invoice. Another patient's, or one
     * still in draft, answers exactly as a missing one.
     */
    private BillingInvoice requirePortalVisible(BillingInvoice invoice, UUID patientId) {
        if (invoice.getPatient() == null || !invoice.getPatient().getId().equals(patientId)
                || invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoice.getId());
        }
        return invoice;
    }

    @Override
    @Transactional(readOnly = true)
    public com.example.hms.payload.dto.portal.PortalInvoiceDetailDTO getPortalInvoiceDetail(UUID invoiceId, UUID patientId) {
        BillingInvoice invoice = requirePortalVisible(invoiceRepository.findByIdWithRefs(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId)), patientId);
        List<com.example.hms.payload.dto.InvoiceItemResponseDTO> items = invoiceItemRepository
            .findByBillingInvoiceId(invoiceId).stream()
            .map((invoiceItemMapper != null ? invoiceItemMapper : new com.example.hms.mapper.InvoiceItemMapper())
                ::toInvoiceItemResponseDTO)
            .toList();
        List<PaymentTransactionResponseDTO> payments = paymentTransactionRepository
            .findByInvoice_IdOrderByCreatedAtAsc(invoiceId).stream()
            .map(PaymentTransactionResponseDTO::from)
            // The cashier's notes and reversal reasons stay with the cashier.
            .map(row -> new PaymentTransactionResponseDTO(row.id(), row.amount(), row.paymentDate(),
                row.paymentMethod(), row.referenceNumber(), null, row.status(), null,
                row.reversedAt(), row.createdAt()))
            .toList();
        List<com.example.hms.payload.dto.InvoiceAdjustmentResponseDTO> adjustments = adjustmentRepository == null
            ? List.of()
            : adjustmentRepository.findByInvoice_IdOrderByCreatedAtAsc(invoiceId).stream()
                .map(com.example.hms.payload.dto.InvoiceAdjustmentResponseDTO::from)
                .map(a -> new com.example.hms.payload.dto.InvoiceAdjustmentResponseDTO(a.id(), a.type(), a.amount(),
                    null, a.invoiceItemId(), a.invoiceItemDescription(), a.createdAt()))
                .toList();
        return new com.example.hms.payload.dto.portal.PortalInvoiceDetailDTO(toPortalResponse(invoice), items, payments,
            adjustments);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoicePdfResponseDTO getPortalInvoicePdf(UUID invoiceId, UUID patientId, Locale locale) {
        BillingInvoice invoice = requirePortalVisible(invoiceRepository.findByIdWithRefs(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId)), patientId);
        // QA D974: the clerk's reason stays with billing, on paper too.
        return renderInvoicePdf(invoice, locale, false);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoicePdfResponseDTO getPortalReceiptPdf(UUID invoiceId, UUID paymentId, UUID patientId, Locale locale) {
        BillingInvoice invoice = requirePortalVisible(invoiceRepository.findByIdWithRefs(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId)), patientId);
        return renderReceiptPdf(invoice, paymentId, locale);
    }

    /** QA D421: the patient's invoice with what they already declared and staff have not confirmed. */
    private BillingInvoiceResponseDTO toPortalResponse(BillingInvoice invoice) {
        BillingInvoiceResponseDTO dto = invoiceMapper.toBillingInvoiceResponseDTO(invoice);
        if (dto == null) {
            return null;
        }
        BigDecimal pending = paymentTransactionRepository.sumAmountByInvoiceAndStatus(invoice.getId(),
            PaymentStatus.PENDING);
        dto.setPendingDeclaredAmount(pending != null ? pending : BigDecimal.ZERO);
        return dto;
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
        // Locked like a payment declaration, so a declaration cannot slip in
        // between the patient-lock check and the reassignment.
        BillingInvoice invoice = invoiceRepository.findLockedById(id)
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
        // A pending or confirmed declaration belongs to the patient who made
        // it: the invoice cannot move to another patient while one is open,
        // or confirming it would settle the new patient's bill with it.
        boolean patientLocked = hasPayments || paymentTransactionRepository.existsByInvoice_IdAndStatusIn(id,
            java.util.List.of(PaymentStatus.PENDING, PaymentStatus.CONFIRMED));
        // QA D614: an issued invoice (anything past DRAFT) is a document the
        // patient may hold; it is cancelled and re-issued, never re-addressed.
        applyPatientChange(invoice, dto, hospitalId, patientLocked,
            invoice.getStatus() != InvoiceStatus.DRAFT);

        // QA D656: an issued invoice is immutable apart from its status
        // (cancel) and payments/credits: its date was moved and its total
        // retyped on a SENT invoice. The edit form echoes the stored values,
        // so only a value that differs is refused.
        // The status move is judged first, so a refused move says why.
        InvoiceStatus nextStatus = requestedStatusOnUpdate(invoice.getStatus(), dto.getStatus(), hasPayments);
        boolean wasDraft = invoice.getStatus() == InvoiceStatus.DRAFT;
        if (!wasDraft) {
            requireIssuedContentUnchanged(invoice, dto);
        }
        // QA D303/D308: a body without an encounter reference keeps the visit
        // link (the QA's PUT and older clients omit it); a blank one unlinks.
        if (dto.getEncounterReference() != null) {
            invoice.setEncounter(resolveEncounter(dto, hospitalId));
        }
        // QA D403: checked after both moves, so a new patient with the kept
        // visit, or a new visit with the kept patient, is refused alike.
        rejectEncounterOfOtherPatient(invoice.getEncounter(), invoice.getPatient());
        requireCoherentDates(dto.getInvoiceDate(), dto.getDueDate(), invoice.getInvoiceDate(),
            invoice.getHospital());
        invoice.setInvoiceDate(dto.getInvoiceDate());
        invoice.setDueDate(dto.getDueDate());
        // QA D72: with lines the total is their sum; without, the typed amount.
        if (dto.getTotalAmount() != null && !invoiceRepository.hasItems(id)) {
            requireWholeAmount(dto.getTotalAmount()); // QA D307
            if (dto.getTotalAmount().compareTo(paid) < 0) {
                throw new BusinessException("billing.invoice.totalBelowPaid");
            }
            invoice.setTotalAmount(dto.getTotalAmount());
        }
        // QA D91: amountPaid is never taken from the form.
        if (wasDraft) {
            requireIssuableTotal(nextStatus, invoice.getTotalAmount());
        }
        invoice.setStatus(nextStatus);
        // D656: an issued invoice keeps its notes (checked unchanged above).
        if (wasDraft) {
            invoice.setNotes(dto.getNotes());
        }

        BillingInvoice saved = invoiceRepository.save(invoice);
        // QA D210: issuing the visit invoice takes over the check-in copay.
        creditCheckInCopays(saved);
        return invoiceMapper.toBillingInvoiceResponseDTO(saved);
    }

    /**
     * QA D656: the dates, total, visit and notes of an issued invoice do not
     * change (round-9 re-test: notes were still editable on a SENT invoice).
     * Cancel or credit are the only ways forward.
     */
    private void requireIssuedContentUnchanged(BillingInvoice invoice, BillingInvoiceRequestDTO dto) {
        boolean changed = !java.util.Objects.equals(dto.getInvoiceDate(), invoice.getInvoiceDate())
            || !java.util.Objects.equals(dto.getDueDate(), invoice.getDueDate())
            || (dto.getTotalAmount() != null && invoice.getTotalAmount() != null
                && dto.getTotalAmount().compareTo(invoice.getTotalAmount()) != 0)
            || encounterChanged(invoice, dto)
            || notesChanged(invoice, dto);
        if (changed) {
            throw new BusinessException("billing.invoice.issuedLocked");
        }
    }

    /** D656: the edit form echoes the stored notes; null means "not sent". */
    private static boolean notesChanged(BillingInvoice invoice, BillingInvoiceRequestDTO dto) {
        if (dto.getNotes() == null) {
            return false;
        }
        String stored = invoice.getNotes() == null ? "" : invoice.getNotes().trim();
        return !dto.getNotes().trim().equals(stored);
    }

    private static boolean encounterChanged(BillingInvoice invoice, BillingInvoiceRequestDTO dto) {
        if (dto.getEncounterReference() == null) {
            return false;
        }
        String requested = dto.getEncounterReference().trim();
        if (invoice.getEncounter() == null) {
            return !requested.isEmpty();
        }
        return !requested.equals(String.valueOf(invoice.getEncounter().getId()))
            && !requested.equals(invoice.getEncounter().getCode());
    }

    private void applyPatientChange(BillingInvoice invoice, BillingInvoiceRequestDTO dto,
                                    UUID hospitalId, boolean hasPayments, boolean issued) {
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
        if (issued) {
            throw new BusinessException("billing.invoice.patientLockedIssued");
        }
        invoice.setPatient(patient);
    }

    /**
     * QA D612: a due date before the invoice date reached the database CHECK
     * and came back as a 500. QA D632: an invoice cannot be dated in the
     * future. QA D842: the D632 check allowed one day of grace for a clerk
     * ahead of the server's clock, so tomorrow's date was accepted; "today"
     * is now the HOSPITAL's day (its organisation's time zone), with no
     * grace. An update that keeps the stored date is not re-judged by
     * today's clock.
     */
    private static void requireCoherentDates(LocalDate invoiceDate, LocalDate dueDate, LocalDate storedInvoiceDate,
                                             Hospital hospital) {
        if (invoiceDate != null && dueDate != null && dueDate.isBefore(invoiceDate)) {
            throw new BusinessException("billing.invoice.dueBeforeInvoiceDate");
        }
        if (invoiceDate != null && !invoiceDate.equals(storedInvoiceDate)
                && invoiceDate.isAfter(com.example.hms.utility.HospitalCalendar.today(hospital))) {
            throw new BusinessException("billing.invoice.dateInFuture");
        }
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
        // QA D613: an issued invoice holds a number in the hospital's
        // sequence; deleting it left a gap. Only a DRAFT is deleted; an issued
        // one is cancelled (status CANCELLED), which keeps its number.
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new BusinessException("billing.invoice.deleteOnlyDraft");
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
        // QA D613/D614 (#926 review): an issued invoice holds its number and
        // its patient; going back to DRAFT would reopen both (delete, patient
        // swap). Once out of DRAFT it only moves on, or to CANCELLED.
        if (requested == InvoiceStatus.DRAFT) {
            throw new BusinessException("billing.invoice.noReturnToDraft");
        }
        // QA D142: a paid or partly paid invoice is cancelled or re-drafted
        // only once its payments are reversed; the message says so.
        // QA D974: an invoice a waiver settled took no money; it may still be
        // cancelled (and re-issued) when the waiver was a mistake.
        if (current == InvoiceStatus.PAID && !hasPayments) {
            if (requested != InvoiceStatus.CANCELLED) {
                throw new BusinessException("billing.invoice.statusLockedByPayments");
            }
            return requested;
        }
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
        BillingInvoice saved = invoiceRepository.save(invoice);
        // QA D210: a visit invoice issued at 0 and priced later by its lines takes the copay now.
        creditCheckInCopays(saved);
    }

    @Override
    @Transactional
    public void creditCheckInCopays(UUID invoiceId) {
        invoiceRepository.findById(invoiceId).ifPresent(this::creditCheckInCopays);
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
        return renderInvoicePdf(invoice, locale, true);
    }

    /** The invoice PDF of an invoice the caller may already read; the adjustment reasons for staff only. */
    private InvoicePdfResponseDTO renderInvoicePdf(BillingInvoice invoice, Locale locale, boolean withReasons) {
        UUID invoiceId = invoice.getId();
        List<InvoiceItem> items = invoiceItemRepository.findByBillingInvoiceId(invoiceId);
        Locale effectiveLocale = locale != null ? locale : LocaleContextHolder.getLocale();

        // QA D141: the confirmed payments are printed under the totals.
        List<PaymentTransaction> payments = paymentTransactionRepository.findByInvoice_IdOrderByCreatedAtAsc(invoiceId);
        // QA D974: the adjustments are on the paper too, so lines and total agree.
        List<com.example.hms.model.InvoiceAdjustment> adjustments = adjustmentRepository != null
            ? adjustmentRepository.findByInvoice_IdOrderByCreatedAtAsc(invoiceId) : List.of();
        if (!withReasons && adjustments != null) {
            adjustments = adjustments.stream()
                .map(a -> com.example.hms.model.InvoiceAdjustment.builder().invoice(a.getInvoice())
                    .invoiceItem(a.getInvoiceItem()).type(a.getType()).amount(a.getAmount()).build())
                .toList();
        }
        byte[] pdf = pdfInvoiceService.generateInvoicePdf(invoice, items,
            payments != null ? payments : List.of(), adjustments != null ? adjustments : List.of(), effectiveLocale);
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
        return renderReceiptPdf(invoice, paymentId, locale);
    }

    /** The receipt of one confirmed payment on an invoice the caller may already read. */
    private InvoicePdfResponseDTO renderReceiptPdf(BillingInvoice invoice, UUID paymentId, Locale locale) {
        UUID invoiceId = invoice.getId();
        PaymentTransaction row = loadPaymentRow(invoice, paymentId);
        // A receipt says money was received: never for a pending, rejected or reversed row.
        if (row.getStatus() != PaymentStatus.CONFIRMED) {
            throw new BusinessException("billing.payment.notConfirmed");
        }
        Locale effectiveLocale = locale != null ? locale : LocaleContextHolder.getLocale();
        // QA D228: a readable number (invoice number + the payment's rank among
        // the money ever received on it) and the amount paid as of THIS payment,
        // not as of today. Ranked by confirmation time, which never changes.
        List<PaymentTransaction> received = paymentTransactionRepository.findByInvoice_IdOrderByCreatedAtAsc(invoiceId)
            .stream()
            .filter(p -> p.getStatus() == PaymentStatus.CONFIRMED || p.getStatus() == PaymentStatus.REVERSED)
            // A row confirmed before confirmation times were kept ranks by when it was written.
            .sorted(java.util.Comparator.comparing(BillingInvoiceServiceImpl::receivedAt,
                    java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                .thenComparing(p -> String.valueOf(p.getId())))
            .toList();
        ReceiptPosition position = receiptPosition(received, row);
        int rank = position.rank();
        BigDecimal paidThen = position.paidThen();
        String receiptNumber = invoice.getInvoiceNumber() + "-R" + rank;
        byte[] pdf = pdfInvoiceService.generateReceiptPdf(invoice, row, receiptNumber, paidThen, effectiveLocale);
        return new InvoicePdfResponseDTO(pdf, receiptNumber, invoice.getId());
    }

    private record ReceiptPosition(int rank, BigDecimal paidThen) { }

    /** QA D228: the payment's rank among the money received, and the amount paid as of it. */
    private static ReceiptPosition receiptPosition(List<PaymentTransaction> received, PaymentTransaction row) {
        int rank = 0;
        BigDecimal paidThen = BigDecimal.ZERO;
        for (PaymentTransaction p : received) {
            rank++;
            // A row reversed before this payment was received no longer counted as paid then.
            boolean countedThen = p.getStatus() != PaymentStatus.REVERSED || p.getReversedAt() == null
                || receivedAt(row) == null || p.getReversedAt().isAfter(receivedAt(row));
            if (countedThen && p.getAmount() != null) {
                paidThen = paidThen.add(p.getAmount());
            }
            if (p.getId() != null && p.getId().equals(row.getId())) {
                break;
            }
        }
        if (received.stream().noneMatch(p -> p.getId() != null && p.getId().equals(row.getId()))) {
            rank = received.size() + 1;
            paidThen = paidThen.add(row.getAmount() != null ? row.getAmount() : BigDecimal.ZERO);
        }
        return new ReceiptPosition(rank, paidThen);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BillingInvoiceResponseDTO> searchInvoices(BillingInvoiceSearchRequest searchRequest, Pageable pageable, Locale locale) {
        // ── Tenant isolation: force hospital scope for non-superadmin ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = (searchRequest != null ? searchRequest.getHospitalId() : null);
        if (activeHospitalId != null) {
            // QA D230: another hospital named in the body is refused, not rewritten.
            // QA D1011: with a search message, not the invoice-create one.
            if (effectiveHospitalId != null && !effectiveHospitalId.equals(activeHospitalId)) {
                throw new BusinessException("billing.search.otherHospital");
            }
            effectiveHospitalId = activeHospitalId;
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
            likePattern(searchRequest != null ? searchRequest.getQuery() : null),
            stableOrder(pageable)
        ).map(invoiceMapper::toBillingInvoiceResponseDTO);
    }

    private static java.time.LocalDateTime receivedAt(PaymentTransaction row) {
        return row.getConfirmedAt() != null ? row.getConfirmedAt() : row.getCreatedAt();
    }

    /** QA D203: a lower-case contains-pattern with LIKE wildcards in the term escaped away, or null. */
    static String likePattern(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String term = query.trim().toLowerCase(java.util.Locale.ROOT).replace("%", "").replace("_", "");
        return term.isEmpty() ? null : "%" + term + "%";
    }

    /**
     * QA D203: newest first with the id as the last key, so a row sits on
     * exactly one page; a caller's sort is kept and the tiebreakers follow it.
     */
    static org.springframework.data.domain.Pageable stableOrder(org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Sort base = pageable.getSort().isSorted()
            ? pageable.getSort()
            // QA D326: newest = most recently created; a back-dated invoice
            // date must not push a new invoice down the list.
            : org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("createdAt"));
        org.springframework.data.domain.Sort sort = base.getOrderFor("id") != null
            ? base : base.and(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("id")));
        if (pageable.isUnpaged()) {
            return pageable;
        }
        return org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    @Override
    @Transactional(readOnly = true)
    public com.example.hms.payload.dto.BillingSummaryDTO summarize(Locale locale) {
        // QA D209: the KPIs cover the whole scope; global view reads every hospital.
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        List<Object[]> rows = invoiceRepository.summarize(hospitalId, LocalDate.now(java.time.ZoneId.systemDefault()));
        Object[] row = rows.isEmpty() ? new Object[6] : rows.get(0);
        return com.example.hms.payload.dto.BillingSummaryDTO.builder()
            .total(asLong(row[0]))
            .paid(asLong(row[1]))
            .outstanding(asLong(row[2]))
            .overdue(asLong(row[3]))
            .totalRevenue(asAmount(row[4]))
            .balance(asAmount(row[5]))
            .build();
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static BigDecimal asAmount(Object value) {
        if (value instanceof BigDecimal b) {
            return b;
        }
        return value instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
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
        // QA D231: locked, and the open declarations count against the
        // balance, so a patient cannot stack PENDING payments above what is owed.
        BillingInvoice invoice = invoiceRepository.findLockedById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId));
        BigDecimal pending = paymentTransactionRepository.sumAmountByInvoiceAndStatus(invoiceId, PaymentStatus.PENDING);
        BigDecimal declared = payment.getAmount().add(pending != null ? pending : BigDecimal.ZERO);
        requirePayable(invoice, patientId, payment.getAmount());
        // QA D421: refused in the patient's own figures (what they typed, what
        // is already declared, what is left), not as one sum they never typed.
        BigDecimal open = pending != null ? pending : BigDecimal.ZERO;
        if (open.signum() > 0) {
            BigDecimal balanceDue = invoice.getTotalAmount()
                .subtract(invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO);
            if (declared.compareTo(balanceDue) > 0) {
                throw new BusinessException("billing.payment.exceedsWithPending", payment.getAmount(), open,
                    balanceDue.subtract(open).max(BigDecimal.ZERO));
            }
        }
        requirePayable(invoice, patientId, declared);
        rejectReusedReference(invoice, method, payment.getTransactionReference());
        savePaymentRow(invoice, payment.getAmount(), method,
            payment.getTransactionReference(), payment.getNotes(), recordedBy, PaymentStatus.PENDING);
        BillingInvoiceResponseDTO response = invoiceMapper.toBillingInvoiceResponseDTO(invoice);
        response.setPendingDeclaredAmount(open.add(payment.getAmount()));
        return response;
    }

    /**
     * QA D345: a card, transfer or mobile-money reference names one provider
     * transaction; a second live row with it (any invoice of the hospital)
     * would count the same money twice. Cash has no provider reference.
     *
     * <p>QA D782 (decision): the check stays PER HOSPITAL on purpose. A
     * cross-hospital check would answer "already recorded" for a reference
     * held by another tenant, which tells a clerk of hospital B that a given
     * operator transaction id was paid at some other hospital (a disclosure
     * of another tenant's transactions, and a probe for them). A reference
     * reused across hospitals is reconciled against the operator's statement
     * by each hospital, not refused here.
     */
    private void rejectReusedReference(BillingInvoice invoice, PaymentMethod method, String reference) {
        if (method == PaymentMethod.CASH || reference == null || reference.isBlank() || invoice.getHospital() == null) {
            return;
        }
        if (paymentTransactionRepository.existsLiveReference(invoice.getHospital().getId(),
                reference.trim().toLowerCase(java.util.Locale.ROOT),
                java.util.List.of(PaymentStatus.PENDING, PaymentStatus.CONFIRMED))) {
            throw new BusinessException("billing.payment.duplicateReference");
        }
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
            .confirmedAt(confirmed ? java.time.LocalDateTime.now(java.time.ZoneId.systemDefault()) : null)
            // QA D228: the total as it stands when the money is received.
            .invoiceTotalAtReceipt(confirmed ? invoice.getTotalAmount() : null)
            .build();
        PaymentTransaction saved = paymentTransactionRepository.save(row);
        return java.util.Objects.requireNonNullElse(saved, row);
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
        throw new BusinessException("billing.payment.methodUnsupported");
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

    /** {@code invoice} checked to be this patient's, issued, open, and owing at least {@code amount}. */
    private static BillingInvoice requirePayable(BillingInvoice invoice, UUID patientId, BigDecimal amount) {
        UUID invoiceId = invoice.getId();
        // ── Ownership check: ensure invoice belongs to this patient ──
        if (!invoice.getPatient().getId().equals(patientId)) {
            throw new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND, invoiceId);
        }

        // ── Validate the invoice is payable ──
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new BusinessException("billing.invoice.alreadyPaid");
        }
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BusinessException("billing.invoice.paymentOnCancelled");
        }
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new BusinessException("billing.invoice.notIssued");
        }

        BigDecimal currentPaid = invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal balanceDue = invoice.getTotalAmount().subtract(currentPaid);

        if (amount.compareTo(balanceDue) > 0) {
            throw new BusinessException("billing.payment.exceedsBalance", amount, balanceDue);
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
        if (REFERENCE_REQUIRED.contains(method)
                && (payment.getReference() == null || payment.getReference().isBlank())) {
            throw new BusinessException("billing.payment.referenceRequired");
        }
        rejectReusedReference(invoice, method, payment.getReference());
        // QA D272: the copay already taken at check-in counts before the clerk's payment.
        creditCheckInCopays(invoice);
        requireRoomBesidePendingDeclarations(invoice, payment.getAmount());
        BillingInvoice saved = applyPayment(invoiceId, invoice.getPatient().getId(), payment.getAmount());
        PaymentTransaction row = savePaymentRow(saved, payment.getAmount(), method, payment.getReference(),
            payment.getNotes(), recordedBy, PaymentStatus.CONFIRMED);
        auditPaymentAfterCommit(AuditEventType.PAYMENT_POSTED, recordedBy, saved, row.getId(), "Payment recorded");
        return invoiceMapper.toBillingInvoiceResponseDTO(saved);
    }

    /**
     * QA D724: a cashier's payment settled the invoice in full while the
     * patient's declaration was still PENDING, which then could never be
     * confirmed (nothing left to pay) and stayed orphaned. The open
     * declarations count against the balance here too: the cashier confirms
     * or rejects them first, or takes only what is left beside them.
     * Called with the invoice row locked.
     */
    private void requireRoomBesidePendingDeclarations(BillingInvoice invoice, BigDecimal amount) {
        BigDecimal pending = paymentTransactionRepository.sumAmountByInvoiceAndStatus(invoice.getId(),
            PaymentStatus.PENDING);
        if (pending == null || pending.signum() <= 0 || amount == null) {
            return;
        }
        BigDecimal balanceDue = invoice.getTotalAmount()
            .subtract(invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO);
        if (amount.add(pending).compareTo(balanceDue) > 0) {
            throw new BusinessException("billing.payment.staffExceedsWithPending", amount, pending,
                balanceDue.subtract(pending).max(BigDecimal.ZERO));
        }
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
        row.setConfirmedAt(java.time.LocalDateTime.now(java.time.ZoneId.systemDefault()));
        row.setInvoiceTotalAtReceipt(invoice.getTotalAmount()); // QA D228
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
        row.setReversedAt(java.time.LocalDateTime.now(java.time.ZoneId.systemDefault()));
        row.setReversalReason(why);
        paymentTransactionRepository.save(row);
        auditPaymentAfterCommit(AuditEventType.PAYMENT_REJECTED, staffId, invoice, row.getId(), "Pending payment rejected");
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
        row.setReversedAt(java.time.LocalDateTime.now(java.time.ZoneId.systemDefault()));
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
        // QA D936 (class): two check-ins of one appointment are serialised by the
        // appointment lock in ReceptionServiceImpl.checkInPatient (each makes a new
        // encounter). This check is the second line: a copay recorded again on the
        // same encounter (a retried call) is not billed twice.
        if (encounter.getId() != null && invoiceRepository
                .findByEncounter_IdAndStatusNot(encounter.getId(), InvoiceStatus.DRAFT).stream()
                .anyMatch(existing -> existing.isCheckInCopay()
                    && (existing.getStatus() != InvoiceStatus.CANCELLED || existing.getCreditedToInvoice() != null))) {
            LOG.info("Check-in copay already recorded for encounter {}; not billed again", encounter.getId());
            return;
        }
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
            .checkInCopay(true)
            .createdBy(recordedBy) // QA D428
            .build();
        BillingInvoice saved = invoiceRepository.saveAndFlush(invoice);
        BillingInvoice settled = settle(saved, amount);
        PaymentTransaction row = savePaymentRow(settled, amount, PaymentMethod.CASH, null, null, recordedBy,
            PaymentStatus.CONFIRMED);
        auditPaymentAfterCommit(AuditEventType.PAYMENT_POSTED, recordedBy, settled, row.getId(), "Check-in copay recorded");
    }
}
