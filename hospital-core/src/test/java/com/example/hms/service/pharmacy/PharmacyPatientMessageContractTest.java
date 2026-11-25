package com.example.hms.service.pharmacy;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.model.PatientNotificationDelivery;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.notification.PatientMessageGate;
import com.example.hms.testsupport.PatientMessageContractFixture;
import com.example.hms.testsupport.PatientMessageContractFixture.Refusal;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PharmacyPatientMessageContractTest {
    static Stream<org.junit.jupiter.params.provider.Arguments> cases() {
        return PatientMessageContractFixture.coveredEvents().stream().filter(event -> event.name().startsWith("PHARMACY_"))
            .flatMap(event -> Stream.of("default","fr","en","es").flatMap(language -> Stream.of(Refusal.values())
                .map(refusal -> org.junit.jupiter.params.provider.Arguments.of(event,language,refusal))));
    }

    @ParameterizedTest @MethodSource("cases")
    void realPharmacySendersRespectRecipientLanguageConsentVerificationAndTraceKey(PatientNotificationEvent event,String language,Refusal refusal) {
        var f=new PatientMessageContractFixture(event,NotificationChannel.SMS,language,refusal);
        var sender=new PharmacyServiceSupport(mock(RoleValidator.class),mock(UserRepository.class),mock(AuditEventLogService.class),f.gate,f.messages,f.locales);
        Prescription prescription=new Prescription();prescription.setId(f.subjectId);prescription.setPatient(f.patient);prescription.setHospital(f.hospital);
        Pharmacy pharmacy=new Pharmacy();pharmacy.setName("cdx Pharmacy");pharmacy.setHospital(f.hospital);
        Runnable send=() -> {
            switch(event) {
                case PHARMACY_DISPENSED -> sender.notifyDispensed(prescription,f.patient,pharmacy);
                case PHARMACY_READY -> sender.notifyReadyForCollection(prescription,f.patient,pharmacy);
                case PHARMACY_READY_CANCELLED -> sender.notifyReadyCancelled(prescription,f.patient,pharmacy);
                case PHARMACY_READY_REMINDER -> sender.notifyReadyReminder(prescription,f.patient,pharmacy);
                case PHARMACY_OUT_OF_STOCK -> sender.notifyOutOfStock(prescription,f.patient,PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
                case PHARMACY_REFILL_REMINDER -> sender.notifyRefillReminder(prescription,f.patient,3);
                default -> throw new AssertionError("Missing real sender contract: "+event);
            }
        };
        send.run();f.assertDirectNotice(event,NotificationChannel.SMS);
        PatientMessageGate.Notice notice=ReflectionTestUtils.invokeMethod(sender,"notice",event,prescription,pharmacy);
        assertThat(notice).isEqualTo(new PatientMessageGate.Notice(event,f.hospital.getId(),f.subjectId));
        String key=PatientNotificationDelivery.keyOf(notice.event(),notice.subjectId(),NotificationChannel.SMS);
        assertThat(key).isEqualTo(event.name()+":"+f.subjectId+":SMS");
        if(refusal==Refusal.NONE) {
            assertThat(f.wire).hasSize(1);
            var delivered=f.wire.getFirst();assertThat(delivered.recipient()).isEqualTo("+22670000000");
            assertThat(delivered.recipient()).isNotEqualTo(f.patient.getPhoneNumberSecondary());
            String suffix=f.messages.getMessage(PharmacyServiceSupport.OUT_OF_STOCK_PRINT,null,f.expectedLocale).trim();
            String code=switch(event) {
                case PHARMACY_DISPENSED -> "sms.pharmacy.dispensed";
                case PHARMACY_READY -> "sms.pharmacy.readyForCollection";
                case PHARMACY_READY_CANCELLED -> "sms.pharmacy.readyCancelled";
                case PHARMACY_READY_REMINDER -> "sms.pharmacy.readyReminder";
                case PHARMACY_OUT_OF_STOCK -> "sms.pharmacy.outOfStock";
                case PHARMACY_REFILL_REMINDER -> "sms.pharmacy.refillReminder";
                default -> throw new AssertionError(event);
            };
            Object[] arguments={f.patient.getFirstName(),event==PatientNotificationEvent.PHARMACY_OUT_OF_STOCK?suffix:
                event==PatientNotificationEvent.PHARMACY_REFILL_REMINDER?"3":pharmacy.getName()};
            assertThat(delivered.body()).isEqualTo(f.messages.getMessage(code,arguments,f.expectedLocale).trim());
        } else {
            send.run(); // a repeated refusal has one trace, never a second recipient delivery
            assertThat(f.wire).isEmpty();assertThat(f.rows).containsOnlyKeys(key);
            var row=f.rows.get(key);assertThat(row.getHospitalId()).isEqualTo(f.hospital.getId());
            assertThat(row.getPatientId()).isEqualTo(f.patient.getId());
            assertThat(row.getErrorCode()).isEqualTo(refusal==Refusal.OPT_OUT?"OPTED_OUT":"RECIPIENT_UNVERIFIED");
        }
    }
}
