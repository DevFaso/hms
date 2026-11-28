package com.example.hms.service;

import com.example.hms.service.notification.PatientChannelConsent.Decision;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.service.i18n.PatientLocaleResolver;
import com.example.hms.service.notification.PatientChannelConsent;
import com.example.hms.service.notification.PatientMessageGate;
import com.example.hms.service.notification.PatientNotificationLedger;
import com.example.hms.payload.dto.EmailInvoiceRequest;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.example.hms.testsupport.RandomIds.withoutRandomIds;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** QA D755 / D758: an invoice is mailed only from its own hospital, and mailing never issues it. */
@ExtendWith(MockitoExtension.class)
class InvoiceEmailServiceTest {

    @Mock private BillingInvoiceRepository billingInvoiceRepository;
    @Mock private InvoiceItemRepository invoiceItemRepository;
    @Mock private PdfInvoiceService pdfInvoiceService;
    @Mock private EmailService emailService;
    @Mock private RoleValidator roleValidator;
    @Mock private com.example.hms.repository.InvoiceAdjustmentRepository adjustmentRepository;
    @Mock private SmsService smsService;
    @Mock private PatientChannelConsent consent;
    @Mock private PatientNotificationLedger ledger;
    @Mock private PatientLocaleResolver patientLocaleResolver;
    @Mock private com.example.hms.repository.PatientGuarantorRepository guarantorRepository;
    @Mock private com.example.hms.service.notification.PatientRecipientCurrentCheck currentCheck;
    private InvoiceEmailService service;
    /** The guarantor on the patient's file at hospital A, whose address is billing@example.test. */
    private final UUID guarantorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // The real gate over a mocked consent: the opt-out path is the gate's own.
        service = new InvoiceEmailService(billingInvoiceRepository, invoiceItemRepository, pdfInvoiceService,
            new PatientMessageGate(consent, ledger, smsService, emailService, new com.example.hms.service.notification.PatientRecipientVerification(), currentCheck), patientLocaleResolver,
            TestMessageSources.bundles(), roleValidator, adjustmentRepository,
            new com.example.hms.service.billing.InvoiceMailRecipients(guarantorRepository));
        // The send states its trace on the request (WriteAuditDetail), for the request's own audit row.
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
            new org.springframework.web.context.request.ServletRequestAttributes(
                new org.springframework.mock.web.MockHttpServletRequest()));
        com.example.hms.model.PatientGuarantor guarantor = com.example.hms.model.PatientGuarantor.builder()
            .fullName("Issa Ouédraogo").email("Billing@Example.test").build();
        guarantor.setId(guarantorId);
        lenient().when(currentCheck.isVerified(any(), any(), any())).thenReturn(true);
        lenient().when(guarantorRepository.findByPatient_IdAndHospital_IdAndActiveTrue(any(), eq(hospitalA)))
            .thenReturn(List.of(guarantor));
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(consent.decision(any(), any(), any()))
            .thenAnswer(call -> new Decision(true, call.getArgument(0), null, null));
    }

    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();

    private final EmailInvoiceRequest request =
        new EmailInvoiceRequest(List.of("billing@example.test"), null, null, "<b>hi</b>", "fr", false);

    private BillingInvoice invoiceAtA(InvoiceStatus status) {
        BillingInvoice invoice = new BillingInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setInvoiceNumber("INV-1");
        invoice.setStatus(status);
        Hospital h = new Hospital();
        h.setId(hospitalA);
        h.setName("Centre Hospitalier Wend-Panga");
        invoice.setHospital(h);
        invoice.setInvoiceDate(java.time.LocalDate.of(2026, 11, 18));
        invoice.setDueDate(java.time.LocalDate.of(2026, 12, 7));
        invoice.setTotalAmount(new java.math.BigDecimal("4300.00"));
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setEmail("seydou@example.test");
        patient.setVerifiedEmailRecipient("seydou@example.test");
        User user = new User();
        user.setId(UUID.randomUUID());
        patient.setUser(user);
        invoice.setPatient(patient);
        when(billingInvoiceRepository.findByIdWithRefs(invoice.getId())).thenReturn(Optional.of(invoice));
        return invoice;
    }

    @Test
    @DisplayName("QA D755: billing staff of hospital B cannot mail hospital A's invoice (404, nothing sent)")
    void otherHospitalsInvoiceIsNotFound() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB);
        UUID id = invoice.getId();

        assertThatThrownBy(() -> service.emailInvoice(id, request))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(emailService, pdfInvoiceService);
    }

    @Test
    @DisplayName("QA D755: a random id is a 404, not a 500")
    void unknownIdIsNotFound() {
        UUID id = UUID.randomUUID();
        when(billingInvoiceRepository.findByIdWithRefs(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.emailInvoice(id, request)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("QA D755: the invoice's own hospital still mails it, the custom message escaped")
    void ownHospitalMailsIt() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(invoiceItemRepository.findByBillingInvoiceId(invoice.getId())).thenReturn(List.of());

        service.emailInvoice(invoice.getId(), request);

        verify(emailService).sendWithAttachment(any(), any(), any(), anyString(),
            contains("&lt;b&gt;hi&lt;/b&gt;"), any(), anyString(), anyString());
        assertEquals(InvoiceStatus.SENT, invoice.getStatus());
    }

    @Test
    @DisplayName("QA D758: a DRAFT is refused and stays DRAFT")
    void draftIsRefusedAndKept() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.DRAFT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        UUID id = invoice.getId();

        assertThatThrownBy(() -> service.emailInvoice(id, request))
            .isInstanceOf(BusinessException.class);
        assertEquals(InvoiceStatus.DRAFT, invoice.getStatus());
        verify(billingInvoiceRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    void emailingAPaidInvoice_keepsItPaid() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.PAID);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(invoiceItemRepository.findByBillingInvoiceId(invoice.getId())).thenReturn(List.of());

        service.emailInvoice(invoice.getId(), request);

        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
        verify(billingInvoiceRepository, never()).save(any());
    }

    @Test
    void theMailedPdfCarriesTheAdjustmentsWithoutTheReason_D974() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(invoiceItemRepository.findByBillingInvoiceId(invoice.getId())).thenReturn(List.of());
        when(adjustmentRepository.findByInvoice_IdAndReversedAtIsNullOrderByCreatedAtAsc(invoice.getId())).thenReturn(List.of(
            com.example.hms.model.InvoiceAdjustment.builder().invoice(invoice)
                .type(com.example.hms.enums.InvoiceAdjustmentType.WAIVER)
                .amount(new java.math.BigDecimal("500")).reason("indigent").build()));
        when(pdfInvoiceService.generateMailedInvoicePdf(any(), any(), any(), any(), any())).thenReturn(new byte[] {1});

        service.emailInvoice(invoice.getId(),
            new EmailInvoiceRequest(List.of("billing@example.test"), null, null, null, "fr", true));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<com.example.hms.model.InvoiceAdjustment>> printed =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(pdfInvoiceService).generateMailedInvoicePdf(any(), any(), printed.capture(), any(), any());
        assertEquals(1, printed.getValue().size());
        assertEquals(com.example.hms.enums.InvoiceAdjustmentType.WAIVER, printed.getValue().get(0).getType());
        org.junit.jupiter.api.Assertions.assertNull(printed.getValue().get(0).getReason());
    }

    // ---------- QA D2200: the invoice e-mail in the patient's language ----------

    private String[] mailed() {
        org.mockito.ArgumentCaptor<String> subject = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailService).sendWithAttachment(any(), any(), any(), subject.capture(), body.capture(), any(),
            anyString(), anyString());
        return new String[] {subject.getValue(), body.getValue()};
    }

    @Test
    @DisplayName("D2200: a patient with no stated language gets the mail in French, dates and F CFA as the PDF")
    void frenchByDefault() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);

        service.emailInvoice(invoice.getId(), request);

        String[] mail = mailed();
        assertThat(mail[0]).isEqualTo("Facture INV-1 - Centre Hospitalier Wend-Panga");
        assertThat(mail[1]).contains("lang=\"fr\"", "<h2>Facture INV-1</h2>", "Date : 18 nov. 2026",
                "Échéance : 7 déc. 2026", "Total : 4 300 F CFA", "Reste à payer : <strong>4 300 F CFA</strong>",
                "Merci,");
        assertThat(withoutRandomIds(mail[1]))
            .doesNotContain("Invoice", "2026-11-18", "4300", "Thank you", "Your Hospital");
    }

    @Test
    @DisplayName("D2200: the patient's language wins over the clerk's, for the subject, the body and the PDF")
    void thePatientsLanguageNotTheClerks() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(patientLocaleResolver.resolve(any(), any())).thenReturn(java.util.Locale.ENGLISH);
        when(pdfInvoiceService.generateMailedInvoicePdf(any(), any(), any(), any(), any())).thenReturn(new byte[] {1});

        service.emailInvoice(invoice.getId(),
            new EmailInvoiceRequest(List.of("billing@example.test"), null, null, null, "fr", true));

        String[] mail = mailed();
        assertThat(mail[0]).isEqualTo("Invoice INV-1 - Centre Hospitalier Wend-Panga");
        assertThat(mail[1]).contains("lang=\"en\"", "Due: Dec 7, 2026", "Balance due: <strong>4,300 F CFA</strong>")
            .doesNotContain("Échéance");
        verify(pdfInvoiceService).generateMailedInvoicePdf(any(), any(), any(), any(), eq(java.util.Locale.ENGLISH));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "fr|4 300 F CFA", "en|4,300 F CFA", "es|4.300 F CFA"
    })
    void patientAmountGroupingMatchesThePatientsLanguage(String language, String expected) {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(patientLocaleResolver.resolve(any(), any())).thenReturn(java.util.Locale.forLanguageTag(language));
        service.emailInvoice(invoice.getId(), new EmailInvoiceRequest(List.of("billing@example.test"), null, null, null, "fr", false));
        String[] mail = mailed();
        assertThat(mail[1]).contains(expected);
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("4300.00");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.SENT);
    }

    // ---------- QA D2201 class: the patient's BILLING e-mail choice ----------

    @Test
    @DisplayName("D2201: mailing the invoice to a patient who turned billing e-mails off is refused and traced")
    void optedOutPatientIsNotMailed() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(consent.decision(invoice.getPatient().getUser().getId(), NotificationType.BILLING,
            NotificationChannel.EMAIL))
            .thenAnswer(call -> new Decision(false, call.getArgument(0), null, null));
        UUID id = invoice.getId();
        EmailInvoiceRequest toPatient = new EmailInvoiceRequest(List.of(" Seydou@Example.test "),
            List.of("billing@example.test"), null, null, null, false);

        assertThatThrownBy(() -> service.emailInvoice(id, toPatient))
            .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("billing.invoice.emailOptedOut");
        verify(emailService, never()).sendWithAttachment(any(), any(), any(), any(), any(), any(), any(), any());
        verify(ledger).recordOptOut(eq(PatientNotificationEvent.INVOICE),
            eq(hospitalA),
            eq(invoice.getPatient().getId()),
            eq(id),
            eq(NotificationChannel.EMAIL),
            any(Decision.class));
    }

    @Test
    @DisplayName("D2201: an invoice mailed only to another payer is not a notice to the patient")
    void thirdPartyPayerStillMailed() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        lenient().when(consent.decision(any(), eq(NotificationType.BILLING), eq(NotificationChannel.EMAIL)))
            .thenAnswer(call -> new Decision(false, call.getArgument(0), null, null));

        service.emailInvoice(invoice.getId(), request);

        mailed();
        verifyNoInteractions(ledger);
    }

    // ---------- Scorecard K4: who an invoice is mailed to, and the trace of it ----------

    @org.junit.jupiter.api.AfterEach
    void k4bClearRequest() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    /** What the send stated for the request's audit row, or null when it stated nothing. */
    private com.example.hms.security.audit.WriteAuditDetail.Detail k4bDetail() {
        Object stated = org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()
            .getAttribute(com.example.hms.security.audit.WriteAuditDetail.class.getName(),
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return (com.example.hms.security.audit.WriteAuditDetail.Detail) stated;
    }

    @SuppressWarnings("unchecked")
    private List<java.util.Map<String, Object>> k4bRecipients() {
        return (List<java.util.Map<String, Object>>) k4bDetail().details().get("invoiceEmailRecipients");
    }

    @Test
    @DisplayName("K4: an address that is on no file is refused without a reason: nothing is composed, sent or traced")
    void anAddressOnNoFileNeedsAReason() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        UUID id = invoice.getId();
        EmailInvoiceRequest toAStranger = new EmailInvoiceRequest(List.of("seydou@example.test"),
            List.of("stranger@employer.test"), null, null, null, true);

        assertThatThrownBy(() -> service.emailInvoice(id, toAStranger))
            .isInstanceOf(BusinessException.class)
            .satisfies(refusal -> {
                BusinessException e = (BusinessException) refusal;
                assertThat(e.getMessageKey()).isEqualTo("invoiceMail.recipient.notOnFile");
                // The refusal names the address masked, never whole.
                assertThat(e.getArgs()).containsExactly("s***@employer.test");
            });
        verifyNoInteractions(emailService, pdfInvoiceService);
        assertThat(k4bDetail()).isNull();
    }

    @Test
    @DisplayName("K4: a guarantor on another hospital's file is not on this invoice's file")
    void aGuarantorOfAnotherHospitalIsNotOnFile() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(guarantorRepository.findByPatient_IdAndHospital_IdAndActiveTrue(any(), eq(hospitalA)))
            .thenReturn(List.of());
        UUID id = invoice.getId();

        assertThatThrownBy(() -> service.emailInvoice(id, request))
            .isInstanceOf(BusinessException.class).extracting("messageKey")
            .isEqualTo("invoiceMail.recipient.notOnFile");
        verifyNoInteractions(emailService);
        assertThat(k4bDetail()).isNull();
    }

    @Test
    @DisplayName("K4: the patient's verified address and a guarantor on file are mailed, each traced masked, ids only")
    // The trace is the detail of the request's own audit row: see WriteAuditInterceptorTest for the row.
    void recipientsOnFileAreMailedAndTraced() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);

        service.emailInvoice(invoice.getId(), new EmailInvoiceRequest(List.of(" Seydou@Example.test "),
            List.of("billing@example.test"), List.of("seydou@example.test"), "note du guichet", null, false));

        mailed();
        com.example.hms.security.audit.WriteAuditDetail.Detail trace = k4bDetail();
        assertThat(trace.eventType()).isEqualTo(com.example.hms.enums.AuditEventType.DATA_EXPORT);
        assertThat(trace.resourceId()).isEqualTo(invoice.getId());
        assertThat(trace.patientId()).isEqualTo(invoice.getPatient().getId());
        // Never an address, a name, the subject or the clerk's note.
        assertThat(String.valueOf(trace.details()))
            .doesNotContainIgnoringCase("seydou@", "billing@", "Ouédraogo", "INV-1", "note du guichet");
        assertThat(trace.details()).containsEntry("attachment", false).doesNotContainKey("reason");
        // The same address typed twice is one recipient.
        assertThat(k4bRecipients()).hasSize(2);
        assertThat(k4bRecipients().get(0)).containsEntry("recipient", "S***@Example.test")
            .containsEntry("recipientKind", "PATIENT").doesNotContainKey("guarantorId");
        assertThat(k4bRecipients().get(1)).containsEntry("recipient", "b***@example.test")
            .containsEntry("recipientKind", "GUARANTOR").containsEntry("guarantorId", guarantorId.toString());
    }

    @Test
    @DisplayName("K4: another address is mailed only with a reason, and the reason is kept with its trace")
    void anotherAddressIsMailedWithARecordedReason() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);

        service.emailInvoice(invoice.getId(), new EmailInvoiceRequest(List.of("rh@employer.test"), null, null,
            null, null, false, "  Employeur du patient, à sa demande  "));

        mailed();
        assertThat(k4bRecipients()).singleElement().satisfies(one -> assertThat(one)
            .containsEntry("recipient", "r***@employer.test").containsEntry("recipientKind", "OTHER"));
        assertThat(k4bDetail().details()).containsEntry("reason", "Employeur du patient, à sa demande");
    }

    @Test
    @DisplayName("K4: one send reaches three addresses at most, all lists together, even with a reason")
    void moreThanThreeAddressesAreRefused() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        UUID id = invoice.getId();
        EmailInvoiceRequest four = new EmailInvoiceRequest(List.of("seydou@example.test", "a@x.test"),
            List.of("billing@example.test"), List.of("b@x.test"), null, null, false, "demandé");

        assertThatThrownBy(() -> service.emailInvoice(id, four))
            .isInstanceOf(BusinessException.class).extracting("messageKey")
            .isEqualTo("invoiceMail.recipient.tooMany");
        verifyNoInteractions(emailService);
        assertThat(k4bDetail()).isNull();
        assertThat(EmailInvoiceRequest.MAX_RECIPIENTS).isEqualTo(3);
    }

    @Test
    @DisplayName("K4: a send the patient refused leaves no recipient trace (nothing left the hospital)")
    void aRefusedSendIsNotTracedAsSent() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(consent.decision(any(), eq(NotificationType.BILLING), eq(NotificationChannel.EMAIL)))
            .thenAnswer(call -> new Decision(false, call.getArgument(0), null, null));
        UUID id = invoice.getId();
        EmailInvoiceRequest toPatient = new EmailInvoiceRequest(List.of("seydou@example.test"), null, null, null, null, false);

        assertThatThrownBy(() -> service.emailInvoice(id, toPatient)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(emailService);
        assertThat(k4bDetail()).isNull();
    }

    // ---------- QA D4222 / D4223 (round 81): the refusal says why, and the dialog knows before it sends ----------

    @Test
    @DisplayName("QA D4222: an unverified patient address is refused as unverified, not as « e-mails turned off »")
    void qaD4222_anUnverifiedPatientAddressIsRefusedAsUnverified() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        invoice.getPatient().setVerifiedEmailRecipient(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        UUID id = invoice.getId();
        EmailInvoiceRequest toPatient = new EmailInvoiceRequest(List.of("seydou@example.test"), null, null, null,
            null, false);

        assertThatThrownBy(() -> service.emailInvoice(id, toPatient))
            .isInstanceOf(BusinessException.class).extracting("messageKey")
            .isEqualTo("billing.invoice.emailUnverified");
        verify(emailService, never()).sendWithAttachment(any(), any(), any(), any(), any(), any(), any(), any());
        // The trace row and the clerk's message carry the same reason.
        verify(ledger).recordUnverified(PatientNotificationEvent.INVOICE, hospitalA, invoice.getPatient().getId(), id,
            NotificationChannel.EMAIL);
        verify(ledger, never()).recordOptOut(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("QA D4222: the dialog is told the patient's address would be refused, and why, with no trace written")
    void qaD4222_theDialogKnowsAnUnverifiedAddressBeforeSending() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        invoice.getPatient().setVerifiedEmailRecipient(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);

        var options = service.recipientOptions(invoice.getId());

        assertThat(options.patient().address()).isEqualTo("seydou@example.test");
        assertThat(options.patient().usable()).isFalse();
        assertThat(options.patient().refusalCode()).isEqualTo("RECIPIENT_UNVERIFIED");
        verifyNoInteractions(emailService, ledger);
    }

    @Test
    @DisplayName("QA D4223: the dialog is offered the verified patient address and the active guarantors on file")
    void qaD4223_theDialogIsOfferedTheAddressesOnFile() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);

        var options = service.recipientOptions(invoice.getId());

        assertThat(options.patient().usable()).isTrue();
        assertThat(options.patient().refusalCode()).isNull();
        assertThat(options.guarantors()).singleElement().satisfies(guarantor -> {
            assertThat(guarantor.guarantorId()).isEqualTo(guarantorId);
            assertThat(guarantor.fullName()).isEqualTo("Issa Ouédraogo");
            assertThat(guarantor.address()).isEqualTo("Billing@Example.test");
        });
        assertThat(options.maxRecipients()).isEqualTo(EmailInvoiceRequest.MAX_RECIPIENTS);
        assertThat(options.reasonMaxLength()).isEqualTo(500);
        verifyNoInteractions(emailService, ledger);
    }

    @Test
    @DisplayName("QA D4223: a patient who turned billing e-mails off is offered as refused for that reason")
    void qaD4223_anOptedOutPatientIsOfferedAsRefused() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(consent.decision(invoice.getPatient().getUser().getId(), NotificationType.BILLING,
            NotificationChannel.EMAIL))
            .thenAnswer(call -> new Decision(false, call.getArgument(0), null, null));

        var options = service.recipientOptions(invoice.getId());

        assertThat(options.patient().usable()).isFalse();
        assertThat(options.patient().refusalCode()).isEqualTo("OPTED_OUT");
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("QA D4223: another hospital's invoice offers nothing (404)")
    void qaD4223_anotherHospitalsInvoiceOffersNothing() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB);
        UUID id = invoice.getId();

        assertThatThrownBy(() -> service.recipientOptions(id)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(guarantorRepository);
    }
}
