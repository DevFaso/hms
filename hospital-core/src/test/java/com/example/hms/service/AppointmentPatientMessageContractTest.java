package com.example.hms.service;

import com.example.hms.config.AppointmentLinkProperties;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.model.*;
import com.example.hms.testsupport.PatientMessageContractFixture;
import com.example.hms.testsupport.PatientMessageContractFixture.Refusal;
import com.example.hms.utility.DisplayDates;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class AppointmentPatientMessageContractTest {
    static Stream<Arguments> cases() {
        return Stream.of(PatientNotificationEvent.APPOINTMENT_CONFIRMATION,PatientNotificationEvent.APPOINTMENT_RESCHEDULED,
            PatientNotificationEvent.APPOINTMENT_CANCELLED,PatientNotificationEvent.APPOINTMENT_COMPLETED,PatientNotificationEvent.APPOINTMENT_NO_SHOW)
            .flatMap(event -> Stream.of("default","fr","en","es").flatMap(language -> Stream.of(Refusal.values()).map(refusal -> Arguments.of(event,language,refusal))));
    }

    @ParameterizedTest @MethodSource("cases")
    void appointmentCallerRealGateAndRealMailRendererUseThePatientsChoices(PatientNotificationEvent event,String language,Refusal refusal) {
        var f=new PatientMessageContractFixture(event,NotificationChannel.EMAIL,language,refusal);
        var sender=f.realSender(AppointmentServiceImpl.class,Map.of("patientMessageGate",f.gate,"patientLocaleResolver",f.locales,
            "appointmentLinks",new AppointmentLinkProperties(),"frontendBaseUrl","https://example.invalid"));
        User clinician=new User();clinician.setFirstName("cdx Clinician");clinician.setLastName("Test");
        Staff staff=new Staff();staff.setUser(clinician);
        Appointment appointment=new Appointment();appointment.setId(f.subjectId);appointment.setPatient(f.patient);appointment.setHospital(f.hospital);appointment.setStaff(staff);
        appointment.setAppointmentDate(LocalDate.of(2026,11,25));appointment.setStartTime(LocalTime.of(10,0));appointment.setEndTime(LocalTime.of(10,30));
        Runnable send=()->ReflectionTestUtils.invokeMethod(sender,"emailPatientAboutAppointment",appointment,event);
        send.run();String key=PatientNotificationDelivery.keyOf(event,f.subjectId,NotificationChannel.EMAIL);
        if(refusal==Refusal.NONE) {
            assertThat(f.wire).hasSize(1);var delivered=f.wire.getFirst();
            assertThat(delivered.recipient()).isEqualTo("cdx-patient@example.invalid");
            String action=switch(event) {
                case APPOINTMENT_CONFIRMATION -> "confirmed";case APPOINTMENT_RESCHEDULED -> "rescheduled";
                case APPOINTMENT_CANCELLED -> "cancelled";case APPOINTMENT_COMPLETED -> "completed";case APPOINTMENT_NO_SHOW -> "noshow";
                default -> throw new AssertionError("Missing appointment sender contract: "+event);
            };
            assertThat(delivered.subject()).isEqualTo(f.messages.getMessage("email.appointment."+action+".subject",null,f.expectedLocale));
            if(event!=PatientNotificationEvent.APPOINTMENT_COMPLETED && event!=PatientNotificationEvent.APPOINTMENT_NO_SHOW)
                assertThat(delivered.body()).contains(DisplayDates.date(appointment.getAppointmentDate(),f.expectedLocale));
        } else {
            send.run();assertThat(f.wire).isEmpty();assertThat(f.rows).containsOnlyKeys(key);
            assertThat(f.rows.get(key).getErrorCode()).isEqualTo(refusal==Refusal.OPT_OUT?"OPTED_OUT":"RECIPIENT_UNVERIFIED");
            assertThat(f.rows.get(key).getPatientId()).isEqualTo(f.patient.getId());
            assertThat(f.rows.get(key).getHospitalId()).isEqualTo(f.hospital.getId());
        }
    }
}
