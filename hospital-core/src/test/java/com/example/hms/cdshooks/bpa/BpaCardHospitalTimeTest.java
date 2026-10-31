package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.rules.CdsRuleContext;
import com.example.hms.cdshooks.rules.DuplicateMedicationOrderRule;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.repository.BpaProtocolRepository;
import com.example.hms.repository.HospitalRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D1114 (round 19, the D1084 class): a time on a CDS or BPA card is the
 * hospital's wall clock, as the chart shows it. The malaria card read
 * "31/10/2026 00:54" (server UTC) for a temperature the vitals tab showed at
 * 20:54 (UTC-4 in the QA run; invisible at Ouagadougou's UTC+0).
 */
class BpaCardHospitalTimeTest {

    /** A zone no test host runs in, so the conversion always shows. */
    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Pacific/Kiritimati");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final UUID hospitalId = UUID.randomUUID();
    private final HospitalRepository hospitals = mock(HospitalRepository.class);

    private CdsCardText text() {
        Organization organization = new Organization();
        organization.setDefaultTimezone(HOSPITAL_ZONE.getId());
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setOrganization(organization);
        when(hospitals.findById(hospitalId)).thenReturn(Optional.of(hospital));
        // QA D1121: the zone is read as a value, never through the LAZY organisation.
        when(hospitals.findTimezoneIdById(hospitalId)).thenReturn(Optional.of(HOSPITAL_ZONE.getId()));
        CdsCardText text = new CdsCardText(null);
        text.setHospitals(hospitals);
        return text;
    }

    private static LocalDateTime onTheHospitalClock(LocalDateTime serverTime) {
        return serverTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(HOSPITAL_ZONE).toLocalDateTime();
    }

    @Test
    void theMalariaCardWritesTheTemperatureTimeOnTheHospitalsClock() {
        BpaProtocolRepository protocols = mock(BpaProtocolRepository.class);
        when(protocols.findByProtocolCodeAndActiveTrue("MALARIA_FEVER")).thenReturn(Optional.of(
            BpaProtocol.builder().protocolCode("MALARIA_FEVER").name("Malaria").summary("RDT").active(true).build()));
        MalariaFeverProtocolRule rule = new MalariaFeverProtocolRule(protocols, text());
        Patient patient = Patient.builder().build();
        patient.setId(UUID.randomUUID());
        LocalDateTime recorded = LocalDateTime.now().minusHours(2).withSecond(0).withNano(0);
        PatientVitalSign fever = PatientVitalSign.builder().temperatureCelsius(39.0).recordedAt(recorded).build();

        List<CdsCard> cards = rule.evaluate(new BpaRuleContext(patient, hospitalId, List.of(fever), List.of(), List.of()));

        assertThat(cards).singleElement().satisfies(card -> assertThat(card.detail())
            .contains(onTheHospitalClock(recorded).format(WHEN))
            .doesNotContain(recorded.format(WHEN)));
    }

    @Test
    void anUnknownHospitalKeepsTheServerClock() {
        CdsCardText text = text();
        LocalDateTime at = LocalDateTime.of(2026, 10, 31, 0, 54);
        assertThat(text.dateTime(at, text.zoneOf(null))).isEqualTo(at.format(WHEN));
        assertThat(text.dateTime(null, HOSPITAL_ZONE)).isEqualTo("—");
    }

    /** The duplicate-order card dates the earlier order on the hospital's calendar, not the server's day. */
    @Test
    void theDuplicateCardDatesTheEarlierOrderOnTheHospitalsDay() {
        CdsCardText text = text();
        Hospital hospital = hospitals.findById(hospitalId).orElseThrow();
        LocalDateTime createdAt = LocalDate.now().atTime(23, 30).minusDays(1);
        Prescription earlier = new Prescription();
        earlier.setId(UUID.randomUUID());
        earlier.setHospital(hospital);
        earlier.setMedicationName("Paracétamol");
        earlier.setStatus(PrescriptionStatus.SIGNED);
        earlier.setCreatedAt(createdAt);
        DuplicateMedicationOrderRule rule = new DuplicateMedicationOrderRule();
        rule.setCardText(text);
        Patient patient = Patient.builder().build();
        patient.setId(UUID.randomUUID());

        List<CdsCard> cards = rule.evaluate(new CdsRuleContext(patient, hospitalId, "Paracétamol", null, null,
            null, null, null, List.of(earlier), null));

        String hospitalDay = text.date(onTheHospitalClock(createdAt).toLocalDate());
        assertThat(cards).singleElement().satisfies(card -> assertThat(card.summary()).contains(hospitalDay));
    }
}
