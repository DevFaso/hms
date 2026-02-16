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

    private BillingInvoiceServiceImpl billingInvoiceService;
    private BillingInvoiceMapper invoiceMapper;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        invoiceMapper = new BillingInvoiceMapper();
        billingInvoiceService = new BillingInvoiceServiceImpl(
            invoiceRepository,
            patientRepository,
            hospitalRepository,
            encounterRepository,
            invoiceItemRepository,
            pdfInvoiceService,
            invoiceMapper
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        LocaleContextHolder.resetLocaleContext();
        mocks.close();
    }

    @Test
    void createInvoice_assignsDefaultsWhenMissingValues() {
        BillingInvoiceRequestDTO request = BillingInvoiceRequestDTO.builder()
            .patientEmail("patient@example.com")
            .hospitalName("General Hospital")
            .invoiceNumber("INV-1001")
            .invoiceDate(LocalDate.of(2024, 1, 10))
            .dueDate(LocalDate.of(2024, 2, 10))
            .build();

        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setEmail("patient@example.com");

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("General Hospital");

        when(patientRepository.findByUsernameOrEmail("patient@example.com"))
            .thenReturn(Optional.of(patient));
        when(hospitalRepository.findByNameIgnoreCase("General Hospital"))
            .thenReturn(Optional.of(hospital));
        when(invoiceRepository.saveAndFlush(any(BillingInvoice.class)))
            .thenAnswer(invocation -> {
                BillingInvoice invoice = invocation.getArgument(0);
                invoice.setId(UUID.randomUUID());
                return invoice;
            });

        BillingInvoiceResponseDTO response = billingInvoiceService.createInvoice(request, Locale.ENGLISH);

        assertNotNull(response);
        assertEquals("INV-1001", response.getInvoiceNumber());

        ArgumentCaptor<BillingInvoice> invoiceCaptor = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceRepository).saveAndFlush(invoiceCaptor.capture());
        BillingInvoice persisted = invoiceCaptor.getValue();

        assertEquals(BigDecimal.ZERO, persisted.getTotalAmount());
        assertEquals(BigDecimal.ZERO, persisted.getAmountPaid());
        assertEquals(InvoiceStatus.DRAFT, persisted.getStatus());
    }

    @Test
    void getInvoicePdf_defaultsLocaleWhenNull() {
        UUID invoiceId = UUID.randomUUID();
        BillingInvoice invoice = new BillingInvoice();
        invoice.setId(invoiceId);
        invoice.setInvoiceNumber("INV-2024-01");

        when(invoiceRepository.findByIdWithRefs(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceItemRepository.findByBillingInvoiceId(invoiceId)).thenReturn(Collections.emptyList());
        when(pdfInvoiceService.generateInvoicePdf(any(), any(), any())).thenReturn(new byte[]{1, 2, 3});

        LocaleContextHolder.setLocale(Locale.CANADA_FRENCH);

        InvoicePdfResponseDTO result = billingInvoiceService.getInvoicePdf(invoiceId, null);

        assertNotNull(result);
        assertEquals("INV-2024-01", result.invoiceNumber());

        ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);
        verify(pdfInvoiceService).generateInvoicePdf(eq(invoice), eq(Collections.emptyList()), localeCaptor.capture());
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
            eq(pageable)
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
            eq(pageable)
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
}
