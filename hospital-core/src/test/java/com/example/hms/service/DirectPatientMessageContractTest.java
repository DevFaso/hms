package com.example.hms.service;

import com.example.hms.enums.*;
import com.example.hms.model.*;
import com.example.hms.model.scheduling.AppointmentSlot;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.payload.dto.EmailInvoiceRequest;
import com.example.hms.repository.*;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import com.example.hms.service.scheduling.*;
import com.example.hms.testsupport.PatientMessageContractFixture;
import com.example.hms.testsupport.PatientMessageContractFixture.Refusal;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.DisplayDates;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DirectPatientMessageContractTest {
    static Stream<Arguments> cases() {
        return Stream.of(PatientNotificationEvent.WAITLIST_OFFER,PatientNotificationEvent.RECALL_DUE,
            PatientNotificationEvent.VISIT_SUMMARY,PatientNotificationEvent.INVOICE)
            .flatMap(event -> Stream.of("default","fr","en","es").flatMap(language -> Stream.of(Refusal.values()).map(refusal -> Arguments.of(event,language,refusal))));
    }

    @ParameterizedTest @MethodSource("cases")
    void outreachSummaryAndInvoiceCallersUseTheRealGatesAndTransports(PatientNotificationEvent event,String language,Refusal refusal) {
        NotificationChannel channel=(event==PatientNotificationEvent.WAITLIST_OFFER || event==PatientNotificationEvent.RECALL_DUE)?NotificationChannel.SMS:NotificationChannel.EMAIL;
        var f=new PatientMessageContractFixture(event,channel,language,refusal);
        var outreach=new PatientOutreachNotifier(mock(NotificationService.class),f.gate,f.messages);
        Runnable send;
        LocalDateTime slotAt=LocalDateTime.of(2026,11,25,10,0),expiresAt=slotAt.minusHours(1);
        switch(event) {
            case WAITLIST_OFFER -> {
                var sender=f.realSender(ReceptionServiceImpl.class,Map.of("outreachNotifier",outreach,"patientLocaleResolver",f.locales,"outreachLocale","fr"));
                AppointmentWaitlist entry=new AppointmentWaitlist();entry.setId(f.subjectId);entry.setPatient(f.patient);entry.setHospital(f.hospital);
                AppointmentSlot slot=new AppointmentSlot();slot.setStartAt(slotAt);slot.setEndAt(slotAt.plusMinutes(30));
                send=()->ReflectionTestUtils.invokeMethod(sender,"notifyOffer",entry,slot,expiresAt);
            }
            case RECALL_DUE -> {
                var sender=new RecallReminderService(mock(PatientRecallRepository.class),outreach,f.locales,f.clock);
                ReflectionTestUtils.setField(sender,"outreachLocale","fr");
                PatientRecall recall=new PatientRecall();recall.setId(f.subjectId);recall.setPatient(f.patient);recall.setHospital(f.hospital);
                send=()->ReflectionTestUtils.invokeMethod(sender,"notifyRecall",recall);
            }
            case VISIT_SUMMARY -> {
                var sender=f.realSender(EncounterServiceImpl.class,Map.of("patientMessageGate",f.gate,"patientLocaleResolver",f.locales,
                    "messageSource",f.messages,"notificationService",mock(NotificationService.class)));
                Encounter encounter=new Encounter();encounter.setId(f.subjectId);encounter.setPatient(f.patient);encounter.setHospital(f.hospital);
                send=()->ReflectionTestUtils.invokeMethod(sender,"notifyPatientAfterVisitSummary",encounter);
            }
            case INVOICE -> {
                var invoices=mock(BillingInvoiceRepository.class);var items=mock(InvoiceItemRepository.class);var roles=mock(RoleValidator.class);
                BillingInvoice invoice=new BillingInvoice();invoice.setId(f.subjectId);invoice.setHospital(f.hospital);invoice.setPatient(f.patient);
                invoice.setInvoiceNumber("cdx-INV-1");invoice.setStatus(InvoiceStatus.SENT);invoice.setInvoiceDate(slotAt.toLocalDate());invoice.setDueDate(slotAt.plusDays(1).toLocalDate());
                invoice.setTotalAmount(new BigDecimal("1250"));invoice.setAmountPaid(BigDecimal.ZERO);
                when(invoices.findByIdWithRefs(f.subjectId)).thenReturn(Optional.of(invoice));
                when(roles.requireActiveHospitalId()).thenReturn(f.hospital.getId());
                var sender=new InvoiceEmailService(invoices,items,new PdfInvoiceService(f.messages),f.gate,f.locales,f.messages,roles,mock(InvoiceAdjustmentRepository.class));
                var request=new EmailInvoiceRequest(List.of(f.patient.getEmail()),List.of(),List.of(),null,"de",false);
                send=()->sender.emailInvoice(f.subjectId,request);
            }
            default -> throw new AssertionError("Missing real sender contract: "+event);
        }
        if(event==PatientNotificationEvent.INVOICE && refusal!=Refusal.NONE)assertThatThrownBy(send::run).isInstanceOf(com.example.hms.exception.BusinessException.class);
        else send.run();
        f.assertDirectNotice(event,channel);
        String key=PatientNotificationDelivery.keyOf(event,f.subjectId,channel);
        if(refusal==Refusal.NONE) {
            assertThat(f.wire).hasSize(1);var delivered=f.wire.getFirst();
            assertThat(delivered.recipient()).isEqualTo(channel==NotificationChannel.SMS?"+22670000000":"cdx-patient@example.invalid");
            switch(event) {
                case WAITLIST_OFFER -> assertThat(delivered.body()).isEqualTo(f.messages.getMessage("sms.waitlist.offer",new Object[]{DisplayDates.date(slotAt.toLocalDate(),f.expectedLocale),DisplayDates.time(slotAt.toLocalTime(),f.expectedLocale),f.hospital.getName(),DisplayDates.dateTime(expiresAt,f.expectedLocale)},f.expectedLocale));
                case RECALL_DUE -> assertThat(delivered.body()).isEqualTo(f.messages.getMessage("sms.recall.due",new Object[]{f.hospital.getName()},f.expectedLocale));
                case VISIT_SUMMARY -> assertThat(delivered.subject()).isEqualTo(f.messages.getMessage("encounter.visitSummary.email.subject",null,f.expectedLocale));
                case INVOICE -> {
                    assertThat(delivered.subject()).isEqualTo(f.messages.getMessage("email.invoice.subject",new Object[]{"cdx-INV-1",f.hospital.getName()},f.expectedLocale));
                    // The sender contract checks value, unit and language. Exact locale grouping is covered by PDF/e-mail formatter tests.
                    assertThat(delivered.body()).contains(DisplayDates.date(slotAt.toLocalDate(),f.expectedLocale));
                    assertThat(delivered.body()).containsPattern("(?<![0-9])1[ ,.\\u00a0\\u202f]250 F CFA");
                }
                default -> throw new AssertionError(event);
            }
        } else {
            if(event==PatientNotificationEvent.INVOICE)assertThatThrownBy(send::run).isInstanceOf(com.example.hms.exception.BusinessException.class);
            else send.run();
            assertThat(f.wire).isEmpty();assertThat(f.rows).containsOnlyKeys(key);
            assertThat(f.rows.get(key).getErrorCode()).isEqualTo(refusal==Refusal.OPT_OUT?"OPTED_OUT":"RECIPIENT_UNVERIFIED");
            assertThat(f.rows.get(key).getPatientId()).isEqualTo(f.patient.getId());
            assertThat(f.rows.get(key).getHospitalId()).isEqualTo(f.hospital.getId());
        }
    }
}
