package com.example.hms.service;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.BillingInvoiceMapper;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingInvoiceServiceImplTest {

    @Mock
    private BillingInvoiceRepository invoiceRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private EncounterRepository encounterRepository;
    @Mock
    private InvoiceItemRepository invoiceItemRepository;
    @Mock
    private PdfInvoiceService pdfInvoiceService;
    @Mock
    private com.example.hms.utility.RoleValidator roleValidator;

    @Mock
    private com.example.hms.repository.PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private com.example.hms.security.tenant.ActingScopeResolver actingScopeResolver;
    @Mock
    private com.example.hms.repository.PatientHospitalRegistrationRepository registrationRepository;
    @Mock
    private InvoiceNumberAllocator invoiceNumberAllocator;
    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();

    private BillingInvoiceServiceImpl billingInvoiceService;
    private BillingInvoiceMapper invoiceMapper;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        // The locked read returns whatever a test stubbed for the plain read,
        // and a transition wins unless a test says it lost the race.
        when(invoiceRepository.findLockedById(any()))
            .thenAnswer(inv -> invoiceRepository.findById(inv.getArgument(0)));
        when(paymentTransactionRepository.transitionStatus(any(), any(), any(), any())).thenReturn(1);
        invoiceMapper = new BillingInvoiceMapper();
        billingInvoiceService = new BillingInvoiceServiceImpl(
            invoiceRepository,
            patientRepository,
            hospitalRepository,
            encounterRepository,
            invoiceItemRepository,
            pdfInvoiceService,
            invoiceMapper,
            roleValidator,
            paymentTransactionRepository,
            actingScopeResolver,
            registrationRepository,
            invoiceNumberAllocator,
            transactionManager
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        LocaleContextHolder.resetLocaleContext();
        mocks.close();
    }

    private Hospital pinnedHospital() {
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        hospital.setName("General Hospital");
        hospital.setCode("GH");
        when(actingScopeResolver.requirePinned()).thenReturn(HOSPITAL_ID);
        when(hospitalRepository.findClinicalById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
        when(invoiceNumberAllocator.next(any(Hospital.class), any(LocalDate.class))).thenReturn("GH-2026-000001");
        when(invoiceRepository.saveAndFlush(any(BillingInvoice.class))).thenAnswer(invocation -> {
            BillingInvoice invoice = invocation.getArgument(0);
            invoice.setId(UUID.randomUUID());
            return invoice;
        });
        return hospital;
    }

    private Patient registeredPatient() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setPhoneNumberPrimary("+22670000000");
        when(patientRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patient.getId(), HOSPITAL_ID)).thenReturn(true);
        return patient;
    }

    private BillingInvoiceRequestDTO.BillingInvoiceRequestDTOBuilder requestFor(Patient patient) {
        return BillingInvoiceRequestDTO.builder()
            .patientId(patient.getId())
            .invoiceDate(LocalDate.of(2026, 10, 1))
            .dueDate(LocalDate.of(2026, 10, 31));
    }

    @Test
    void createInvoice_assignsDefaultsWhenMissingValues() {
        pinnedHospital();
        Patient patient = registeredPatient();

        billingInvoiceService.createInvoice(requestFor(patient).build(), Locale.ENGLISH);

        ArgumentCaptor<BillingInvoice> invoiceCaptor = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceRepository).saveAndFlush(invoiceCaptor.capture());
        BillingInvoice persisted = invoiceCaptor.getValue();
        assertEquals(BigDecimal.ZERO, persisted.getTotalAmount());
        assertEquals(BigDecimal.ZERO, persisted.getAmountPaid());
        assertEquals(InvoiceStatus.DRAFT, persisted.getStatus());
    }

    @Test
    void createInvoice_phoneOnlyPatientByIdKeepsTheTypedTotal_D72_D92() {
        pinnedHospital();
        Patient patient = registeredPatient();

        BillingInvoiceResponseDTO response = billingInvoiceService.createInvoice(
            requestFor(patient).totalAmount(new BigDecimal("15000")).build(), Locale.ENGLISH);

        assertEquals(new BigDecimal("15000"), response.getTotalAmount());
        assertEquals(patient.getId().toString(), response.getPatientId());
        verify(patientRepository, never()).findByUsernameOrEmail(any());
    }

    @Test
    void createInvoice_ignoresTypedAmountPaid_D91() {
        pinnedHospital();
        Patient patient = registeredPatient();

        BillingInvoiceResponseDTO response = billingInvoiceService.createInvoice(
            requestFor(patient).totalAmount(new BigDecimal("100")).amountPaid(new BigDecimal("100"))
                .build(), Locale.ENGLISH);

        assertEquals(BigDecimal.ZERO, response.getAmountPaid());
        assertEquals("GH-2026-000001", response.getInvoiceNumber());
    }

    @Test
    void createInvoice_typedNumberIsRefusedNotRewritten_D183() {
        pinnedHospital();
        Patient patient = registeredPatient();
        BillingInvoiceRequestDTO request = requestFor(patient).invoiceNumber("TYPED-1").build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.createInvoice(request, Locale.ENGLISH));

        assertEquals("billing.invoice.numberAssignedByServer", refused.getMessageKey());
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInvoice_refusesAPaidStatus_D91() {
        pinnedHospital();
        Patient patient = registeredPatient();

        assertThrows(com.example.hms.exception.BusinessException.class, () -> billingInvoiceService.createInvoice(
            requestFor(patient).status(InvoiceStatus.PAID).build(), Locale.ENGLISH));
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInvoice_sameClientRequestIdReturnsTheFirstInvoice_D94() {
        pinnedHospital();
        Patient patient = registeredPatient();
        UUID key = UUID.randomUUID();
        BillingInvoice first = payableInvoice(patient.getId());
        first.setInvoiceNumber("GH-2026-000007");
        when(invoiceRepository.findByHospital_IdAndClientRequestId(HOSPITAL_ID, key)).thenReturn(Optional.of(first));

        BillingInvoiceResponseDTO response = billingInvoiceService.createInvoice(
            requestFor(patient).clientRequestId(key).build(), Locale.ENGLISH);

        assertEquals("GH-2026-000007", response.getInvoiceNumber());
        verify(invoiceRepository, never()).saveAndFlush(any());
        verify(invoiceNumberAllocator, never()).next(any(), any());
    }

    @Test
    void createInvoice_lostRaceOnClientRequestIdReturnsTheWinner_D94() {
        pinnedHospital();
        Patient patient = registeredPatient();
        UUID key = UUID.randomUUID();
        BillingInvoice winner = payableInvoice(patient.getId());
        winner.setInvoiceNumber("GH-2026-000008");
        // Both submits miss the pre-check; the loser's insert hits the unique index.
        when(invoiceRepository.findByHospital_IdAndClientRequestId(HOSPITAL_ID, key))
            .thenReturn(Optional.empty(), Optional.of(winner));
        when(invoiceRepository.saveAndFlush(any(BillingInvoice.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_bi_hospital_client_request"));

        BillingInvoiceResponseDTO response = billingInvoiceService.createInvoice(
            requestFor(patient).clientRequestId(key).build(), Locale.ENGLISH);

        assertEquals("GH-2026-000008", response.getInvoiceNumber());
        verify(transactionManager).rollback(any());
    }

    @Test
    void createInvoice_integrityViolationWithoutKeyStillFails() {
        pinnedHospital();
        Patient patient = registeredPatient();
        when(invoiceRepository.saveAndFlush(any(BillingInvoice.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("other"));

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> billingInvoiceService.createInvoice(requestFor(patient).build(), Locale.ENGLISH));
    }

    @Test
    void createInvoice_patientOfAnotherHospitalIsNotFound() {
        pinnedHospital();
        Patient patient = registeredPatient();
        when(registrationRepository.existsByPatientIdAndHospitalId(patient.getId(), HOSPITAL_ID)).thenReturn(false);

        BillingInvoiceRequestDTO request = requestFor(patient).build();
        assertThrows(ResourceNotFoundException.class,
            () -> billingInvoiceService.createInvoice(request, Locale.ENGLISH));
    }

    private BillingInvoice existingInvoice(InvoiceStatus status, String paid) {
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        BillingInvoice invoice = BillingInvoice.builder()
            .patient(patient).hospital(hospital).invoiceNumber("GH-2026-000001")
            .invoiceDate(LocalDate.of(2026, 10, 1)).dueDate(LocalDate.of(2026, 10, 31))
            .totalAmount(new BigDecimal("500")).amountPaid(new BigDecimal(paid)).status(status).build();
        invoice.setId(UUID.randomUUID());
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_ID);
        return invoice;
    }

    private BillingInvoiceRequestDTO.BillingInvoiceRequestDTOBuilder editOf() {
        return BillingInvoiceRequestDTO.builder()
            .invoiceDate(LocalDate.of(2026, 10, 1)).dueDate(LocalDate.of(2026, 10, 31));
    }

    @Test
    void updateInvoice_savesTheTypedTotalOfAnInvoiceWithoutLines_D72() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");

        BillingInvoiceResponseDTO response = billingInvoiceService.updateInvoice(invoice.getId(),
            editOf().totalAmount(new BigDecimal("15000")).build(), Locale.ENGLISH);

        assertEquals(new BigDecimal("15000"), response.getTotalAmount());
    }

    @Test
    void updateInvoice_withLinesKeepsTheComputedTotal_D72() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        when(invoiceRepository.hasItems(invoice.getId())).thenReturn(true);

        billingInvoiceService.updateInvoice(invoice.getId(),
            editOf().totalAmount(new BigDecimal("1")).build(), Locale.ENGLISH);

        assertEquals(new BigDecimal("500"), invoice.getTotalAmount());
    }

    @Test
    void updateInvoice_cannotTypeAmountPaidOrSetPaid_D91() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.SENT, "0");

        assertThrows(com.example.hms.exception.BusinessException.class, () -> billingInvoiceService.updateInvoice(
            invoice.getId(), editOf().status(InvoiceStatus.PAID).build(), Locale.ENGLISH));

        billingInvoiceService.updateInvoice(invoice.getId(),
            editOf().amountPaid(new BigDecimal("500")).status(InvoiceStatus.SENT).build(), Locale.ENGLISH);
        assertEquals(new BigDecimal("0"), invoice.getAmountPaid());
        assertEquals(InvoiceStatus.SENT, invoice.getStatus());
    }

    @Test
    void updateInvoice_partlyPaidCannotBeCancelledOrLowered_D91() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.PARTIALLY_PAID, "200");

        assertThrows(com.example.hms.exception.BusinessException.class, () -> billingInvoiceService.updateInvoice(
            invoice.getId(), editOf().status(InvoiceStatus.CANCELLED).build(), Locale.ENGLISH));
        assertThrows(com.example.hms.exception.BusinessException.class, () -> billingInvoiceService.updateInvoice(
            invoice.getId(), editOf().totalAmount(new BigDecimal("100")).build(), Locale.ENGLISH));
    }

    @Test
    void recordStaffPayment_onAnotherHospitalsInvoiceIsNotFound() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.SENT, "0");
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        UUID invoiceId = invoice.getId();
        com.example.hms.payload.dto.StaffPaymentRequestDTO payment =
            new com.example.hms.payload.dto.StaffPaymentRequestDTO(new BigDecimal("10.00"), null, null, null);
        assertThrows(ResourceNotFoundException.class,
            () -> billingInvoiceService.recordStaffPayment(invoiceId, payment, null, Locale.ENGLISH));
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void recomputeAndPersistTotals_lineAfterAPaymentReopensTheBalance() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.PAID, "500");
        when(invoiceRepository.sumItemsByInvoiceId(invoice.getId())).thenReturn(new BigDecimal("800"));

        billingInvoiceService.recomputeAndPersistTotals(invoice.getId());

        assertEquals(new BigDecimal("800"), invoice.getTotalAmount());
        assertEquals(InvoiceStatus.PARTIALLY_PAID, invoice.getStatus());
    }

    @Test
    void getInvoicePdf_defaultsLocaleWhenNull() {
        UUID invoiceId = UUID.randomUUID();
        BillingInvoice invoice = new BillingInvoice();
        invoice.setId(invoiceId);
        invoice.setInvoiceNumber("INV-2024-01");

        when(invoiceRepository.findByIdWithRefs(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceItemRepository.findByBillingInvoiceId(invoiceId)).thenReturn(Collections.emptyList());
        when(pdfInvoiceService.generateInvoicePdf(any(), any(), any(), any())).thenReturn(new byte[]{1, 2, 3});

        LocaleContextHolder.setLocale(Locale.CANADA_FRENCH);

        InvoicePdfResponseDTO result = billingInvoiceService.getInvoicePdf(invoiceId, null);

        assertNotNull(result);
        assertEquals("INV-2024-01", result.invoiceNumber());

        ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);
        verify(pdfInvoiceService).generateInvoicePdf(eq(invoice), eq(Collections.emptyList()), any(), localeCaptor.capture());
        assertEquals(Locale.CANADA_FRENCH, localeCaptor.getValue());
    }

    @Test
    void recomputeAndPersistTotals_usesZeroWhenSumNull() {
        UUID invoiceId = UUID.randomUUID();
        BillingInvoice invoice = new BillingInvoice();
        invoice.setId(invoiceId);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.sumItemsByInvoiceId(invoiceId)).thenReturn(null);
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        billingInvoiceService.recomputeAndPersistTotals(invoiceId);

        assertEquals(BigDecimal.ZERO, invoice.getTotalAmount());
        verify(invoiceRepository).save(invoice);
    }

    @Test
    void searchInvoices_translatesStatusesToEnum() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        LocalDate fromDate = LocalDate.of(2024, 1, 1);
        LocalDate toDate = LocalDate.of(2024, 12, 31);

        BillingInvoiceSearchRequest searchRequest = BillingInvoiceSearchRequest.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .statuses(List.of("SENT", "PAID"))
            .fromDate(fromDate)
            .toDate(toDate)
            .build();

        BillingInvoice invoice = new BillingInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setInvoiceNumber("INV-77");
        invoice.setStatus(InvoiceStatus.SENT);

        Page<BillingInvoice> page = new PageImpl<>(List.of(invoice));
        PageRequest pageable = PageRequest.of(0, 10);

        when(invoiceRepository.findAllWithFilters(
            eq(patientId),
            eq(hospitalId),
            anyList(),
            eq(fromDate),
            eq(toDate),
            any(),
            any(Pageable.class)
        )).thenReturn(page);

        Page<BillingInvoiceResponseDTO> result = billingInvoiceService.searchInvoices(searchRequest, pageable, Locale.ENGLISH);

        assertEquals(1, result.getTotalElements());
        assertEquals("INV-77", result.getContent().get(0).getInvoiceNumber());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InvoiceStatus>> statusesCaptor = ArgumentCaptor.forClass(List.class);
        verify(invoiceRepository).findAllWithFilters(
            eq(patientId),
            eq(hospitalId),
            statusesCaptor.capture(),
            eq(fromDate),
            eq(toDate),
            any(),
            any(Pageable.class)
        );

        List<InvoiceStatus> statuses = statusesCaptor.getValue();
        assertEquals(List.of(InvoiceStatus.SENT, InvoiceStatus.PAID), statuses);
    }

    @Test
    void deleteInvoice_throwsWhenRecordMissing() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.existsById(invoiceId)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> billingInvoiceService.deleteInvoice(invoiceId, Locale.ENGLISH));
    }

    @Test
    void updateInvoice_throwsWhenNotFound() {
        UUID invoiceId = UUID.randomUUID();
        BillingInvoiceRequestDTO request = BillingInvoiceRequestDTO.builder()
            .patientEmail("patient@example.com")
            .hospitalName("General Hospital")
            .invoiceNumber("INV-1001")
            .invoiceDate(LocalDate.of(2024, 1, 10))
            .dueDate(LocalDate.of(2024, 2, 10))
            .totalAmount(BigDecimal.TEN)
            .amountPaid(BigDecimal.ONE)
            .status(InvoiceStatus.SENT)
            .notes("Updated")
            .build();

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> billingInvoiceService.updateInvoice(invoiceId, request, Locale.ENGLISH));
    }

    // ---- QA D101: a draft invoice is never listed to the patient ----

    @org.junit.jupiter.api.Test
    void portalInvoicesLeaveOutDrafts() {
        UUID patientId = UUID.randomUUID();
        when(invoiceRepository.findByPatient_IdAndStatusNot(eq(patientId), eq(InvoiceStatus.DRAFT), any(Pageable.class)))
            .thenReturn(new PageImpl<>(Collections.emptyList()));

        billingInvoiceService.getInvoicesForPortalPatient(patientId, PageRequest.of(0, 20), Locale.ENGLISH);

        verify(invoiceRepository).findByPatient_IdAndStatusNot(eq(patientId), eq(InvoiceStatus.DRAFT), any(Pageable.class));
        verify(invoiceRepository, never()).findByPatient_Id(any(), any());
    }

    // ---- QA D70: a typed hospital name is a claim, not a scope ----

    @Test
    void createInvoice_anotherHospitalNameIsRefusedNotRewritten_D70_D183() {
        pinnedHospital();
        Patient patient = registeredPatient();
        BillingInvoiceRequestDTO request = requestFor(patient).hospitalName("Other Hospital").build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.createInvoice(request, Locale.ENGLISH));

        assertEquals("billing.invoice.hospitalMismatch", refused.getMessageKey());
        verify(invoiceRepository, never()).saveAndFlush(any());
        verify(hospitalRepository, never()).findClinicalByNameIgnoreCase(any());
    }

    @Test
    void createInvoice_theActingHospitalsOwnNameIsAccepted_D183() {
        Hospital acting = pinnedHospital();
        Patient patient = registeredPatient();

        billingInvoiceService.createInvoice(
            requestFor(patient).hospitalName(" " + acting.getName().toUpperCase() + " ").build(), Locale.ENGLISH);

        ArgumentCaptor<BillingInvoice> captor = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceRepository).saveAndFlush(captor.capture());
        assertEquals(acting.getId(), captor.getValue().getHospital().getId());
    }

    @Test
    void createInvoice_refusesAnEncounterOfAnotherHospital_D70() {
        pinnedHospital();
        Patient patient = registeredPatient();
        Hospital foreign = new Hospital();
        foreign.setId(UUID.randomUUID());
        com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
        encounter.setHospital(foreign);
        when(encounterRepository.findByCode("ENC-X")).thenReturn(Optional.of(encounter));
        BillingInvoiceRequestDTO request = requestFor(patient).encounterReference("ENC-X").build();

        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.createInvoice(request, Locale.ENGLISH));
        verify(invoiceRepository, never()).saveAndFlush(any(BillingInvoice.class));
    }

    // ---- Tenant isolation: GET /billing-invoices/hospital/{hospitalId} ----

    @org.junit.jupiter.api.Test
    void getInvoicesByHospitalId_scopedCallerIsPinnedToOwnHospital() {
        UUID ownHospital = UUID.randomUUID();
        UUID foreignHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(ownHospital);
        when(invoiceRepository.findByHospital_Id(eq(ownHospital), any(Pageable.class)))
            .thenReturn(new PageImpl<>(Collections.emptyList()));

        billingInvoiceService.getInvoicesByHospitalId(foreignHospital, PageRequest.of(0, 20), Locale.ENGLISH);

        // The requested (foreign) hospital id is overridden by the caller's scope.
        verify(invoiceRepository).findByHospital_Id(eq(ownHospital), any(Pageable.class));
    }

    @org.junit.jupiter.api.Test
    void getInvoicesByHospitalId_superAdminReadsTheRequestedHospital() {
        UUID requestedHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(null); // super-admin
        when(invoiceRepository.findByHospital_Id(eq(requestedHospital), any(Pageable.class)))
            .thenReturn(new PageImpl<>(Collections.emptyList()));

        billingInvoiceService.getInvoicesByHospitalId(requestedHospital, PageRequest.of(0, 20), Locale.ENGLISH);

        verify(invoiceRepository).findByHospital_Id(eq(requestedHospital), any(Pageable.class));
    }
    // ---- A patient's payment keeps what they told us about it ----

    private BillingInvoice payableInvoice(UUID patientId) {
        Patient patient = new Patient();
        patient.setId(patientId);
        BillingInvoice invoice = BillingInvoice.builder()
            .patient(patient)
            .totalAmount(new BigDecimal("100.00"))
            .amountPaid(BigDecimal.ZERO)
            .status(InvoiceStatus.SENT)
            .build();
        invoice.setId(UUID.randomUUID());
        return invoice;
    }

    private static com.example.hms.payload.dto.portal.PatientPaymentRequestDTO payment(String method) {
        return com.example.hms.payload.dto.portal.PatientPaymentRequestDTO.builder()
            .amount(new BigDecimal("40.00"))
            .paymentMethod(method)
            .transactionReference(" OM-20260926-7781 ")
            .notes("Paid from my Orange Money wallet")
            .build();
    }

    @Test
    void recordPatientPayment_storesMethodReferenceAndNotesOnAPendingRow_D140() {
        UUID patientId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        billingInvoiceService.recordPatientPayment(invoice.getId(), patientId, userId,
            payment("mobile_money"), Locale.ENGLISH);

        ArgumentCaptor<com.example.hms.model.PaymentTransaction> row =
            ArgumentCaptor.forClass(com.example.hms.model.PaymentTransaction.class);
        verify(paymentTransactionRepository).save(row.capture());
        assertEquals(com.example.hms.enums.PaymentMethod.MOBILE_MONEY, row.getValue().getPaymentMethod());
        assertEquals("OM-20260926-7781", row.getValue().getReferenceNumber());
        assertEquals("Paid from my Orange Money wallet", row.getValue().getNotes());
        assertEquals(new BigDecimal("40.00"), row.getValue().getAmount());
        assertEquals(userId, row.getValue().getRecordedBy());
        assertEquals(invoice, row.getValue().getInvoice());
        assertNotNull(row.getValue().getPaymentDate());
        // QA D140: a patient's declaration settles nothing until a cashier confirms it.
        assertEquals(com.example.hms.enums.PaymentStatus.PENDING, row.getValue().getStatus());
        assertEquals(BigDecimal.ZERO, invoice.getAmountPaid());
        assertEquals(InvoiceStatus.SENT, invoice.getStatus());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void recordPatientPayment_acceptsEveryMethodThePatientFormsOffer() {
        for (String method : List.of("CARD", "BANK_TRANSFER", "MOBILE_MONEY", "CASH", "INSURANCE")) {
            UUID patientId = UUID.randomUUID();
            BillingInvoice invoice = payableInvoice(patientId);
            when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
            when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

            billingInvoiceService.recordPatientPayment(invoice.getId(), patientId, UUID.randomUUID(),
                payment(method), Locale.ENGLISH);
        }
        verify(paymentTransactionRepository, times(5)).save(any());
    }

    @Test
    void recordPatientPayment_unknownMethodRefusesBeforeTheInvoiceMoves() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);

        assertThrows(com.example.hms.exception.BusinessException.class, () ->
            billingInvoiceService.recordPatientPayment(invoice.getId(), patientId, UUID.randomUUID(),
                payment("BITCOIN"), Locale.ENGLISH));

        verify(invoiceRepository, never()).save(any());
        verify(paymentTransactionRepository, never()).save(any());
        assertEquals(BigDecimal.ZERO, invoice.getAmountPaid());
    }

    @Test
    void recordPatientPayment_onSomeoneElsesInvoiceWritesNoPaymentRow() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        UUID invoiceId = invoice.getId();
        UUID wrongPatientId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        var cashPayment = payment("CASH");

        assertThrows(ResourceNotFoundException.class, () ->
            billingInvoiceService.recordPatientPayment(invoiceId, wrongPatientId, transactionId,
                cashPayment, Locale.ENGLISH));

        verify(paymentTransactionRepository, never()).save(any());
    }
    @Test
    void deleteInvoice_withAPaymentRowIsRefusedNotAForeignKeyFailure() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentTransactionRepository.existsByInvoice_Id(invoice.getId())).thenReturn(true);

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.deleteInvoice(invoice.getId(), Locale.ENGLISH));

        assertEquals("billing.invoice.hasPayments", refused.getMessageKey());
        verify(invoiceRepository, never()).deleteById(any());
    }

    @Test
    void deleteInvoice_withoutPaymentsStillDeletes() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentTransactionRepository.existsByInvoice_Id(invoice.getId())).thenReturn(false);

        billingInvoiceService.deleteInvoice(invoice.getId(), Locale.ENGLISH);

        verify(invoiceRepository).deleteById(invoice.getId());
    }
    @Test
    void recordStaffPayment_writesThePaymentRowWithTheStatedMethod() {
        UUID patientId = UUID.randomUUID();
        UUID cashier = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        billingInvoiceService.recordStaffPayment(invoice.getId(),
            new com.example.hms.payload.dto.StaffPaymentRequestDTO(new BigDecimal("25.00"), "cash", "R-9", "front desk"),
            cashier, Locale.ENGLISH);

        ArgumentCaptor<com.example.hms.model.PaymentTransaction> row =
            ArgumentCaptor.forClass(com.example.hms.model.PaymentTransaction.class);
        verify(paymentTransactionRepository).save(row.capture());
        assertEquals(com.example.hms.enums.PaymentMethod.CASH, row.getValue().getPaymentMethod());
        assertEquals("R-9", row.getValue().getReferenceNumber());
        assertEquals("front desk", row.getValue().getNotes());
        assertEquals(cashier, row.getValue().getRecordedBy());
        assertEquals(new BigDecimal("25.00"), invoice.getAmountPaid());
    }

    @Test
    void recordStaffPayment_withNoMethodRecordsOther() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        billingInvoiceService.recordStaffPayment(invoice.getId(),
            new com.example.hms.payload.dto.StaffPaymentRequestDTO(new BigDecimal("10.00"), null, null, null),
            null, Locale.ENGLISH);

        ArgumentCaptor<com.example.hms.model.PaymentTransaction> row =
            ArgumentCaptor.forClass(com.example.hms.model.PaymentTransaction.class);
        verify(paymentTransactionRepository).save(row.capture());
        assertEquals(com.example.hms.enums.PaymentMethod.OTHER, row.getValue().getPaymentMethod());
    }
    // ---- QA D140 / D142 / D184: pending, confirmation, reversal, whole francs ----

    private com.example.hms.model.PaymentTransaction ledgerRow(BillingInvoice invoice, String amount,
            com.example.hms.enums.PaymentStatus status) {
        com.example.hms.model.PaymentTransaction row = com.example.hms.model.PaymentTransaction.builder()
            .invoice(invoice).amount(new BigDecimal(amount)).paymentDate(java.time.LocalDate.now())
            .paymentMethod(com.example.hms.enums.PaymentMethod.CARD).status(status).build();
        row.setId(UUID.randomUUID());
        when(paymentTransactionRepository.findByIdAndInvoice_Id(row.getId(), invoice.getId()))
            .thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void confirmPayment_countsAPendingDeclarationOnlyNow_D140() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        var row = ledgerRow(invoice, "40.00", com.example.hms.enums.PaymentStatus.PENDING);
        UUID cashier = UUID.randomUUID();

        billingInvoiceService.confirmPayment(invoice.getId(), row.getId(), cashier, Locale.ENGLISH);

        assertEquals(com.example.hms.enums.PaymentStatus.CONFIRMED, row.getStatus());
        assertEquals(cashier, row.getConfirmedBy());
        assertEquals(new BigDecimal("40.00"), invoice.getAmountPaid());
        assertEquals(InvoiceStatus.PARTIALLY_PAID, invoice.getStatus());
    }

    @Test
    void confirmPayment_refusesARowThatIsNotPending_D140() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        var row = ledgerRow(invoice, "40.00", com.example.hms.enums.PaymentStatus.CONFIRMED);
        UUID invoiceId = invoice.getId();
        UUID rowId = row.getId();
        UUID staff = UUID.randomUUID();

        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.confirmPayment(invoiceId, rowId, staff, Locale.ENGLISH));
        assertEquals(BigDecimal.ZERO, invoice.getAmountPaid());
    }

    @Test
    void confirmPayment_thatLosesTheRaceToAConcurrentConfirmIsRefusedAndCountsNothing() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        var row = ledgerRow(invoice, "40.00", com.example.hms.enums.PaymentStatus.PENDING);
        // The other request already moved the row out of PENDING.
        when(paymentTransactionRepository.transitionStatus(row.getId(), invoice.getId(),
            com.example.hms.enums.PaymentStatus.PENDING, com.example.hms.enums.PaymentStatus.CONFIRMED))
            .thenReturn(0);
        UUID invoiceId = invoice.getId();
        UUID rowId = row.getId();
        UUID staff = UUID.randomUUID();

        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.confirmPayment(invoiceId, rowId, staff, Locale.ENGLISH));
        assertEquals(BigDecimal.ZERO, invoice.getAmountPaid());
        verify(invoiceRepository, never()).save(any(BillingInvoice.class));
        verify(invoiceRepository).findLockedById(invoiceId);
    }

    @Test
    void reversePayment_thatLosesTheRaceToAConcurrentReverseSubtractsNothing() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        invoice.setAmountPaid(new BigDecimal("100.00"));
        invoice.setStatus(InvoiceStatus.PAID);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        var row = ledgerRow(invoice, "60.00", com.example.hms.enums.PaymentStatus.CONFIRMED);
        when(paymentTransactionRepository.transitionStatus(row.getId(), invoice.getId(),
            com.example.hms.enums.PaymentStatus.CONFIRMED, com.example.hms.enums.PaymentStatus.REVERSED))
            .thenReturn(0);
        UUID invoiceId = invoice.getId();
        UUID rowId = row.getId();
        UUID staff = UUID.randomUUID();

        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.reversePayment(invoiceId, rowId, "Refund", staff, Locale.ENGLISH));
        assertEquals(new BigDecimal("100.00"), invoice.getAmountPaid());
        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
        verify(invoiceRepository, never()).save(any(BillingInvoice.class));
    }

    @Test
    void confirmPayment_onAnotherHospitalsInvoiceIsNotFound() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        invoice.setHospital(other);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_ID);
        UUID invoiceId = invoice.getId();
        UUID rowId = UUID.randomUUID();
        UUID staff = UUID.randomUUID();

        assertThrows(ResourceNotFoundException.class,
            () -> billingInvoiceService.confirmPayment(invoiceId, rowId, staff, Locale.ENGLISH));
    }

    @Test
    void rejectPayment_keepsTheRowWithItsReasonAndMovesNothing_D140() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        var row = ledgerRow(invoice, "40.00", com.example.hms.enums.PaymentStatus.PENDING);

        billingInvoiceService.rejectPayment(invoice.getId(), row.getId(), " No such transfer ", UUID.randomUUID(),
            Locale.ENGLISH);

        assertEquals(com.example.hms.enums.PaymentStatus.REJECTED, row.getStatus());
        assertEquals("No such transfer", row.getReversalReason());
        assertEquals(BigDecimal.ZERO, invoice.getAmountPaid());
    }

    @Test
    void rejectPayment_isAuditedAsRejectedNotPosted() {
        com.example.hms.service.AuditEventLogService audit =
            mock(com.example.hms.service.AuditEventLogService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(billingInvoiceService, "auditEventLogService", audit);
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        var row = ledgerRow(invoice, "40.00", com.example.hms.enums.PaymentStatus.PENDING);

        billingInvoiceService.rejectPayment(invoice.getId(), row.getId(), "No such transfer", UUID.randomUUID(),
            Locale.ENGLISH);

        var captor = org.mockito.ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(audit).logEvent(captor.capture());
        assertEquals(com.example.hms.enums.AuditEventType.PAYMENT_REJECTED, captor.getValue().getEventType());
    }

    @Test
    void updateInvoice_aPendingDeclarationLocksThePatient() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.SENT, "0");
        when(paymentTransactionRepository.existsByInvoice_IdAndStatusIn(any(), any())).thenReturn(true);
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        when(patientRepository.findById(other.getId())).thenReturn(Optional.of(other));
        when(registrationRepository.existsByPatientIdAndHospitalId(other.getId(), HOSPITAL_ID)).thenReturn(true);
        UUID originalPatient = invoice.getPatient().getId();
        UUID invoiceId = invoice.getId();
        BillingInvoiceRequestDTO request = editOf().patientId(other.getId()).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, request, Locale.ENGLISH));
        assertEquals("billing.invoice.patientLockedByPayments", refused.getMessageKey());
        assertEquals(originalPatient, invoice.getPatient().getId());
    }

    @Test
    void reversePayment_reopensThePaidInvoiceAndKeepsTotalsConsistent_D142() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        invoice.setAmountPaid(new BigDecimal("100.00"));
        invoice.setStatus(InvoiceStatus.PAID);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        var first = ledgerRow(invoice, "60.00", com.example.hms.enums.PaymentStatus.CONFIRMED);
        UUID accountant = UUID.randomUUID();

        billingInvoiceService.reversePayment(invoice.getId(), first.getId(), "Refunded: duplicate", accountant,
            Locale.ENGLISH);

        assertEquals(com.example.hms.enums.PaymentStatus.REVERSED, first.getStatus());
        assertEquals("Refunded: duplicate", first.getReversalReason());
        assertEquals(accountant, first.getReversedBy());
        assertNotNull(first.getReversedAt());
        assertEquals(new BigDecimal("40.00"), invoice.getAmountPaid());
        assertEquals(InvoiceStatus.PARTIALLY_PAID, invoice.getStatus());
    }

    @Test
    void reversePayment_ofTheOnlyPaymentLeavesAnIssuedUnpaidInvoice_D142() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        invoice.setAmountPaid(new BigDecimal("100.00"));
        invoice.setStatus(InvoiceStatus.PAID);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        var only = ledgerRow(invoice, "100.00", com.example.hms.enums.PaymentStatus.CONFIRMED);

        billingInvoiceService.reversePayment(invoice.getId(), only.getId(), "Entered in error", UUID.randomUUID(),
            Locale.ENGLISH);

        assertEquals(0, invoice.getAmountPaid().signum());
        assertEquals(InvoiceStatus.SENT, invoice.getStatus());
    }

    @Test
    void reversePayment_needsAReasonAndAConfirmedRow_D142() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        invoice.setAmountPaid(new BigDecimal("40.00"));
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        var confirmed = ledgerRow(invoice, "40.00", com.example.hms.enums.PaymentStatus.CONFIRMED);
        var pending = ledgerRow(invoice, "10.00", com.example.hms.enums.PaymentStatus.PENDING);
        UUID invoiceId = invoice.getId();
        UUID confirmedId = confirmed.getId();
        UUID pendingId = pending.getId();
        UUID staff = UUID.randomUUID();

        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.reversePayment(invoiceId, confirmedId, "  ", staff, Locale.ENGLISH));
        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.reversePayment(invoiceId, pendingId, "why", staff, Locale.ENGLISH));
        assertEquals(new BigDecimal("40.00"), invoice.getAmountPaid());
    }

    @Test
    void cancellingAPartlyPaidInvoiceSaysToReverseThePaymentsFirst_D142() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        invoice.setHospital(hospital);
        invoice.setInvoiceNumber("GH-2026-000001");
        invoice.setAmountPaid(new BigDecimal("40.00"));
        invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        BillingInvoiceRequestDTO request = BillingInvoiceRequestDTO.builder()
            .invoiceDate(java.time.LocalDate.now()).dueDate(java.time.LocalDate.now().plusDays(1))
            .status(InvoiceStatus.CANCELLED).build();
        UUID invoiceId = invoice.getId();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, request, Locale.ENGLISH));
        assertEquals("billing.invoice.statusLockedByPayments", refused.getMessageKey());
    }

    @Test
    void updateInvoice_aDifferentNumberIsRefused_D183() {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        hospital.setName("General Hospital");
        invoice.setHospital(hospital);
        invoice.setInvoiceNumber("GH-2026-000001");
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        BillingInvoiceRequestDTO request = BillingInvoiceRequestDTO.builder()
            .invoiceDate(java.time.LocalDate.now()).dueDate(java.time.LocalDate.now().plusDays(1))
            .invoiceNumber("GH-2026-999999").build();
        UUID invoiceId = invoice.getId();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, request, Locale.ENGLISH));
        assertEquals("billing.invoice.numberAssignedByServer", refused.getMessageKey());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void fractionalFrancPaymentsAreRefused_D184() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        UUID invoiceId = invoice.getId();
        var staffPayment = new com.example.hms.payload.dto.StaffPaymentRequestDTO(new BigDecimal("0.50"), "CASH", null, null);
        var patientPayment = com.example.hms.payload.dto.portal.PatientPaymentRequestDTO.builder()
            .amount(new BigDecimal("10.50")).paymentMethod("CARD").build();
        UUID staff = UUID.randomUUID();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.recordStaffPayment(invoiceId, staffPayment, staff, Locale.ENGLISH));
        assertEquals("billing.payment.wholeAmountOnly", refused.getMessageKey());
        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.recordPatientPayment(invoiceId, patientId, staff, patientPayment, Locale.ENGLISH));
        verify(paymentTransactionRepository, never()).save(any());
        assertEquals(BigDecimal.ZERO, invoice.getAmountPaid());
    }

    @Test
    void wholeFrancsWrittenWithTrailingZerosAreAccepted_D184() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        billingInvoiceService.recordStaffPayment(invoice.getId(),
            new com.example.hms.payload.dto.StaffPaymentRequestDTO(new BigDecimal("25.00"), "CASH", null, null),
            UUID.randomUUID(), Locale.ENGLISH);

        assertEquals(new BigDecimal("25.00"), invoice.getAmountPaid());
    }

    @Test
    void checkInCopayBecomesAPaidInvoiceOnTheEncounter_D149() {
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        hospital.setName("General Hospital");
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setHospital(hospital);
        encounter.setPatient(patient);
        when(invoiceNumberAllocator.next(any(), any())).thenReturn("GH-2026-000042");
        when(invoiceRepository.saveAndFlush(any(BillingInvoice.class))).thenAnswer(inv -> {
            BillingInvoice b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID receptionist = UUID.randomUUID();

        billingInvoiceService.recordCheckInCopay(encounter, new BigDecimal("2000"), receptionist);

        ArgumentCaptor<BillingInvoice> invoice = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceRepository).saveAndFlush(invoice.capture());
        assertEquals(encounter, invoice.getValue().getEncounter());
        assertEquals(new BigDecimal("2000"), invoice.getValue().getTotalAmount());
        assertEquals(new BigDecimal("2000"), invoice.getValue().getAmountPaid());
        assertEquals(InvoiceStatus.PAID, invoice.getValue().getStatus());
        ArgumentCaptor<com.example.hms.model.PaymentTransaction> row =
            ArgumentCaptor.forClass(com.example.hms.model.PaymentTransaction.class);
        verify(paymentTransactionRepository).save(row.capture());
        assertEquals(com.example.hms.enums.PaymentMethod.CASH, row.getValue().getPaymentMethod());
        assertEquals(com.example.hms.enums.PaymentStatus.CONFIRMED, row.getValue().getStatus());
        assertEquals(receptionist, row.getValue().getRecordedBy());
    }

    // ---- QA round 4 (billing) ----

    @Test
    void searchInvoices_sendsTheTermToTheServerQueryAndAStableOrder_D203() {
        when(invoiceRepository.findAllWithFilters(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        billingInvoiceService.searchInvoices(
            BillingInvoiceSearchRequest.builder().query("  GH-2026_%0024 ").build(), PageRequest.of(1, 20), Locale.ENGLISH);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> paging = ArgumentCaptor.forClass(Pageable.class);
        verify(invoiceRepository).findAllWithFilters(any(), any(), any(), any(), any(), query.capture(), paging.capture());
        assertEquals("%gh-20260024%", query.getValue());
        assertEquals(1, paging.getValue().getPageNumber());
        // A unique last key: equal dates can no longer swap rows between pages.
        List<org.springframework.data.domain.Sort.Order> orders = paging.getValue().getSort().toList();
        assertEquals("invoiceDate", orders.get(0).getProperty());
        assertEquals("id", orders.get(orders.size() - 1).getProperty());
    }

    @Test
    void searchInvoices_anotherHospitalInTheBodyIsRefused_D230() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_ID);
        BillingInvoiceSearchRequest request = BillingInvoiceSearchRequest.builder().hospitalId(UUID.randomUUID()).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.searchInvoices(request, PageRequest.of(0, 20), Locale.ENGLISH));
        assertEquals("billing.invoice.hospitalMismatch", refused.getMessageKey());
    }

    @Test
    void createInvoice_anotherHospitalIdIsRefusedNotRewritten_D230() {
        pinnedHospital();
        Patient patient = registeredPatient();
        BillingInvoiceRequestDTO request = requestFor(patient).hospitalId(UUID.randomUUID()).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.createInvoice(request, Locale.ENGLISH));
        assertEquals("billing.invoice.hospitalMismatch", refused.getMessageKey());
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInvoice_theActingHospitalIdIsAccepted_D230() {
        pinnedHospital();
        Patient patient = registeredPatient();

        billingInvoiceService.createInvoice(requestFor(patient).hospitalId(HOSPITAL_ID).build(), Locale.ENGLISH);

        verify(invoiceRepository).saveAndFlush(any(BillingInvoice.class));
    }

    @Test
    void createInvoice_unknownEncounterCodeIsRefusedNotDropped_D219() {
        pinnedHospital();
        Patient patient = registeredPatient();
        when(encounterRepository.findByCode("ENC-TYPO")).thenReturn(Optional.empty());
        BillingInvoiceRequestDTO request = requestFor(patient).encounterReference("ENC-TYPO").build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.createInvoice(request, Locale.ENGLISH));
        assertEquals("billing.invoice.encounterNotFound", refused.getMessageKey());
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void recordPatientPayment_openDeclarationsCountAgainstTheBalance_D231() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId); // owes 100
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentTransactionRepository.sumAmountByInvoiceAndStatus(
            invoice.getId(), com.example.hms.enums.PaymentStatus.PENDING)).thenReturn(new BigDecimal("80"));

        com.example.hms.payload.dto.portal.PatientPaymentRequestDTO forty = payment("CARD"); // 40 + 80 > 100
        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.recordPatientPayment(invoice.getId(), patientId, UUID.randomUUID(),
                forty, Locale.ENGLISH));
        verify(paymentTransactionRepository, never()).save(any());
        verify(invoiceRepository).findLockedById(invoice.getId());
    }

    @Test
    void createInvoice_issuedVisitInvoiceTakesOverTheCheckInCopay_D210() {
        pinnedHospital();
        Patient patient = registeredPatient();
        com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
        encounter.setId(UUID.randomUUID());
        Hospital acting = new Hospital();
        acting.setId(HOSPITAL_ID);
        encounter.setHospital(acting);
        when(encounterRepository.findByCode("ENC-1")).thenReturn(Optional.of(encounter));
        BillingInvoice copay = BillingInvoice.builder().patient(patient).encounter(encounter)
            .totalAmount(new BigDecimal("2500")).amountPaid(new BigDecimal("2500"))
            .status(InvoiceStatus.PAID).checkInCopay(true).build();
        copay.setId(UUID.randomUUID());
        when(invoiceRepository.findByEncounter_IdAndCheckInCopayTrueAndStatus(encounter.getId(), InvoiceStatus.PAID))
            .thenReturn(List.of(copay));
        when(invoiceRepository.findById(copay.getId())).thenReturn(Optional.of(copay));
        com.example.hms.model.PaymentTransaction row = com.example.hms.model.PaymentTransaction.builder()
            .invoice(copay).amount(new BigDecimal("2500"))
            .status(com.example.hms.enums.PaymentStatus.CONFIRMED).build();
        when(paymentTransactionRepository.findByInvoice_IdOrderByCreatedAtAsc(copay.getId())).thenReturn(List.of(row));
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        BillingInvoiceResponseDTO visit = billingInvoiceService.createInvoice(
            requestFor(patient).encounterReference("ENC-1").totalAmount(new BigDecimal("7450"))
                .status(InvoiceStatus.SENT).build(), Locale.ENGLISH);

        assertEquals(0, new BigDecimal("2500").compareTo(visit.getAmountPaid()));
        assertEquals(0, new BigDecimal("4950").compareTo(visit.getBalanceDue()));
        assertEquals(InvoiceStatus.CANCELLED, copay.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(copay.getAmountPaid()));
        assertEquals(visit.getId(), String.valueOf(row.getInvoice().getId()));
    }

    @Test
    void receipt_isNumberedByRankAndShowsWhatWasPaidThen_D228() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);
        invoice.setInvoiceNumber("GH-2026-000019");
        invoice.setAmountPaid(new BigDecimal("100"));
        com.example.hms.model.PaymentTransaction first = com.example.hms.model.PaymentTransaction.builder()
            .invoice(invoice).amount(new BigDecimal("30")).status(com.example.hms.enums.PaymentStatus.CONFIRMED)
            .confirmedAt(java.time.LocalDateTime.of(2026, 10, 1, 9, 0)).build();
        first.setId(UUID.randomUUID());
        com.example.hms.model.PaymentTransaction second = com.example.hms.model.PaymentTransaction.builder()
            .invoice(invoice).amount(new BigDecimal("70")).status(com.example.hms.enums.PaymentStatus.CONFIRMED)
            .confirmedAt(java.time.LocalDateTime.of(2026, 10, 2, 9, 0)).build();
        second.setId(UUID.randomUUID());
        when(invoiceRepository.findByIdWithRefs(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentTransactionRepository.findByIdAndInvoice_Id(first.getId(), invoice.getId())).thenReturn(Optional.of(first));
        when(paymentTransactionRepository.findByInvoice_IdOrderByCreatedAtAsc(invoice.getId()))
            .thenReturn(List.of(second, first));
        when(pdfInvoiceService.generateReceiptPdf(any(), any(), any(String.class), any(BigDecimal.class), any()))
            .thenReturn(new byte[] {1});

        InvoicePdfResponseDTO pdf = billingInvoiceService.getPaymentReceiptPdf(invoice.getId(), first.getId(), Locale.FRENCH);

        assertEquals("GH-2026-000019-R1", pdf.suggestedFilename());
        verify(pdfInvoiceService).generateReceiptPdf(eq(invoice), eq(first), eq("GH-2026-000019-R1"),
            eq(new BigDecimal("30")), any());
    }
}
