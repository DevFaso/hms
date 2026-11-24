package com.example.hms.service.pharmacy.partner;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.model.*;
import com.example.hms.model.pharmacy.*;
import com.example.hms.testsupport.PatientMessageContractFixture;
import com.example.hms.testsupport.PatientMessageContractFixture.Refusal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.UUID;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PartnerPatientMessageContractTest {
    static Stream<Arguments> cases() {
        return Stream.of(PatientNotificationEvent.PHARMACY_OUT_OF_STOCK,PatientNotificationEvent.PHARMACY_DISPENSED)
            .flatMap(event->Stream.of("default","fr","en","es").flatMap(language->Stream.of(Refusal.values()).map(refusal->Arguments.of(event,language,refusal))));
    }
    @ParameterizedTest @MethodSource("cases")
    void partnerRepliesUseTheOriginatingDecisionAndVerifiedPatientContact(PatientNotificationEvent event,String language,Refusal refusal) {
        var f=new PatientMessageContractFixture(event,NotificationChannel.SMS,language,refusal);
        var transport=mock(PartnerPharmacySmsTransport.class);var templates=new PartnerSmsTemplates(f.messages);
        var sender=new SmsPartnerNotificationChannel(transport,f.gate,templates,f.locales);
        Prescription rx=new Prescription();rx.setId(UUID.randomUUID());rx.setPatient(f.patient);rx.setHospital(f.hospital);rx.setMedicationName("cdx PRIVATE-MEDICINE");
        PrescriptionRoutingDecision decision=new PrescriptionRoutingDecision();decision.setId(f.subjectId);decision.setPrescription(rx);
        Pharmacy partner=new Pharmacy();partner.setName("cdx Partner");Hospital other=new Hospital();other.setId(UUID.randomUUID());partner.setHospital(other);
        Runnable send=()->{
            if(event==PatientNotificationEvent.PHARMACY_OUT_OF_STOCK)sender.notifyPatientAccepted(decision,f.patient,partner);
            else sender.notifyPatientDispensed(decision,f.patient,partner);
        };
        send.run();verifyNoInteractions(transport);
        if(refusal==Refusal.NONE) {
            assertThat(f.wire).hasSize(1);var delivered=f.wire.getFirst();
            assertThat(delivered.recipient()).isEqualTo("+22670000000");
            assertThat(delivered.body()).isEqualTo(event==PatientNotificationEvent.PHARMACY_OUT_OF_STOCK?
                templates.patientAccepted(partner.getName(),f.expectedLocale):templates.patientDispensed(partner.getName(),f.expectedLocale));
            assertThat(delivered.body()).doesNotContain("PRIVATE-MEDICINE");
        } else {
            send.run();String key=PatientNotificationDelivery.keyOf(event,decision.getId(),NotificationChannel.SMS);
            assertThat(f.wire).isEmpty();assertThat(f.rows).containsOnlyKeys(key);
            var trace=f.rows.get(key);assertThat(trace.getHospitalId()).isEqualTo(f.hospital.getId()).isNotEqualTo(other.getId());
            assertThat(trace.getSubjectId()).isEqualTo(decision.getId());assertThat(trace.getPatientId()).isEqualTo(f.patient.getId());
        }
    }

    @Test void aMissingOrDifferentPatientContextNeverSendsAnUntraceableNotice() {
        var f=new PatientMessageContractFixture(PatientNotificationEvent.PHARMACY_DISPENSED,NotificationChannel.SMS,"default",Refusal.NONE);
        var sender=new SmsPartnerNotificationChannel(mock(PartnerPharmacySmsTransport.class),f.gate,new PartnerSmsTemplates(f.messages),f.locales);
        Pharmacy partner=new Pharmacy();partner.setName("cdx Partner");
        sender.notifyPatientDispensed(null,f.patient,partner);
        Prescription rx=new Prescription();rx.setHospital(f.hospital);Patient different=new Patient();different.setId(UUID.randomUUID());rx.setPatient(different);
        PrescriptionRoutingDecision decision=new PrescriptionRoutingDecision();decision.setId(UUID.randomUUID());decision.setPrescription(rx);
        sender.notifyPatientAccepted(decision,f.patient,partner);
        assertThat(f.wire).isEmpty();assertThat(f.rows).isEmpty();
    }
}
