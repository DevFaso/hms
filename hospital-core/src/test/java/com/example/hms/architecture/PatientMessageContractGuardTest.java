package com.example.hms.architecture;

import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.testsupport.PatientMessageContractFixture;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class PatientMessageContractGuardTest {
    @Test void everyEventHasRunnableCasesThroughAnActualSender() throws Exception {
        Set<PatientNotificationEvent> tested=EnumSet.noneOf(PatientNotificationEvent.class);
        for(String type:List.of("service.AppointmentPatientMessageContractTest","service.DirectPatientMessageContractTest",
            "service.notification.QueuedPatientMessageContractTest","service.pharmacy.PharmacyPatientMessageContractTest",
            "service.pharmacy.partner.PartnerPatientMessageContractTest")) {
            var method=Class.forName("com.example.hms."+type).getDeclaredMethod("cases");method.setAccessible(true);
            try(Stream<?> cases=(Stream<?>)method.invoke(null)) {
                var events=cases.map(row->(PatientNotificationEvent)((Arguments)row).get()[0]).toList();
                assertThat(events).as(type).isNotEmpty();tested.addAll(events);
            }
        }
        assertThat(tested).isEqualTo(EnumSet.allOf(PatientNotificationEvent.class));
        assertThat(PatientMessageContractFixture.coveredEvents()).isEqualTo(tested);
    }

    @Test void partnerPatientNoticesCannotLoseTheirHospitalAndDecisionTrace() throws Exception {
        String source=SourceScan.stripComments(Files.readString(Path.of("src/main/java/com/example/hms/service/pharmacy/partner/SmsPartnerNotificationChannel.java")));
        assertThat(source.replaceAll("\\s+","")).doesNotContain(",null,null)");
        assertThat(SourceScan.methodBody(source,"notifyPatientAccepted")).contains("patientNotice(");
        assertThat(SourceScan.methodBody(source,"notifyPatientDispensed")).contains("patientNotice(");
        assertThat(SourceScan.methodBody(source,"patientNotice")).contains("prescription.getHospital().getId()", "decision.getId()", "prescription.getPatient().getId()");
    }
}
