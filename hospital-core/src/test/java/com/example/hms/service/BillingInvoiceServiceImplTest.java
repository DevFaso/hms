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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /** QA D537: the create response names who created the invoice. */
    @Test
    void createInvoice_responseNamesTheCreator_D537() {
        pinnedHospital();
        Patient patient = registeredPatient();
        UUID creatorId = UUID.randomUUID();
        com.example.hms.model.User creator = new com.example.hms.model.User();
        org.springframework.test.util.ReflectionTestUtils.setField(creator, "id", creatorId);
        creator.setFirstName("Awa");
        creator.setLastName("Biller");
        com.example.hms.repository.UserRepository users = mock(com.example.hms.repository.UserRepository.class);
        when(users.findById(creatorId)).thenReturn(Optional.of(creator));
        billingInvoiceService.setUserRepository(users);
        when(roleValidator.getCurrentUserId()).thenReturn(creatorId);

        BillingInvoiceResponseDTO response = billingInvoiceService.createInvoice(
            requestFor(patient).totalAmount(new BigDecimal("5000")).build(), Locale.ENGLISH);

        assertEquals("Awa Biller", response.getCreatedByName());
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
        when(pdfInvoiceService.generateInvoicePdf(any(), any(), any(), any(), any())).thenReturn(new byte[]{1, 2, 3});

        LocaleContextHolder.setLocale(Locale.CANADA_FRENCH);

        InvoicePdfResponseDTO result = billingInvoiceService.getInvoicePdf(invoiceId, null);

        assertNotNull(result);
        assertEquals("INV-2024-01", result.invoiceNumber());

        ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);
        verify(pdfInvoiceService).generateInvoicePdf(eq(invoice), eq(Collections.emptyList()), any(), any(), localeCaptor.capture());
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
        invoice.setStatus(InvoiceStatus.DRAFT);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentTransactionRepository.existsByInvoice_Id(invoice.getId())).thenReturn(false);

        billingInvoiceService.deleteInvoice(invoice.getId(), Locale.ENGLISH);

        verify(invoiceRepository).deleteById(invoice.getId());
    }
    /** QA D613: HOSPITAL_ADMIN_A sent DELETE on SENT invoice RHA23EEA-2026-000016 and got 200. */
    @Test
    void deleteInvoice_anIssuedInvoiceIsRefusedSoItsNumberStays_D613() {
        for (InvoiceStatus issued : List.of(InvoiceStatus.SENT, InvoiceStatus.CANCELLED,
                InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.PAID)) {
            BillingInvoice invoice = payableInvoice(UUID.randomUUID());
            invoice.setStatus(issued);
            when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
            UUID id = invoice.getId();

            com.example.hms.exception.BusinessException refused = assertThrows(
                com.example.hms.exception.BusinessException.class,
                () -> billingInvoiceService.deleteInvoice(id, Locale.ENGLISH));

            assertEquals("billing.invoice.deleteOnlyDraft", refused.getMessageKey(), issued.name());
        }
        verify(invoiceRepository, never()).deleteById(any());
    }

    /** #926 review: PUT {status:DRAFT} then DELETE hard-deleted an issued invoice. */
    @Test
    void sentInvoiceCannotGoBackToDraftThenBeDeleted() {
        for (InvoiceStatus issued : List.of(InvoiceStatus.SENT)) {
            BillingInvoice invoice = existingInvoice(issued, "0");
            UUID invoiceId = invoice.getId();
            BillingInvoiceRequestDTO toDraft = editOf().status(InvoiceStatus.DRAFT).build();

            com.example.hms.exception.BusinessException refused = assertThrows(
                com.example.hms.exception.BusinessException.class,
                () -> billingInvoiceService.updateInvoice(invoiceId, toDraft, Locale.ENGLISH));
            assertEquals("billing.invoice.noReturnToDraft", refused.getMessageKey(), issued.name());
            assertEquals(issued, invoice.getStatus());

            assertThrows(com.example.hms.exception.BusinessException.class,
                () -> billingInvoiceService.deleteInvoice(invoiceId, Locale.ENGLISH));
        }
        verify(invoiceRepository, never()).deleteById(any());
        verify(invoiceRepository, never()).save(any());
    }

    /** #926 review: PUT {status:DRAFT} then PUT {patientId:other} swapped an issued invoice's patient. */
    @Test
    void sentInvoiceCannotGoBackToDraftThenChangePatient() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.SENT, "0");
        UUID invoiceId = invoice.getId();
        UUID original = invoice.getPatient().getId();
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(patientRepository.findById(other.getId())).thenReturn(Optional.of(other));
        org.mockito.Mockito.lenient().when(registrationRepository.existsByPatientIdAndHospitalId(other.getId(), HOSPITAL_ID))
            .thenReturn(true);
        BillingInvoiceRequestDTO toDraft = editOf().status(InvoiceStatus.DRAFT).build();
        BillingInvoiceRequestDTO swap = editOf().patientId(other.getId()).build();

        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, toDraft, Locale.ENGLISH));
        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, swap, Locale.ENGLISH));

        assertEquals("billing.invoice.patientLockedIssued", refused.getMessageKey());
        assertEquals(InvoiceStatus.SENT, invoice.getStatus());
        assertEquals(original, invoice.getPatient().getId());
        verify(invoiceRepository, never()).save(any());
    }

    /** QA D614: PUT on SENT invoice 22ae400f with patientId changed from A4 to A2 returned 200. */
    @Test
    void updateInvoice_anIssuedInvoiceKeepsItsPatient_D614() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.SENT, "0");
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        when(patientRepository.findById(other.getId())).thenReturn(Optional.of(other));
        when(registrationRepository.existsByPatientIdAndHospitalId(other.getId(), HOSPITAL_ID)).thenReturn(true);
        UUID original = invoice.getPatient().getId();
        UUID invoiceId = invoice.getId();
        BillingInvoiceRequestDTO request = editOf().patientId(other.getId()).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, request, Locale.ENGLISH));

        assertEquals("billing.invoice.patientLockedIssued", refused.getMessageKey());
        assertEquals(original, invoice.getPatient().getId());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void updateInvoice_aDraftStillMovesToAnotherPatient_D614() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        when(patientRepository.findById(other.getId())).thenReturn(Optional.of(other));
        when(registrationRepository.existsByPatientIdAndHospitalId(other.getId(), HOSPITAL_ID)).thenReturn(true);

        billingInvoiceService.updateInvoice(invoice.getId(), editOf().patientId(other.getId()).build(),
            Locale.ENGLISH);

        assertEquals(other.getId(), invoice.getPatient().getId());
    }

    @Test
    void updateInvoice_theSamePatientOnAnIssuedInvoiceIsAccepted_D614() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.SENT, "0");
        Patient same = invoice.getPatient();
        when(patientRepository.findById(same.getId())).thenReturn(Optional.of(same));
        when(registrationRepository.existsByPatientIdAndHospitalId(same.getId(), HOSPITAL_ID)).thenReturn(true);

        billingInvoiceService.updateInvoice(invoice.getId(), editOf().patientId(same.getId()).build(),
            Locale.ENGLISH);

        assertEquals(same.getId(), invoice.getPatient().getId());
        verify(invoiceRepository).save(invoice);
    }

    /** QA D656 re-test (bill3.js): PUT notes 'changed' on a SENT invoice returned 200. */
    @Test
    void updateInvoice_anIssuedInvoiceKeepsItsNotes_D656() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.SENT, "0");
        invoice.setNotes("QA-R9RT2");
        UUID invoiceId = invoice.getId();
        BillingInvoiceRequestDTO request = editOf().notes("changed").status(InvoiceStatus.SENT).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, request, Locale.FRENCH));

        assertEquals("billing.invoice.issuedLocked", refused.getMessageKey());
        assertEquals("QA-R9RT2", invoice.getNotes());
        verify(invoiceRepository, never()).save(any());
    }

    /** D656: the edit form echoes the stored notes; that is not an edit. */
    @Test
    void updateInvoice_anIssuedInvoiceAcceptsItsEchoedNotes_D656() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.SENT, "0");
        invoice.setNotes("QA-R9RT2");
        billingInvoiceService.updateInvoice(invoice.getId(),
            editOf().notes("QA-R9RT2").status(InvoiceStatus.SENT).build(), Locale.ENGLISH);
        assertEquals("QA-R9RT2", invoice.getNotes());
    }

    /** QA D656: PUT invoiceDate on SENT invoice ae8e18f2 returned 200 and moved the date. */
    @Test
    void updateInvoice_anIssuedInvoiceKeepsItsDate_D656() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.SENT, "0");
        UUID invoiceId = invoice.getId();
        BillingInvoiceRequestDTO request = editOf().invoiceDate(LocalDate.of(2026, 9, 30))
            .status(InvoiceStatus.SENT).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, request, Locale.FRENCH));

        assertEquals("billing.invoice.issuedLocked", refused.getMessageKey());
        assertEquals(LocalDate.of(2026, 10, 1), invoice.getInvoiceDate());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void updateInvoice_anIssuedInvoiceKeepsItsDueDateAndTotal_D656() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.SENT, "0");
        UUID invoiceId = invoice.getId();
        BillingInvoiceRequestDTO later = editOf().dueDate(LocalDate.of(2026, 12, 31)).build();
        BillingInvoiceRequestDTO retyped = editOf().totalAmount(new BigDecimal("2000")).build();

        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, later, Locale.FRENCH));
        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, retyped, Locale.FRENCH));
        assertEquals(new BigDecimal("500"), invoice.getTotalAmount());
    }

    @Test
    void updateInvoice_anIssuedInvoiceIsStillCancelledWithItsEchoedValues_D656() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.SENT, "0");

        billingInvoiceService.updateInvoice(invoice.getId(),
            editOf().totalAmount(new BigDecimal("500.00")).status(InvoiceStatus.CANCELLED).build(), Locale.FRENCH);

        assertEquals(InvoiceStatus.CANCELLED, invoice.getStatus());
    }

    /** QA D612: invoiceDate 2026-10-23, dueDate 2026-10-01 gave a 500. */
    @Test
    void createInvoice_dueBeforeTheInvoiceDateIsRefused_D612() {
        pinnedHospital();
        Patient patient = registeredPatient();
        BillingInvoiceRequestDTO request = requestFor(patient)
            .invoiceDate(LocalDate.now()).dueDate(LocalDate.now().minusDays(22)).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.createInvoice(request, Locale.FRENCH));

        assertEquals("billing.invoice.dueBeforeInvoiceDate", refused.getMessageKey());
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateInvoice_dueBeforeTheInvoiceDateIsRefused_D612() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        UUID id = invoice.getId();
        BillingInvoiceRequestDTO request = editOf()
            .invoiceDate(LocalDate.of(2026, 10, 1)).dueDate(LocalDate.of(2026, 9, 1)).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(id, request, Locale.ENGLISH));

        assertEquals("billing.invoice.dueBeforeInvoiceDate", refused.getMessageKey());
    }

    /** QA D632: an invoice dated 2027-05-01 returned 201. */
    @Test
    void createInvoice_aFutureInvoiceDateIsRefused_D632() {
        pinnedHospital();
        Patient patient = registeredPatient();
        BillingInvoiceRequestDTO request = requestFor(patient)
            .invoiceDate(LocalDate.now().plusMonths(6)).dueDate(LocalDate.now().plusMonths(7)).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.createInvoice(request, Locale.ENGLISH));

        assertEquals("billing.invoice.dateInFuture", refused.getMessageKey());
    }

    @Test
    void createInvoice_todayAndADueDateTheSameDayAreAccepted_D612_D632() {
        pinnedHospital();
        Patient patient = registeredPatient();

        billingInvoiceService.createInvoice(requestFor(patient)
            .invoiceDate(LocalDate.now()).dueDate(LocalDate.now()).build(), Locale.ENGLISH);

        verify(invoiceRepository).saveAndFlush(any());
    }

    /**
     * QA D842: BILLING_A posted invoiceDate = tomorrow (2026-10-27 on 2026-10-26)
     * and got 201: the D632 check allowed a day of grace past the server's date.
     */
    @Test
    void createInvoice_tomorrowIsRefused_D842() {
        pinnedHospital();
        Patient patient = registeredPatient();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        BillingInvoiceRequestDTO request = requestFor(patient)
            .invoiceDate(tomorrow).dueDate(tomorrow.plusDays(30)).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.createInvoice(request, Locale.FRENCH));

        assertEquals("billing.invoice.dateInFuture", refused.getMessageKey());
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    /** QA D842, the PUT half: moving a draft's date to tomorrow is refused too. */
    @Test
    void updateInvoice_movingTheDateToTomorrowIsRefused_D842() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        UUID id = invoice.getId();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        BillingInvoiceRequestDTO request = editOf().invoiceDate(tomorrow).dueDate(tomorrow.plusDays(30)).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(id, request, Locale.FRENCH));

        assertEquals("billing.invoice.dateInFuture", refused.getMessageKey());
        assertEquals(LocalDate.of(2026, 10, 1), invoice.getInvoiceDate());
    }

    /** QA D842: a hospital whose organisation runs on another zone is judged on ITS day. */
    @Test
    void createInvoice_todayIsTheHospitalsOwnDay_D842() {
        Hospital hospital = pinnedHospital();
        com.example.hms.model.Organization org = new com.example.hms.model.Organization();
        org.setDefaultTimezone("Pacific/Kiritimati"); // UTC+14: always the latest calendar day on earth
        hospital.setOrganization(org);
        Patient patient = registeredPatient();
        LocalDate hospitalToday = LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati"));

        billingInvoiceService.createInvoice(requestFor(patient)
            .invoiceDate(hospitalToday).dueDate(hospitalToday).build(), Locale.ENGLISH);

        verify(invoiceRepository).saveAndFlush(any());
    }

    @Test
    void updateInvoice_aStoredDateIsNotReJudged_D632() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        LocalDate stored = LocalDate.now().plusDays(10);
        invoice.setInvoiceDate(stored);

        billingInvoiceService.updateInvoice(invoice.getId(),
            editOf().invoiceDate(stored).dueDate(stored.plusDays(30)).notes("kept").build(), Locale.ENGLISH);

        assertEquals("kept", invoice.getNotes());
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
    void qaD724_staffPaymentCannotSettleInFullWhileADeclarationIsPending() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        BigDecimal total = invoice.getTotalAmount();
        when(paymentTransactionRepository.sumAmountByInvoiceAndStatus(invoice.getId(),
            com.example.hms.enums.PaymentStatus.PENDING)).thenReturn(new BigDecimal("10.00"));

        UUID invoiceId = invoice.getId();
        com.example.hms.payload.dto.StaffPaymentRequestDTO full =
            new com.example.hms.payload.dto.StaffPaymentRequestDTO(total, "cash", null, null);
        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.recordStaffPayment(invoiceId, full, null, Locale.ENGLISH));
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void qaD724_staffPaymentBesideAPendingDeclarationIsTaken() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        BigDecimal rest = invoice.getTotalAmount().subtract(new BigDecimal("10.00"));
        when(paymentTransactionRepository.sumAmountByInvoiceAndStatus(invoice.getId(),
            com.example.hms.enums.PaymentStatus.PENDING)).thenReturn(new BigDecimal("10.00"));

        billingInvoiceService.recordStaffPayment(invoice.getId(),
            new com.example.hms.payload.dto.StaffPaymentRequestDTO(rest, "cash", null, null), null, Locale.ENGLISH);

        verify(paymentTransactionRepository).save(any());
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

    /** QA D767: an invoice created as SENT at 0 F CFA was outstanding forever. */
    @Test
    void qaD767_createdSentAtZeroIsRefused() {
        pinnedHospital();
        Patient patient = registeredPatient();
        BillingInvoiceRequestDTO request = requestFor(patient).status(InvoiceStatus.SENT).build();
        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.createInvoice(request, Locale.ENGLISH));
        assertEquals("billing.invoice.zeroTotal", refused.getMessageKey());
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    /** QA D767: a 0 F CFA draft may still be created while its lines are added. */
    @Test
    void qaD767_zeroDraftIsStillCreated() {
        pinnedHospital();
        Patient patient = registeredPatient();
        billingInvoiceService.createInvoice(requestFor(patient).build(), Locale.ENGLISH);
        verify(invoiceRepository).saveAndFlush(any());
    }

    /** QA D767: issuing a 0 F CFA draft by PUT {status:SENT} is refused and it stays DRAFT. */
    @Test
    void qaD767_issuingAZeroDraftIsRefused() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        invoice.setTotalAmount(BigDecimal.ZERO);
        UUID invoiceId = invoice.getId();
        BillingInvoiceRequestDTO issue = editOf().status(InvoiceStatus.SENT).build();
        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, issue, Locale.ENGLISH));
        assertEquals("billing.invoice.zeroTotal", refused.getMessageKey());
        assertEquals(InvoiceStatus.DRAFT, invoice.getStatus());
    }

    /** QA D768: a MOBILE_MONEY or INSURANCE payment with no reference was accepted. */
    @Test
    void qaD768_tracedMethodsNeedAReference() {
        for (String method : List.of("MOBILE_MONEY", "INSURANCE", "BANK_TRANSFER")) {
            BillingInvoice invoice = payableInvoice(UUID.randomUUID());
            when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
            UUID invoiceId = invoice.getId();
            com.example.hms.payload.dto.StaffPaymentRequestDTO noRef =
                new com.example.hms.payload.dto.StaffPaymentRequestDTO(new BigDecimal("10"), method, "  ", null);
            com.example.hms.exception.BusinessException refused = assertThrows(
                com.example.hms.exception.BusinessException.class,
                () -> billingInvoiceService.recordStaffPayment(invoiceId, noRef, null, Locale.ENGLISH));
            assertEquals("billing.payment.referenceRequired", refused.getMessageKey(), method);
        }
        verify(paymentTransactionRepository, never()).save(any());
    }

    /** QA D768: the QA's body named the field "referenceNumber"; it was dropped, so the reuse check never ran. */
    @Test
    void qaD768_referenceNumberInTheBodyIsTheReference_andAReuseIsRefused() throws Exception {
        com.example.hms.payload.dto.StaffPaymentRequestDTO dto = tools.jackson.databind.json.JsonMapper.builder().build()
            .readValue("{\"amount\":100,\"method\":\"INSURANCE\",\"referenceNumber\":\"CNAMU-R11-1\"}",
                com.example.hms.payload.dto.StaffPaymentRequestDTO.class);
        assertEquals("CNAMU-R11-1", dto.getReference());

        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        invoice.setHospital(hospital);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentTransactionRepository.existsLiveReference(any(), eq("cnamu-r11-1"), any())).thenReturn(true);
        UUID invoiceId = invoice.getId();
        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.recordStaffPayment(invoiceId, dto, null, Locale.ENGLISH));
        assertEquals("billing.payment.duplicateReference", refused.getMessageKey());
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
    void aSecondCheckInOfTheSameVisitIsNotBilledAgain_D936() {
        com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
        encounter.setId(UUID.randomUUID());
        BillingInvoice first = BillingInvoice.builder().encounter(encounter).checkInCopay(true)
            .status(InvoiceStatus.PAID).build();
        when(invoiceRepository.findByEncounter_IdAndStatusNot(encounter.getId(), InvoiceStatus.DRAFT))
            .thenReturn(List.of(first));

        billingInvoiceService.recordCheckInCopay(encounter, new BigDecimal("1000"), UUID.randomUUID());

        verify(invoiceRepository, never()).saveAndFlush(any());
        verify(paymentTransactionRepository, never()).save(any());
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

    @Test
    void recordCheckInCopay_keepsTheMethodAndReference_andRefusesMobileMoneyWithoutOne_D975() {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setHospital(hospital);
        encounter.setPatient(patient);

        assertThrows(com.example.hms.exception.BusinessException.class, () -> billingInvoiceService.recordCheckInCopay(
            encounter, new BigDecimal("1500"), com.example.hms.enums.PaymentMethod.MOBILE_MONEY, " ", UUID.randomUUID()));
        verify(invoiceRepository, never()).saveAndFlush(any(BillingInvoice.class));

        when(invoiceNumberAllocator.next(any(), any())).thenReturn("GH-2026-000043");
        when(invoiceRepository.saveAndFlush(any(BillingInvoice.class))).thenAnswer(inv -> {
            BillingInvoice b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentTransactionRepository.save(any(com.example.hms.model.PaymentTransaction.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        billingInvoiceService.recordCheckInCopay(encounter, new BigDecimal("1500"),
            com.example.hms.enums.PaymentMethod.MOBILE_MONEY, "OM-778812", UUID.randomUUID());

        ArgumentCaptor<com.example.hms.model.PaymentTransaction> row =
            ArgumentCaptor.forClass(com.example.hms.model.PaymentTransaction.class);
        verify(paymentTransactionRepository).save(row.capture());
        assertEquals(com.example.hms.enums.PaymentMethod.MOBILE_MONEY, row.getValue().getPaymentMethod());
        assertEquals("OM-778812", row.getValue().getReferenceNumber());
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
        assertEquals("createdAt", orders.get(0).getProperty()); // QA D326
        assertEquals("id", orders.get(orders.size() - 1).getProperty());
    }

    @Test
    void searchInvoices_anotherHospitalInTheBodyIsRefused_D230() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_ID);
        BillingInvoiceSearchRequest request = BillingInvoiceSearchRequest.builder().hospitalId(UUID.randomUUID()).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.searchInvoices(request, PageRequest.of(0, 20), Locale.ENGLISH));
        // QA D1011: a search names the search, not an invoice's hospital.
        assertEquals("billing.search.otherHospital", refused.getMessageKey());
        assertTrue(rendered(refused, Locale.FRENCH).contains("rechercher"));
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

    // ---- QA D403: an invoice may only carry a visit of the patient it bills ----

    private com.example.hms.model.Encounter visitOf(Patient owner, String code) {
        Hospital acting = new Hospital();
        acting.setId(HOSPITAL_ID);
        com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setCode(code);
        encounter.setHospital(acting);
        encounter.setPatient(owner);
        when(encounterRepository.findByCode(code)).thenReturn(Optional.of(encounter));
        return encounter;
    }

    @Test
    void createInvoice_anotherPatientsEncounterIsRefused_D403() {
        pinnedHospital();
        Patient a1 = registeredPatient();
        Patient a2 = new Patient();
        a2.setId(UUID.randomUUID());
        visitOf(a2, "ENC-20261020-214014");
        BillingInvoiceRequestDTO request = requestFor(a1).encounterReference("ENC-20261020-214014").build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.createInvoice(request, Locale.ENGLISH));
        assertEquals("billing.encounter.otherPatient", refused.getMessageKey());
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInvoice_thePatientsOwnEncounterIsLinked_D403() {
        pinnedHospital();
        Patient a1 = registeredPatient();
        com.example.hms.model.Encounter own = visitOf(a1, "ENC-OWN");

        billingInvoiceService.createInvoice(requestFor(a1).encounterReference("ENC-OWN").build(), Locale.ENGLISH);

        ArgumentCaptor<BillingInvoice> saved = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceRepository).saveAndFlush(saved.capture());
        assertEquals(own, saved.getValue().getEncounter());
    }

    @Test
    void updateInvoice_anotherPatientsEncounterIsRefused_D403() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        Patient a2 = new Patient();
        a2.setId(UUID.randomUUID());
        visitOf(a2, "ENC-A2");
        UUID invoiceId = invoice.getId();
        BillingInvoiceRequestDTO request = editOf().encounterReference("ENC-A2").build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, request, Locale.ENGLISH));
        assertEquals("billing.encounter.otherPatient", refused.getMessageKey());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void updateInvoice_movingToAnotherPatientWhileKeepingTheVisitIsRefused_D403() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        com.example.hms.model.Encounter visit = visitOf(invoice.getPatient(), "ENC-A1");
        invoice.setEncounter(visit);
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        when(patientRepository.findById(other.getId())).thenReturn(Optional.of(other));
        when(registrationRepository.existsByPatientIdAndHospitalId(other.getId(), HOSPITAL_ID)).thenReturn(true);
        UUID invoiceId = invoice.getId();
        BillingInvoiceRequestDTO request = editOf().patientId(other.getId()).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(invoiceId, request, Locale.ENGLISH));
        assertEquals("billing.encounter.otherPatient", refused.getMessageKey());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void updateInvoice_thePatientsOwnEncounterIsLinked_D403() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        com.example.hms.model.Encounter own = visitOf(invoice.getPatient(), "ENC-A1B");

        billingInvoiceService.updateInvoice(invoice.getId(), editOf().encounterReference("ENC-A1B").build(),
            Locale.ENGLISH);

        assertEquals(own, invoice.getEncounter());
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
    void recordPatientPayment_theRefusalNamesWhatThePatientTypedAndWhatIsPending_D421() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId); // owes 100
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentTransactionRepository.sumAmountByInvoiceAndStatus(
            invoice.getId(), com.example.hms.enums.PaymentStatus.PENDING)).thenReturn(new BigDecimal("80"));
        UUID invoiceId = invoice.getId();
        UUID userId = UUID.randomUUID();
        var forty = payment("CARD"); // 40 typed, 80 pending, 20 left to declare

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.recordPatientPayment(invoiceId, patientId, userId, forty, Locale.ENGLISH));

        assertEquals("billing.payment.exceedsWithPending", refused.getMessageKey());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void portalInvoicesCarryWhatIsAlreadyDeclared_D421() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);
        when(invoiceRepository.findByPatient_IdAndStatusNot(eq(patientId), eq(InvoiceStatus.DRAFT), any()))
            .thenReturn(new PageImpl<>(List.of(invoice)));
        when(paymentTransactionRepository.sumAmountByInvoiceAndStatus(
            invoice.getId(), com.example.hms.enums.PaymentStatus.PENDING)).thenReturn(new BigDecimal("150"));

        BillingInvoiceResponseDTO dto = billingInvoiceService
            .getInvoicesForPortalPatient(patientId, PageRequest.of(0, 10), Locale.ENGLISH).getContent().get(0);

        assertEquals(0, new BigDecimal("150").compareTo(dto.getPendingDeclaredAmount()));
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
        // QA D428: the copay says where it went instead of only "cancelled".
        assertEquals(visit.getId(), String.valueOf(copay.getCreditedToInvoice().getId()));
    }

    @Test
    void updateInvoice_withoutEncounterReferenceKeepsTheVisitAndCreditsTheCopay_D303() {
        // The QA's PUT: status SENT, no encounterReference in the body.
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        invoice.setTotalAmount(new BigDecimal("7450"));
        com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setHospital(invoice.getHospital());
        invoice.setEncounter(encounter);
        when(invoiceRepository.hasItems(invoice.getId())).thenReturn(true);
        BillingInvoice copay = BillingInvoice.builder().patient(invoice.getPatient()).encounter(encounter)
            .totalAmount(new BigDecimal("1000")).amountPaid(new BigDecimal("1000"))
            .status(InvoiceStatus.PAID).checkInCopay(true).build();
        copay.setId(UUID.randomUUID());
        when(invoiceRepository.findByEncounter_IdAndCheckInCopayTrueAndStatus(encounter.getId(), InvoiceStatus.PAID))
            .thenReturn(List.of(copay));
        when(invoiceRepository.findById(copay.getId())).thenReturn(Optional.of(copay));

        BillingInvoiceResponseDTO sent = billingInvoiceService.updateInvoice(invoice.getId(),
            editOf().status(InvoiceStatus.SENT).build(), Locale.ENGLISH);

        assertEquals(encounter, invoice.getEncounter());
        assertEquals(0, new BigDecimal("6450").compareTo(sent.getBalanceDue()));
        assertEquals(InvoiceStatus.CANCELLED, copay.getStatus());
        verify(encounterRepository, never()).findByCode(any());
    }

    @Test
    void updateInvoice_blankEncounterReferenceUnlinks_D303() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
        encounter.setId(UUID.randomUUID());
        invoice.setEncounter(encounter);

        billingInvoiceService.updateInvoice(invoice.getId(), editOf().encounterReference("").build(), Locale.ENGLISH);

        assertNull(invoice.getEncounter());
    }

    @Test
    void updateInvoice_validEncounterCodeIsLinked_D308() {
        // The QA's PUT: an unlinked invoice, a same-hospital visit code in the body.
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setCode("ENC-QA-308");
        encounter.setHospital(invoice.getHospital());
        when(encounterRepository.findByCode("ENC-QA-308")).thenReturn(Optional.of(encounter));

        BillingInvoiceResponseDTO linked = billingInvoiceService.updateInvoice(invoice.getId(),
            editOf().encounterReference(" ENC-QA-308 ").build(), Locale.ENGLISH);

        assertEquals(encounter, invoice.getEncounter());
        assertEquals("ENC-QA-308", linked.getEncounterCode());
    }

    @Test
    void recordPatientPayment_aCardReferenceAlreadyRecordedIsRefused_D345() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        invoice.setHospital(hospital);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentTransactionRepository.existsLiveReference(eq(HOSPITAL_ID), eq("om-20260926-7781"), any()))
            .thenReturn(true);
        UUID invoiceId = invoice.getId();
        UUID userId = UUID.randomUUID();
        var card = payment("CARD");

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.recordPatientPayment(invoiceId, patientId, userId, card, Locale.ENGLISH));
        assertEquals("billing.payment.duplicateReference", refused.getMessageKey());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void updateInvoice_aDecimalXofTotalIsRefused_D307() {
        BillingInvoice invoice = existingInvoice(InvoiceStatus.DRAFT, "0");
        UUID id = invoice.getId();
        BillingInvoiceRequestDTO edit = editOf().totalAmount(new BigDecimal("7450.25")).build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.updateInvoice(id, edit, Locale.ENGLISH));
        assertEquals("billing.payment.wholeAmountOnly", refused.getMessageKey());
    }

    @Test
    void searchOrder_isNewestCreatedFirstWhateverTheInvoiceDate_D326() {
        org.springframework.data.domain.Sort sort = BillingInvoiceServiceImpl
            .stableOrder(org.springframework.data.domain.PageRequest.of(0, 20)).getSort();

        java.util.List<org.springframework.data.domain.Sort.Order> orders = sort.toList();
        assertEquals("createdAt", orders.get(0).getProperty());
        assertTrue(orders.get(0).isDescending());
        assertEquals("id", orders.get(orders.size() - 1).getProperty());
        assertNull(sort.getOrderFor("invoiceDate"));
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

    @Test
    void receipt_ignoresAPaymentReversedBeforeThisOne_D228() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);
        invoice.setInvoiceNumber("GH-2026-000020");
        invoice.setAmountPaid(new BigDecimal("70"));
        com.example.hms.model.PaymentTransaction reversed = com.example.hms.model.PaymentTransaction.builder()
            .invoice(invoice).amount(new BigDecimal("30")).status(com.example.hms.enums.PaymentStatus.REVERSED)
            .confirmedAt(java.time.LocalDateTime.of(2026, 10, 1, 9, 0))
            .reversedAt(java.time.LocalDateTime.of(2026, 10, 1, 12, 0)).build();
        reversed.setId(UUID.randomUUID());
        com.example.hms.model.PaymentTransaction later = com.example.hms.model.PaymentTransaction.builder()
            .invoice(invoice).amount(new BigDecimal("70")).status(com.example.hms.enums.PaymentStatus.CONFIRMED)
            .confirmedAt(java.time.LocalDateTime.of(2026, 10, 2, 9, 0)).build();
        later.setId(UUID.randomUUID());
        when(invoiceRepository.findByIdWithRefs(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentTransactionRepository.findByIdAndInvoice_Id(later.getId(), invoice.getId())).thenReturn(Optional.of(later));
        when(paymentTransactionRepository.findByInvoice_IdOrderByCreatedAtAsc(invoice.getId()))
            .thenReturn(List.of(reversed, later));
        when(pdfInvoiceService.generateReceiptPdf(any(), any(), any(String.class), any(BigDecimal.class), any()))
            .thenReturn(new byte[] {1});

        billingInvoiceService.getPaymentReceiptPdf(invoice.getId(), later.getId(), Locale.FRENCH);

        verify(pdfInvoiceService).generateReceiptPdf(eq(invoice), eq(later), eq("GH-2026-000020-R2"),
            eq(new BigDecimal("70")), any());
    }

    @Test
    void receipt_stillCountsAPaymentReversedAfterThisOne_D228() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = payableInvoice(patientId);
        invoice.setInvoiceNumber("GH-2026-000021");
        invoice.setAmountPaid(new BigDecimal("70"));
        com.example.hms.model.PaymentTransaction reversed = com.example.hms.model.PaymentTransaction.builder()
            .invoice(invoice).amount(new BigDecimal("30")).status(com.example.hms.enums.PaymentStatus.REVERSED)
            .confirmedAt(java.time.LocalDateTime.of(2026, 10, 1, 9, 0))
            .reversedAt(java.time.LocalDateTime.of(2026, 10, 3, 12, 0)).build();
        reversed.setId(UUID.randomUUID());
        com.example.hms.model.PaymentTransaction later = com.example.hms.model.PaymentTransaction.builder()
            .invoice(invoice).amount(new BigDecimal("70")).status(com.example.hms.enums.PaymentStatus.CONFIRMED)
            .confirmedAt(java.time.LocalDateTime.of(2026, 10, 2, 9, 0)).build();
        later.setId(UUID.randomUUID());
        when(invoiceRepository.findByIdWithRefs(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentTransactionRepository.findByIdAndInvoice_Id(later.getId(), invoice.getId())).thenReturn(Optional.of(later));
        when(paymentTransactionRepository.findByInvoice_IdOrderByCreatedAtAsc(invoice.getId()))
            .thenReturn(List.of(reversed, later));
        when(pdfInvoiceService.generateReceiptPdf(any(), any(), any(String.class), any(BigDecimal.class), any()))
            .thenReturn(new byte[] {1});

        billingInvoiceService.getPaymentReceiptPdf(invoice.getId(), later.getId(), Locale.FRENCH);

        verify(pdfInvoiceService).generateReceiptPdf(eq(invoice), eq(later), eq("GH-2026-000021-R2"),
            eq(new BigDecimal("100")), any());
    }

    @Test
    void summarize_withNoRow_isAllZeros_D209() {
        when(invoiceRepository.summarize(any(), any())).thenReturn(List.of());

        com.example.hms.payload.dto.BillingSummaryDTO sum = billingInvoiceService.summarize(Locale.ENGLISH);

        assertEquals(0L, sum.getTotal());
        assertEquals(0L, sum.getOverdue());
        assertEquals(0, BigDecimal.ZERO.compareTo(sum.getBalance()));
    }

    @Test
    void summarize_withNullAggregates_isAllZeros_D209() {
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(new Object[6]);
        when(invoiceRepository.summarize(any(), any())).thenReturn(rows);

        com.example.hms.payload.dto.BillingSummaryDTO sum = billingInvoiceService.summarize(Locale.ENGLISH);

        assertEquals(0L, sum.getPaid());
        assertEquals(0, BigDecimal.ZERO.compareTo(sum.getTotalRevenue()));
    }

    @Test
    void recompute_aVisitInvoiceIssuedAtZeroAndPricedLater_takesOverTheCopay_D210() {
        BillingInvoice visit = existingInvoice(InvoiceStatus.SENT, "0");
        visit.setTotalAmount(BigDecimal.ZERO);
        com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
        encounter.setId(UUID.randomUUID());
        visit.setEncounter(encounter);
        BillingInvoice copay = BillingInvoice.builder().patient(visit.getPatient()).encounter(encounter)
            .totalAmount(new BigDecimal("2500")).amountPaid(new BigDecimal("2500"))
            .status(InvoiceStatus.PAID).checkInCopay(true).build();
        copay.setId(UUID.randomUUID());
        when(invoiceRepository.sumItemsByInvoiceId(visit.getId())).thenReturn(new BigDecimal("7450"));
        when(invoiceRepository.findByEncounter_IdAndCheckInCopayTrueAndStatus(encounter.getId(), InvoiceStatus.PAID))
            .thenReturn(List.of(copay));
        when(invoiceRepository.findLockedById(copay.getId())).thenReturn(Optional.of(copay));
        when(paymentTransactionRepository.findByInvoice_IdOrderByCreatedAtAsc(copay.getId())).thenReturn(List.of());

        billingInvoiceService.recomputeAndPersistTotals(visit.getId());

        assertEquals(0, new BigDecimal("2500").compareTo(visit.getAmountPaid()));
        assertEquals(InvoiceStatus.PARTIALLY_PAID, visit.getStatus());
        assertEquals(InvoiceStatus.CANCELLED, copay.getStatus());
    }

    /** A visit invoice of 7 450 and its paid 2 500 check-in copay on one encounter (QA D272). */
    private BillingInvoice[] visitAndCopay(InvoiceStatus visitStatus, BigDecimal visitPaid) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
        encounter.setId(UUID.randomUUID());
        BillingInvoice visit = BillingInvoice.builder().patient(patient).encounter(encounter)
            .totalAmount(new BigDecimal("7450")).amountPaid(visitPaid).status(visitStatus).build();
        visit.setId(UUID.randomUUID());
        BillingInvoice copay = BillingInvoice.builder().patient(patient).encounter(encounter)
            .totalAmount(new BigDecimal("2500")).amountPaid(new BigDecimal("2500"))
            .status(InvoiceStatus.PAID).checkInCopay(true).build();
        copay.setId(UUID.randomUUID());
        when(invoiceRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(invoiceRepository.findById(copay.getId())).thenReturn(Optional.of(copay));
        when(invoiceRepository.findByEncounter_IdAndCheckInCopayTrueAndStatus(encounter.getId(), InvoiceStatus.PAID))
            .thenReturn(List.of(copay));
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        return new BillingInvoice[] {visit, copay};
    }

    @Test
    void recordStaffPayment_theCopayPaidAfterTheInvoiceWasIssuedIsNotChargedAgain_D272() {
        BillingInvoice[] pair = visitAndCopay(InvoiceStatus.SENT, BigDecimal.ZERO);
        BillingInvoice visit = pair[0];
        com.example.hms.payload.dto.StaffPaymentRequestDTO full =
            new com.example.hms.payload.dto.StaffPaymentRequestDTO(new BigDecimal("7450"), "cash", null, null);

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.recordStaffPayment(visit.getId(), full, UUID.randomUUID(), Locale.ENGLISH));

        assertEquals("billing.payment.exceedsBalance", refused.getMessageKey());
    }

    @Test
    void recordStaffPayment_takesTheCopayThenTheBalanceSettlesTheVisit_D272() {
        BillingInvoice[] pair = visitAndCopay(InvoiceStatus.SENT, BigDecimal.ZERO);
        BillingInvoice visit = pair[0];
        BillingInvoice copay = pair[1];

        billingInvoiceService.recordStaffPayment(visit.getId(),
            new com.example.hms.payload.dto.StaffPaymentRequestDTO(new BigDecimal("4950"), "cash", null, null),
            UUID.randomUUID(), Locale.ENGLISH);

        assertEquals(InvoiceStatus.PAID, visit.getStatus());
        assertEquals(0, new BigDecimal("7450").compareTo(visit.getAmountPaid()));
        assertEquals(InvoiceStatus.CANCELLED, copay.getStatus());
    }

    @Test
    void creditCheckInCopays_aPartPaidVisitInvoiceStillTakesTheCopay_D272() {
        BillingInvoice[] pair = visitAndCopay(InvoiceStatus.PARTIALLY_PAID, new BigDecimal("1000"));

        billingInvoiceService.creditCheckInCopays(pair[0].getId());

        assertEquals(0, new BigDecimal("3500").compareTo(pair[0].getAmountPaid()));
        assertEquals(InvoiceStatus.CANCELLED, pair[1].getStatus());
    }

    @Test
    void createInvoice_legacyKeyTakesThePatientId_D269() {
        pinnedHospital();
        Patient patient = registeredPatient();

        BillingInvoiceResponseDTO response = billingInvoiceService.createInvoice(BillingInvoiceRequestDTO.builder()
            .patientEmail(patient.getId().toString()).build(), Locale.ENGLISH);

        assertEquals(patient.getId().toString(), response.getPatientId());
        verify(patientRepository, never()).findByUsernameOrEmail(any());
    }

    @Test
    void createInvoice_legacyKeyTakesTheMrnAtThisHospital_D269() {
        pinnedHospital();
        Patient patient = registeredPatient();
        com.example.hms.model.PatientHospitalRegistration registration =
            new com.example.hms.model.PatientHospitalRegistration();
        registration.setPatient(patient);
        registration.setMrn("MRN-A5");
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(HOSPITAL_ID, "mrn-a5"))
            .thenReturn(List.of(registration));

        BillingInvoiceResponseDTO response = billingInvoiceService.createInvoice(BillingInvoiceRequestDTO.builder()
            .patientEmail(" mrn-a5 ").build(), Locale.ENGLISH);

        assertEquals(patient.getId().toString(), response.getPatientId());
    }

    // ---- QA D529: a second invoice for a stay already invoiced ----

    @Test
    void createInvoice_aSecondInvoiceForAPaidStayIsRefusedWithTheExistingNumber_D529() {
        // QA D529: BILLING_A POSTed /billing-invoices for the stay's encounter
        // code when the stay's invoice was already PAID; a second invoice was
        // created with no warning.
        pinnedHospital();
        Patient a2 = registeredPatient();
        com.example.hms.model.Encounter stay = visitOf(a2, "ENC-STAY-1");
        BillingInvoice paid = new BillingInvoice();
        paid.setId(UUID.randomUUID());
        paid.setInvoiceNumber("RHA-2026-000012");
        paid.setStatus(com.example.hms.enums.InvoiceStatus.PAID);
        when(invoiceRepository.findByEncounter_IdAndStatusNot(stay.getId(), com.example.hms.enums.InvoiceStatus.CANCELLED))
            .thenReturn(List.of(paid));
        BillingInvoiceRequestDTO request = requestFor(a2).encounterReference("ENC-STAY-1")
            .clientRequestId(UUID.randomUUID()).build();

        com.example.hms.exception.ConflictException refused = assertThrows(
            com.example.hms.exception.ConflictException.class,
            () -> billingInvoiceService.createInvoice(request, Locale.ENGLISH));
        assertTrue(refused.getMessage().contains("RHA-2026-000012") || refused.getMessage().contains("encounterAlreadyInvoiced"));
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInvoice_aConfirmedSupplementaryInvoiceIsCreated_D529() {
        pinnedHospital();
        Patient a2 = registeredPatient();
        com.example.hms.model.Encounter stay = visitOf(a2, "ENC-STAY-2");
        BillingInvoice paid = new BillingInvoice();
        paid.setInvoiceNumber("RHA-2026-000013");
        paid.setStatus(com.example.hms.enums.InvoiceStatus.PAID);
        org.mockito.Mockito.lenient().when(invoiceRepository.findByEncounter_IdAndStatusNot(
                stay.getId(), com.example.hms.enums.InvoiceStatus.CANCELLED))
            .thenReturn(List.of(paid));

        billingInvoiceService.createInvoice(requestFor(a2).encounterReference("ENC-STAY-2")
            .supplementary(true).build(), Locale.ENGLISH);

        verify(invoiceRepository).saveAndFlush(any(BillingInvoice.class));
    }

    // =========================================================================
    // QA D974: discount, waiver and credit note, each with a reason
    // =========================================================================

    private com.example.hms.repository.InvoiceAdjustmentRepository adjustments() {
        com.example.hms.repository.InvoiceAdjustmentRepository repo =
            mock(com.example.hms.repository.InvoiceAdjustmentRepository.class);
        when(repo.sumByInvoiceItemId(any())).thenReturn(BigDecimal.ZERO);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        billingInvoiceService.setAdjustmentRepository(repo);
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        return repo;
    }

    private BillingInvoice issued(String total, String paid) {
        BillingInvoice invoice = payableInvoice(UUID.randomUUID());
        invoice.setTotalAmount(new BigDecimal(total));
        invoice.setAmountPaid(new BigDecimal(paid));
        invoice.setStatus(new BigDecimal(paid).signum() > 0 ? InvoiceStatus.PARTIALLY_PAID : InvoiceStatus.SENT);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        return invoice;
    }

    private static com.example.hms.payload.dto.InvoiceAdjustmentRequestDTO adjustment(
            com.example.hms.enums.InvoiceAdjustmentType type, String amount, String reason, UUID line) {
        return com.example.hms.payload.dto.InvoiceAdjustmentRequestDTO.builder()
            .type(type).amount(amount != null ? new BigDecimal(amount) : null).reason(reason).invoiceItemId(line)
            .build();
    }

    @Test
    void createInvoice_withoutVisitCode_neverGuessesTheVisit_D936() {
        pinnedHospital();
        Patient patient = registeredPatient();

        BillingInvoiceResponseDTO invoice = billingInvoiceService.createInvoice(
            requestFor(patient).totalAmount(new BigDecimal("3000")).status(InvoiceStatus.SENT).build(), Locale.FRENCH);

        // The form proposes the visit of an open copay; the server links only the visit the clerk sends.
        assertNull(invoice.getEncounterCode());
    }

    @Test
    void addAdjustment_aCreditNoteCannotExceedWhatTheInvoiceStillOwes_D974() {
        adjustments();
        BillingInvoice invoice = issued("2000", "0");
        com.example.hms.model.InvoiceItem line = new com.example.hms.model.InvoiceItem();
        line.setId(UUID.randomUUID());
        line.setBillingInvoice(invoice);
        line.setTotalPrice(new BigDecimal("10000"));
        when(invoiceItemRepository.findById(line.getId())).thenReturn(Optional.of(line));

        com.example.hms.exception.BusinessException refused = assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.addAdjustment(invoice.getId(),
                adjustment(com.example.hms.enums.InvoiceAdjustmentType.CREDIT_NOTE, "5000", "Erreur", line.getId()),
                UUID.randomUUID(), Locale.FRENCH));
        assertEquals("billing.adjustment.moreThanTotal", refused.getMessageKey());

        // Left empty, a credit note never silently credits less than the line: it says how much it can.
        com.example.hms.exception.BusinessException capped = assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.addAdjustment(invoice.getId(),
                adjustment(com.example.hms.enums.InvoiceAdjustmentType.CREDIT_NOTE, null, "Erreur", line.getId()),
                UUID.randomUUID(), Locale.FRENCH));
        assertEquals("billing.adjustment.creditBeyondUnpaid", capped.getMessageKey());
    }

    @Test
    void anInvoiceAWaiverSettledCanStillBeCancelled_D974() {
        // Settled by a waiver: PAID, no money received. A mistyped waiver is undone by cancelling.
        InvoiceStatus next = org.springframework.test.util.ReflectionTestUtils.invokeMethod(BillingInvoiceServiceImpl.class,
            "requestedStatusOnUpdate", InvoiceStatus.PAID, InvoiceStatus.CANCELLED, false);
        assertEquals(InvoiceStatus.CANCELLED, next);
        // Only cancelled: a waived invoice never goes back to "sent" at 0.
        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(BillingInvoiceServiceImpl.class,
                "requestedStatusOnUpdate", InvoiceStatus.PAID, InvoiceStatus.SENT, false));
        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(BillingInvoiceServiceImpl.class,
                "requestedStatusOnUpdate", InvoiceStatus.PAID, InvoiceStatus.CANCELLED, true));
    }

    @Test
    void addAdjustment_aFullyCreditedLineSaysSo_D974() {
        com.example.hms.repository.InvoiceAdjustmentRepository repo = adjustments();
        BillingInvoice invoice = issued("6000", "0");
        com.example.hms.model.InvoiceItem line = new com.example.hms.model.InvoiceItem();
        line.setId(UUID.randomUUID());
        line.setBillingInvoice(invoice);
        line.setTotalPrice(new BigDecimal("3000"));
        when(invoiceItemRepository.findById(line.getId())).thenReturn(Optional.of(line));
        when(repo.sumByInvoiceItemId(line.getId())).thenReturn(new BigDecimal("3000"));

        com.example.hms.exception.BusinessException refused = assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.addAdjustment(invoice.getId(),
                adjustment(com.example.hms.enums.InvoiceAdjustmentType.CREDIT_NOTE, null, "Erreur", line.getId()),
                UUID.randomUUID(), Locale.FRENCH));
        assertEquals("billing.adjustment.lineFullyCredited", refused.getMessageKey());
    }

    @Test
    void addAdjustment_anEmptyCreditNoteStaysWithinWhatIsStillOwed_D974() {
        adjustments();
        BillingInvoice invoice = issued("10000", "6000");
        com.example.hms.model.InvoiceItem line = new com.example.hms.model.InvoiceItem();
        line.setId(UUID.randomUUID());
        line.setBillingInvoice(invoice);
        line.setTotalPrice(new BigDecimal("8000"));
        when(invoiceItemRepository.findById(line.getId())).thenReturn(Optional.of(line));

        // The whole line cannot be credited without reversing a payment: the refusal says how much can.
        com.example.hms.exception.BusinessException refused = assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.addAdjustment(invoice.getId(),
                adjustment(com.example.hms.enums.InvoiceAdjustmentType.CREDIT_NOTE, null, "NFS non faite", line.getId()),
                UUID.randomUUID(), Locale.FRENCH));
        assertEquals("billing.adjustment.creditBeyondUnpaid", refused.getMessageKey());
        assertEquals(0, new BigDecimal("10000").compareTo(invoice.getTotalAmount()));
    }

    @Test
    void addAdjustment_aLowerTotalTakesTheVisitsCopay_D936() {
        adjustments();
        BillingInvoice invoice = issued("10000", "0");
        com.example.hms.model.Encounter visit = new com.example.hms.model.Encounter();
        visit.setId(UUID.randomUUID());
        invoice.setEncounter(visit);

        billingInvoiceService.addAdjustment(invoice.getId(),
            adjustment(com.example.hms.enums.InvoiceAdjustmentType.DISCOUNT, "4000", "Remise", null),
            UUID.randomUUID(), Locale.FRENCH);

        verify(invoiceRepository).findByEncounter_IdAndCheckInCopayTrueAndStatus(visit.getId(), InvoiceStatus.PAID);
    }

    /** A SENT 20 000 visit invoice, nothing paid, its 5 000 check-in copay still standing (PAID). */
    private BillingInvoice[] adjustableVisitWithCopay() {
        adjustments();
        BillingInvoice invoice = issued("20000", "0");
        com.example.hms.model.Encounter visit = new com.example.hms.model.Encounter();
        visit.setId(UUID.randomUUID());
        invoice.setEncounter(visit);
        BillingInvoice copay = BillingInvoice.builder().patient(invoice.getPatient()).encounter(visit)
            .totalAmount(new BigDecimal("5000")).amountPaid(new BigDecimal("5000"))
            .status(InvoiceStatus.PAID).checkInCopay(true).build();
        copay.setId(UUID.randomUUID());
        when(invoiceRepository.findByEncounter_IdAndCheckInCopayTrueAndStatus(visit.getId(), InvoiceStatus.PAID))
            .thenReturn(List.of(copay));
        when(invoiceRepository.findLockedById(copay.getId())).thenReturn(Optional.of(copay));
        when(paymentTransactionRepository.findByInvoice_IdOrderByCreatedAtAsc(copay.getId())).thenReturn(List.of());
        return new BillingInvoice[] {invoice, copay};
    }

    @Test
    void addAdjustment_creditsTheVisitsCopayWhenItFits_D936() {
        BillingInvoice[] pair = adjustableVisitWithCopay();

        billingInvoiceService.addAdjustment(pair[0].getId(),
            adjustment(com.example.hms.enums.InvoiceAdjustmentType.DISCOUNT, "2000", "Remise", null),
            UUID.randomUUID(), Locale.FRENCH);

        assertEquals(0, new BigDecimal("5000").compareTo(pair[0].getAmountPaid()));
        assertEquals(InvoiceStatus.CANCELLED, pair[1].getStatus());
    }

    @Test
    void addAdjustment_neverCreditsTheCopayOverADeclaredPayment_D724() {
        // 18 000 left after the discount; 15 000 declared by mobile money and not yet confirmed:
        // moving the 5 000 copay would leave 13 000, and the declaration could never be confirmed.
        BillingInvoice[] pair = adjustableVisitWithCopay();
        when(paymentTransactionRepository.sumAmountByInvoiceAndStatus(pair[0].getId(), com.example.hms.enums.PaymentStatus.PENDING))
            .thenReturn(new BigDecimal("15000"));

        billingInvoiceService.addAdjustment(pair[0].getId(),
            adjustment(com.example.hms.enums.InvoiceAdjustmentType.DISCOUNT, "2000", "Remise", null),
            UUID.randomUUID(), Locale.FRENCH);

        assertEquals(0, BigDecimal.ZERO.compareTo(pair[0].getAmountPaid()));
        assertEquals(InvoiceStatus.PAID, pair[1].getStatus());
        assertEquals(0, new BigDecimal("18000").compareTo(pair[0].getTotalAmount()));
    }

    @Test
    void theCopayIsReReadUnderItsLock_aCopayAnotherInvoiceTookMeanwhileIsNotCreditedTwice_D936() {
        BillingInvoice[] pair = adjustableVisitWithCopay();
        jakarta.persistence.EntityManager em = mock(jakarta.persistence.EntityManager.class);
        // While this request waited for the lock, another invoice took the copay and committed.
        org.mockito.Mockito.doAnswer(inv -> {
            BillingInvoice c = inv.getArgument(0);
            c.setStatus(InvoiceStatus.CANCELLED);
            c.setAmountPaid(BigDecimal.ZERO);
            return null;
        }).when(em).refresh(pair[1]);
        billingInvoiceService.setEntityManager(em);

        billingInvoiceService.addAdjustment(pair[0].getId(),
            adjustment(com.example.hms.enums.InvoiceAdjustmentType.DISCOUNT, "2000", "Remise", null),
            UUID.randomUUID(), Locale.FRENCH);

        verify(em).refresh(pair[1]);
        assertEquals(0, BigDecimal.ZERO.compareTo(pair[0].getAmountPaid()));
    }

    @Test
    void addAdjustment_aLineDiscountWithoutAnAmountIsRefusedNot500_D974() {
        adjustments();
        BillingInvoice invoice = issued("6000", "0");
        com.example.hms.model.InvoiceItem line = new com.example.hms.model.InvoiceItem();
        line.setId(UUID.randomUUID());
        line.setBillingInvoice(invoice);
        line.setTotalPrice(new BigDecimal("3000"));
        when(invoiceItemRepository.findById(line.getId())).thenReturn(Optional.of(line));

        com.example.hms.exception.BusinessException refused = assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.addAdjustment(invoice.getId(),
                adjustment(com.example.hms.enums.InvoiceAdjustmentType.DISCOUNT, null, "Remise", line.getId()),
                UUID.randomUUID(), Locale.FRENCH));
        assertEquals("billing.adjustment.amountPositive", refused.getMessageKey());
    }

    @Test
    void addAdjustment_aDiscountOnALineIsHeldAgainstThatLine_D974() {
        com.example.hms.repository.InvoiceAdjustmentRepository repo = adjustments();
        BillingInvoice invoice = issued("6000", "0");
        com.example.hms.model.InvoiceItem line = new com.example.hms.model.InvoiceItem();
        line.setId(UUID.randomUUID());
        line.setBillingInvoice(invoice);
        line.setTotalPrice(new BigDecimal("3000"));
        when(invoiceItemRepository.findById(line.getId())).thenReturn(Optional.of(line));

        billingInvoiceService.addAdjustment(invoice.getId(),
            adjustment(com.example.hms.enums.InvoiceAdjustmentType.DISCOUNT, "1000", "Remise", line.getId()),
            UUID.randomUUID(), Locale.FRENCH);

        ArgumentCaptor<com.example.hms.model.InvoiceAdjustment> row =
            ArgumentCaptor.forClass(com.example.hms.model.InvoiceAdjustment.class);
        verify(repo).save(row.capture());
        assertEquals(line, row.getValue().getInvoiceItem());
    }

    @Test
    void addAdjustment_aWaiverLowersWhatIsOwedAndKeepsItsReason_D974() {
        com.example.hms.repository.InvoiceAdjustmentRepository repo = adjustments();
        BillingInvoice invoice = issued("4725", "0");

        BillingInvoiceResponseDTO after = billingInvoiceService.addAdjustment(invoice.getId(),
            adjustment(com.example.hms.enums.InvoiceAdjustmentType.WAIVER, "4725", "Gratuité : patient indigent", null),
            UUID.randomUUID(), Locale.FRENCH);

        assertEquals(0, BigDecimal.ZERO.compareTo(after.getTotalAmount()));
        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
        ArgumentCaptor<com.example.hms.model.InvoiceAdjustment> row =
            ArgumentCaptor.forClass(com.example.hms.model.InvoiceAdjustment.class);
        verify(repo).save(row.capture());
        assertEquals("Gratuité : patient indigent", row.getValue().getReason());
        assertEquals(com.example.hms.enums.InvoiceAdjustmentType.WAIVER, row.getValue().getType());
    }

    @Test
    void addAdjustment_aCreditNoteReversesTheLineNotDone_D974() {
        adjustments();
        BillingInvoice invoice = issued("8225", "3500");
        com.example.hms.model.InvoiceItem nfs = new com.example.hms.model.InvoiceItem();
        nfs.setId(UUID.randomUUID());
        nfs.setBillingInvoice(invoice);
        nfs.setTotalPrice(new BigDecimal("4725"));
        when(invoiceItemRepository.findById(nfs.getId())).thenReturn(Optional.of(nfs));

        billingInvoiceService.addAdjustment(invoice.getId(),
            adjustment(com.example.hms.enums.InvoiceAdjustmentType.CREDIT_NOTE, null, "NFS non réalisée", nfs.getId()),
            UUID.randomUUID(), Locale.FRENCH);

        assertEquals(0, new BigDecimal("3500").compareTo(invoice.getTotalAmount()));
        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
    }

    @Test
    void addAdjustment_refusesWithoutAReasonOrBelowWhatWasPaid_D974() {
        com.example.hms.repository.InvoiceAdjustmentRepository repo = adjustments();
        BillingInvoice invoice = issued("4000", "3000");

        com.example.hms.exception.BusinessException noReason = assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.addAdjustment(invoice.getId(),
                adjustment(com.example.hms.enums.InvoiceAdjustmentType.DISCOUNT, "500", "  ", null),
                UUID.randomUUID(), Locale.FRENCH));
        assertEquals("billing.adjustment.reasonRequired", noReason.getMessageKey());

        com.example.hms.exception.BusinessException belowPaid = assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.addAdjustment(invoice.getId(),
                adjustment(com.example.hms.enums.InvoiceAdjustmentType.DISCOUNT, "1500", "Remise", null),
                UUID.randomUUID(), Locale.FRENCH));
        assertEquals("billing.adjustment.belowPaid", belowPaid.getMessageKey());
        verify(repo, never()).save(any());
    }

    @Test
    void addAdjustment_aCreditNoteNeedsALineOfThisInvoiceAndNoMoreThanIsLeft_D974() {
        com.example.hms.repository.InvoiceAdjustmentRepository repo = adjustments();
        BillingInvoice invoice = issued("6000", "0");
        com.example.hms.model.InvoiceItem line = new com.example.hms.model.InvoiceItem();
        line.setId(UUID.randomUUID());
        line.setBillingInvoice(invoice);
        line.setTotalPrice(new BigDecimal("3000"));
        when(invoiceItemRepository.findById(line.getId())).thenReturn(Optional.of(line));
        when(repo.sumByInvoiceItemId(line.getId())).thenReturn(new BigDecimal("2000"));

        com.example.hms.exception.BusinessException tooMuch = assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.addAdjustment(invoice.getId(),
                adjustment(com.example.hms.enums.InvoiceAdjustmentType.CREDIT_NOTE, "1500", "Erreur", line.getId()),
                UUID.randomUUID(), Locale.FRENCH));
        assertEquals("billing.adjustment.moreThanLine", tooMuch.getMessageKey());

        BillingInvoice other = issued("1000", "0");
        when(invoiceItemRepository.findById(line.getId())).thenReturn(Optional.of(line));
        com.example.hms.exception.BusinessException foreign = assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.addAdjustment(other.getId(),
                adjustment(com.example.hms.enums.InvoiceAdjustmentType.CREDIT_NOTE, null, "Erreur", line.getId()),
                UUID.randomUUID(), Locale.FRENCH));
        assertEquals("billing.adjustment.lineNotOnInvoice", foreign.getMessageKey());
    }

    @Test
    void addAdjustment_aDraftIsEditedNotAdjusted_D974() {
        adjustments();
        BillingInvoice draft = issued("1000", "0");
        draft.setStatus(InvoiceStatus.DRAFT);

        com.example.hms.exception.BusinessException refused = assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.addAdjustment(draft.getId(),
                adjustment(com.example.hms.enums.InvoiceAdjustmentType.DISCOUNT, "100", "Remise", null),
                UUID.randomUUID(), Locale.FRENCH));
        assertEquals("billing.adjustment.notIssued", refused.getMessageKey());
    }

    @Test
    void portalDetail_showsWhatWasWaivedButNotTheClerksReason_D974() {
        com.example.hms.repository.InvoiceAdjustmentRepository repo = adjustments();
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = portalInvoice(patientId, InvoiceStatus.PAID);
        when(repo.findByInvoice_IdOrderByCreatedAtAsc(invoice.getId())).thenReturn(List.of(
            com.example.hms.model.InvoiceAdjustment.builder().invoice(invoice)
                .type(com.example.hms.enums.InvoiceAdjustmentType.WAIVER)
                .amount(new BigDecimal("500")).reason("indigent, vu avec l'assistante sociale").build()));
        billingInvoiceService.setInvoiceItemMapper(new com.example.hms.mapper.InvoiceItemMapper());

        com.example.hms.payload.dto.portal.PortalInvoiceDetailDTO detail =
            billingInvoiceService.getPortalInvoiceDetail(invoice.getId(), patientId);

        assertEquals(1, detail.adjustments().size());
        assertEquals("WAIVER", detail.adjustments().get(0).type());
        assertNull(detail.adjustments().get(0).reason());
    }

    @Test
    void addAdjustment_neverUnderAPaymentThePatientDeclared_D974() {
        com.example.hms.repository.InvoiceAdjustmentRepository repo = adjustments();
        BillingInvoice invoice = issued("10000", "0");
        when(paymentTransactionRepository.sumAmountByInvoiceAndStatus(invoice.getId(),
            com.example.hms.enums.PaymentStatus.PENDING)).thenReturn(new BigDecimal("10000"));

        com.example.hms.exception.BusinessException refused = assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.addAdjustment(invoice.getId(),
                adjustment(com.example.hms.enums.InvoiceAdjustmentType.DISCOUNT, "4000", "Remise", null),
                UUID.randomUUID(), Locale.FRENCH));
        assertEquals("billing.adjustment.belowPending", refused.getMessageKey());
        verify(repo, never()).save(any());
    }

    @Test
    void addAdjustment_aPaidInvoiceIsNotAdjusted_D974() {
        adjustments();
        BillingInvoice paid = issued("1000", "1000");
        paid.setStatus(InvoiceStatus.PAID);

        com.example.hms.exception.BusinessException refused = assertThrows(com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.addAdjustment(paid.getId(),
                adjustment(com.example.hms.enums.InvoiceAdjustmentType.DISCOUNT, "100", "Remise", null),
                UUID.randomUUID(), Locale.FRENCH));
        assertEquals("billing.adjustment.notIssued", refused.getMessageKey());
    }

    @Test
    void portalPdf_carriesTheAdjustmentButNotTheClerksReason_D974() {
        com.example.hms.repository.InvoiceAdjustmentRepository repo = adjustments();
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = portalInvoice(patientId, InvoiceStatus.PAID);
        when(repo.findByInvoice_IdOrderByCreatedAtAsc(invoice.getId())).thenReturn(List.of(
            com.example.hms.model.InvoiceAdjustment.builder().invoice(invoice)
                .type(com.example.hms.enums.InvoiceAdjustmentType.WAIVER)
                .amount(new BigDecimal("500")).reason("famille en conflit").build()));
        when(pdfInvoiceService.generateInvoicePdf(any(), any(), any(), any(), any())).thenReturn(new byte[] {1});

        billingInvoiceService.getPortalInvoicePdf(invoice.getId(), patientId, Locale.FRENCH);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.example.hms.model.InvoiceAdjustment>> printed = ArgumentCaptor.forClass(List.class);
        verify(pdfInvoiceService).generateInvoicePdf(any(), any(), any(), printed.capture(), any());
        assertEquals(1, printed.getValue().size());
        assertNull(printed.getValue().get(0).getReason());
    }

    @Test
    void aCopayAlreadyFoldedIntoTheVisitInvoiceStillCountsAsTheVisitsCopay_D936() {
        com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
        encounter.setId(UUID.randomUUID());
        BillingInvoice visitInvoice = BillingInvoice.builder().status(InvoiceStatus.SENT).build();
        BillingInvoice folded = BillingInvoice.builder().encounter(encounter).checkInCopay(true)
            .status(InvoiceStatus.CANCELLED).creditedToInvoice(visitInvoice).build();
        when(invoiceRepository.findByEncounter_IdAndStatusNot(encounter.getId(), InvoiceStatus.DRAFT))
            .thenReturn(List.of(folded));

        billingInvoiceService.recordCheckInCopay(encounter, new BigDecimal("1000"), UUID.randomUUID());

        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    // =========================================================================
    // QA D982: the patient sees the lines, the payments and the documents
    // =========================================================================

    private BillingInvoice portalInvoice(UUID patientId, InvoiceStatus status) {
        BillingInvoice invoice = payableInvoice(patientId);
        invoice.setStatus(status);
        invoice.setInvoiceNumber("GH-2026-000012");
        when(invoiceRepository.findByIdWithRefs(invoice.getId())).thenReturn(Optional.of(invoice));
        return invoice;
    }

    @Test
    void portalDetail_listsTheLinesAndThePaymentsWithoutTheCashiersNotes_D982() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = portalInvoice(patientId, InvoiceStatus.PARTIALLY_PAID);
        com.example.hms.model.InvoiceItem line = new com.example.hms.model.InvoiceItem();
        line.setId(UUID.randomUUID());
        line.setBillingInvoice(invoice);
        line.setItemDescription("Consultation");
        line.setQuantity(1);
        line.setUnitPrice(new BigDecimal("3000"));
        when(invoiceItemRepository.findByBillingInvoiceId(invoice.getId())).thenReturn(List.of(line));
        com.example.hms.model.PaymentTransaction declared = com.example.hms.model.PaymentTransaction.builder()
            .invoice(invoice).amount(new BigDecimal("500")).notes("vu au guichet, client pressé")
            .reversalReason("référence douteuse")
            .status(com.example.hms.enums.PaymentStatus.PENDING).build();
        when(paymentTransactionRepository.findByInvoice_IdOrderByCreatedAtAsc(invoice.getId()))
            .thenReturn(List.of(declared));
        billingInvoiceService.setInvoiceItemMapper(new com.example.hms.mapper.InvoiceItemMapper());

        com.example.hms.payload.dto.portal.PortalInvoiceDetailDTO detail =
            billingInvoiceService.getPortalInvoiceDetail(invoice.getId(), patientId);

        assertEquals(1, detail.items().size());
        assertEquals("Consultation", detail.items().get(0).getItemDescription());
        assertEquals(1, detail.payments().size());
        assertEquals("PENDING", detail.payments().get(0).status());
        assertNull(detail.payments().get(0).notes());
        assertNull(detail.payments().get(0).reversalReason());
        assertEquals("GH-2026-000012", detail.invoice().getInvoiceNumber());
    }

    @Test
    void portalDetail_anotherPatientsInvoiceAnswersAsAMissingOne_D982() {
        BillingInvoice invoice = portalInvoice(UUID.randomUUID(), InvoiceStatus.SENT);
        UUID stranger = UUID.randomUUID();

        assertThrows(com.example.hms.exception.ResourceNotFoundException.class,
            () -> billingInvoiceService.getPortalInvoiceDetail(invoice.getId(), stranger));
        assertThrows(com.example.hms.exception.ResourceNotFoundException.class,
            () -> billingInvoiceService.getPortalInvoicePdf(invoice.getId(), stranger, Locale.FRENCH));
        assertThrows(com.example.hms.exception.ResourceNotFoundException.class,
            () -> billingInvoiceService.getPortalReceiptPdf(invoice.getId(), UUID.randomUUID(), stranger, Locale.FRENCH));
        verify(pdfInvoiceService, never()).generateInvoicePdf(any(), any(), any(), any(), any());
    }

    @Test
    void portalDetail_aDraftIsNotIssuedToThePatientYet_D982() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice draft = portalInvoice(patientId, InvoiceStatus.DRAFT);

        assertThrows(com.example.hms.exception.ResourceNotFoundException.class,
            () -> billingInvoiceService.getPortalInvoiceDetail(draft.getId(), patientId));
    }

    @Test
    void portalPdf_thePatientDownloadsTheirOwnInvoice_D982() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = portalInvoice(patientId, InvoiceStatus.PAID);
        when(pdfInvoiceService.generateInvoicePdf(any(), any(), any(), any(), any())).thenReturn(new byte[] {1, 2});

        com.example.hms.payload.dto.InvoicePdfResponseDTO pdf =
            billingInvoiceService.getPortalInvoicePdf(invoice.getId(), patientId, Locale.FRENCH);

        assertEquals("GH-2026-000012", pdf.suggestedFilename());
        verify(roleValidator, never()).requireActiveHospitalId();
    }

    // =========================================================================
    // QA D936: the visit code left empty still takes the check-in copay
    // =========================================================================

    @Test
    void createInvoice_aFoldedCheckInCopayDoesNotBlockTheVisitInvoice_D529() {
        pinnedHospital();
        Patient a2 = registeredPatient();
        com.example.hms.model.Encounter visit = visitOf(a2, "ENC-VISIT-3");
        BillingInvoice copay = new BillingInvoice();
        copay.setStatus(com.example.hms.enums.InvoiceStatus.PAID);
        copay.setCheckInCopay(true);
        when(invoiceRepository.findByEncounter_IdAndStatusNot(visit.getId(), com.example.hms.enums.InvoiceStatus.CANCELLED))
            .thenReturn(List.of(copay));

        billingInvoiceService.createInvoice(requestFor(a2).encounterReference("ENC-VISIT-3").build(), Locale.ENGLISH);

        verify(invoiceRepository).saveAndFlush(any(BillingInvoice.class));
    }

    // =========================================================================
    // QA D828: the refusal names every amount in F CFA, in each language
    // =========================================================================

    /** The refusal as the API answers it: the real bundles, in that language, spaces made plain. */
    private static String rendered(com.example.hms.exception.BusinessException refused, Locale locale) {
        return com.example.hms.i18n.TestMessageSources.bundles()
            .getMessage(refused.getMessageKey(), refused.getArgs(), locale)
            .replace(' ', ' ').replace(' ', ' ');
    }

    private BillingInvoice owing450(UUID patientId) {
        BillingInvoice invoice = payableInvoice(patientId);
        invoice.setTotalAmount(new BigDecimal("450.00"));
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        return invoice;
    }

    /** Re-test call: PATIENT_A3 POSTs 999 999 by mobile money on an invoice whose balance is 450. */
    @Test
    void qaD828_patientOverpaymentRefusalWritesFCfa() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = owing450(patientId);
        UUID invoiceId = invoice.getId();
        var tooMuch = com.example.hms.payload.dto.portal.PatientPaymentRequestDTO.builder()
            .amount(new BigDecimal("999999")).paymentMethod("MOBILE_MONEY")
            .transactionReference("OM-QA-828").build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.recordPatientPayment(invoiceId, patientId, UUID.randomUUID(), tooMuch,
                Locale.FRENCH));

        assertEquals("billing.payment.exceedsBalance", refused.getMessageKey());
        assertEquals("Le montant du paiement (999 999 F CFA) dépasse le solde dû (450 F CFA).",
            rendered(refused, Locale.FRENCH));
        assertEquals("El importe del pago (999.999 F CFA) supera el saldo pendiente (450 F CFA).",
            rendered(refused, Locale.forLanguageTag("es")));
        assertEquals("Payment amount (999,999 F CFA) exceeds balance due (450 F CFA).",
            rendered(refused, Locale.ENGLISH));
        verify(paymentTransactionRepository, never()).save(any());
    }

    /** Re-test call: 100 more on an invoice whose whole 450 balance is already declared. */
    @Test
    void qaD828_fullyDeclaredRefusalWritesFCfa() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = owing450(patientId);
        UUID invoiceId = invoice.getId();
        when(paymentTransactionRepository.sumAmountByInvoiceAndStatus(invoiceId,
            com.example.hms.enums.PaymentStatus.PENDING)).thenReturn(new BigDecimal("450.00"));
        var hundred = com.example.hms.payload.dto.portal.PatientPaymentRequestDTO.builder()
            .amount(new BigDecimal("100")).paymentMethod("MOBILE_MONEY")
            .transactionReference("OM-QA-828-B").build();

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.recordPatientPayment(invoiceId, patientId, UUID.randomUUID(), hundred,
                Locale.FRENCH));

        assertEquals("billing.payment.exceedsWithPending", refused.getMessageKey());
        assertEquals("Vous avez saisi 100 F CFA, mais 450 F CFA déjà déclarés sont en attente de confirmation : "
            + "vous pouvez déclarer au plus 0 F CFA de plus.", rendered(refused, Locale.FRENCH));
        assertEquals("Ha introducido 100 F CFA, pero 450 F CFA ya declarados están pendientes de confirmación: "
            + "puede declarar como máximo 0 F CFA más.", rendered(refused, Locale.forLanguageTag("es")));
        assertEquals("You entered 100 F CFA, but 450 F CFA you already declared is awaiting confirmation: "
            + "you can declare at most 0 F CFA more.", rendered(refused, Locale.ENGLISH));
    }

    /** Sibling: the cashier taking the whole balance beside the patient's pending declaration. */
    @Test
    void qaD828_staffRefusalBesideAPendingDeclarationWritesFCfa() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = owing450(patientId);
        UUID invoiceId = invoice.getId();
        when(paymentTransactionRepository.sumAmountByInvoiceAndStatus(invoiceId,
            com.example.hms.enums.PaymentStatus.PENDING)).thenReturn(new BigDecimal("150.00"));
        var whole = new com.example.hms.payload.dto.StaffPaymentRequestDTO(new BigDecimal("450"), "cash", null, null);

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.recordStaffPayment(invoiceId, whole, null, Locale.FRENCH));

        assertEquals("billing.payment.staffExceedsWithPending", refused.getMessageKey());
        assertEquals("Vous avez saisi 450 F CFA, mais le patient a déclaré 150 F CFA en attente de confirmation : "
            + "confirmez ou rejetez d’abord cette déclaration, ou encaissez au plus 300 F CFA.",
            rendered(refused, Locale.FRENCH));
    }

    /** Sibling: the cashier's plain overpayment (no declaration pending) on a 7 450 balance. */
    @Test
    void qaD828_staffOverpaymentRefusalWritesFCfa() {
        UUID patientId = UUID.randomUUID();
        BillingInvoice invoice = owing450(patientId);
        invoice.setTotalAmount(new BigDecimal("7450.00"));
        UUID invoiceId = invoice.getId();
        var tooMuch = new com.example.hms.payload.dto.StaffPaymentRequestDTO(new BigDecimal("12000"), "cash", null, null);

        com.example.hms.exception.BusinessException refused = assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> billingInvoiceService.recordStaffPayment(invoiceId, tooMuch, null, Locale.FRENCH));

        assertEquals("Le montant du paiement (12 000 F CFA) dépasse le solde dû (7 450 F CFA).",
            rendered(refused, Locale.FRENCH));
    }
}
