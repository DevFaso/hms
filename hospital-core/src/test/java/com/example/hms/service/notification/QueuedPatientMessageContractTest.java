package com.example.hms.service.notification;

import com.example.hms.enums.*;
import com.example.hms.model.*;
import com.example.hms.repository.*;
import com.example.hms.testsupport.PatientMessageContractFixture;
import com.example.hms.testsupport.PatientMessageContractFixture.Refusal;
import com.example.hms.utility.DisplayDates;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Ledger -> actual subject renderer -> actual dispatch -> fake SMS/SMTP. */
class QueuedPatientMessageContractTest {
    static Stream<Arguments> cases() {
        return PatientMessageContractFixture.coveredEvents().stream().filter(PatientNotificationEvent::isQueued)
            .flatMap(event -> (event.sendsEmail()?Stream.of(NotificationChannel.SMS,NotificationChannel.EMAIL):Stream.of(NotificationChannel.SMS))
                .flatMap(channel -> Stream.of("default","fr","en","es").flatMap(language -> Stream.of(Refusal.values())
                    .map(refusal -> Arguments.of(event,channel,language,refusal)))));
    }

    @ParameterizedTest @MethodSource("cases")
    void releasedResultsAndAppointmentNoticesUseTheRealPipeline(PatientNotificationEvent event,NotificationChannel channel,String language,Refusal refusal) {
        var f=new PatientMessageContractFixture(event,channel,language,refusal);
        var hospitals=mock(HospitalRepository.class);when(hospitals.findById(f.hospital.getId())).thenReturn(Optional.of(f.hospital));
        var appointments=mock(AppointmentRepository.class);
        Appointment appointment=new Appointment();appointment.setId(f.subjectId);appointment.setPatient(f.patient);appointment.setHospital(f.hospital);
        appointment.setAppointmentDate(LocalDate.of(2026,11,25));appointment.setStartTime(LocalTime.of(10,0));appointment.setEndTime(LocalTime.of(10,30));appointment.setStatus(AppointmentStatus.CONFIRMED);
        when(appointments.findById(f.subjectId)).thenReturn(Optional.of(appointment));
        var orders=mock(LabOrderRepository.class);LabOrder order=new LabOrder();order.setId(f.subjectId);order.setStatus(LabOrderStatus.COMPLETED);
        when(orders.findById(f.subjectId)).thenReturn(Optional.of(order));
        var results=mock(LabResultRepository.class);LabResult result=new LabResult();result.setReleased(true);result.setResultValue("cdx PRIVATE-RESULT");
        when(results.findByLabOrder_Id(f.subjectId)).thenReturn(List.of(result));
        var images=mock(ImagingReportRepository.class);ImagingReport image=new ImagingReport();image.setSignedAt(LocalDateTime.now(f.clock));image.setReportStatus(ImagingReportStatus.FINAL);
        when(images.findByImagingOrder_IdOrderByReportVersionDesc(f.subjectId)).thenReturn(List.of(image));
        var ultrasounds=mock(UltrasoundReportRepository.class);UltrasoundReport ultrasound=new UltrasoundReport();ultrasound.setReportReviewedByProvider(true);ultrasound.setPatientNotifiedAt(LocalDateTime.now(f.clock));
        when(ultrasounds.findByIdUnscoped(f.subjectId)).thenReturn(Optional.of(ultrasound));
        PatientNotificationProperties properties=new PatientNotificationProperties();
        var renderer=new PatientNotificationRenderer(f.deliveries,f.patients,hospitals,appointments,orders,results,images,ultrasounds,
            f.consent,f.locales,f.messages,f.clock,properties,new PatientRecipientVerification());
        when(f.deliveries.findDispatchableIds(anyCollection(),anyInt(),any(),any())).thenAnswer(call -> f.rows.values().stream()
            .filter(row -> row.getStatus().isOpen() && !row.getNextAttemptAt().isAfter(LocalDateTime.now(f.clock))).map(PatientNotificationDelivery::getId).toList());
        when(f.deliveries.claim(any(),anyCollection(),anyInt(),any(),any())).thenAnswer(call -> {
            var row=f.rows.values().stream().filter(r -> r.getId().equals(call.getArgument(0))).findFirst().orElseThrow();
            LocalDateTime now=call.getArgument(3);
            if(!row.getStatus().isOpen() || row.getNextAttemptAt().isAfter(now))return 0;
            row.setAttempts(row.getAttempts()+1);row.setLastAttemptAt(now);row.setNextAttemptAt(call.getArgument(4));return 1;
        });
        when(f.deliveries.markSent(any(),any(),anyCollection(),any(),any())).thenAnswer(call -> {
            var row=f.rows.values().stream().filter(r -> r.getId().equals(call.getArgument(0))).findFirst().orElseThrow();
            row.setStatus(NotificationDeliveryStatus.SENT);return 1;
        });
        var dispatcher=new PatientNotificationDispatchService(f.deliveries,renderer,f.sms,f.email,properties,f.clock,f.transactions);
        f.ledger.requestAfterCommit(event,f.hospital.getId(),f.patient.getId(),f.subjectId);
        f.ledger.requestAfterCommit(event,f.hospital.getId(),f.patient.getId(),f.subjectId);
        String key=PatientNotificationDelivery.keyOf(event,f.subjectId,channel);
        assertThat(f.rows).containsKey(key);
        assertThat(f.rows.get(key).getIdempotencyKey()).isEqualTo(event.name()+":"+f.subjectId+":"+channel.name());
        assertThat(f.rows.values()).filteredOn(row -> row.getChannel()==channel).hasSize(1);
        dispatcher.dispatchPending();int sent=f.wire.size();dispatcher.dispatchPending();assertThat(f.wire).hasSize(sent);
        var target=f.wire.stream().filter(w -> w.channel()==channel).toList();
        if(refusal==Refusal.NONE) {
            assertThat(target).hasSize(1);var delivered=target.getFirst();
            assertThat(delivered.recipient()).isEqualTo(channel==NotificationChannel.SMS?"+22670000000":"cdx-patient@example.invalid");
            assertThat(delivered.body()).doesNotContain("PRIVATE-RESULT",f.patient.getPhoneNumberSecondary());
            Object[] args=event.isResult()?new Object[]{f.hospital.getName()}:new Object[]{DisplayDates.date(appointment.getAppointmentDate(),f.expectedLocale),DisplayDates.time(appointment.getStartTime(),f.expectedLocale),f.hospital.getName()};
            String code=event.isResult()?"sms.result.available":event==PatientNotificationEvent.APPOINTMENT_CONFIRMATION?"sms.appointment.confirmation":"sms.appointment.reminder";
            if(channel==NotificationChannel.SMS)assertThat(delivered.body()).isEqualTo(f.messages.getMessage(code,args,f.expectedLocale));
            else {
                String subjectKey=event.isResult()?"email.patient.resultAvailable.subject":"email.patient.appointmentReminder.subject";
                assertThat(delivered.subject()).isEqualTo(f.messages.getMessage(subjectKey,args,f.expectedLocale));
                assertThat(delivered.body()).contains("lang=\""+f.expectedLocale.getLanguage()+"\"");
            }
            assertThat(f.rows.get(key).getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        } else {
            assertThat(target).isEmpty();assertThat(f.rows.get(key).getStatus()).isEqualTo(NotificationDeliveryStatus.SKIPPED);
            assertThat(f.rows.get(key).getErrorCode()).isEqualTo(refusal==Refusal.OPT_OUT?"OPTED_OUT":"RECIPIENT_UNVERIFIED");
        }
    }
}
