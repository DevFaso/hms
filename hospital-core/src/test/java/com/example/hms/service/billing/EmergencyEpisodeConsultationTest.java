package com.example.hms.service.billing;

import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Admission;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.EncounterTreatmentRepository;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Policy rule 6 (provisional, owner decision 2026-11-20): one consultation per episode. An emergency, urgent or
 * observation admission that follows an emergency visit already billed gets no second consultation line.
 */
@DisplayName("One consultation per episode: no second line on a stay that follows a billed emergency visit")
class EmergencyEpisodeConsultationTest {

    private static final LocalDateTime ADMITTED = LocalDateTime.of(2026, 11, 20, 14, 30);

    private final EncounterRepository encounters = mock(EncounterRepository.class);
    private final BillingInvoiceRepository invoices = mock(BillingInvoiceRepository.class);
    private final EncounterTreatmentRepository treatments = mock(EncounterTreatmentRepository.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final EmergencyEpisodeConsultation rule = new EmergencyEpisodeConsultation(encounters, invoices);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private Hospital hospital;
    private Patient patient;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        hospital = new Hospital();
        hospital.setId(hospitalId);
        patient = new Patient();
        patient.setId(patientId);
    }

    private Admission stay(AdmissionType type) {
        Admission stay = new Admission();
        stay.setId(UUID.randomUUID());
        stay.setAdmissionType(type);
        stay.setPatient(patient);
        stay.setHospital(hospital);
        stay.setAdmissionDateTime(ADMITTED);
        return stay;
    }

    private Encounter emergencyVisit(LocalDateTime began, LocalDateTime ended, boolean billed) {
        Encounter visit = new Encounter();
        visit.setId(UUID.randomUUID());
        visit.setEncounterType(EncounterType.EMERGENCY);
        visit.setStatus(EncounterStatus.COMPLETED);
        visit.setEncounterDate(began);
        visit.setCheckoutTimestamp(ended);
        BillingInvoice invoice = new BillingInvoice();
        when(invoices.findByEncounter_IdAndStatusNot(visit.getId(), InvoiceStatus.CANCELLED))
            .thenReturn(billed ? List.of(invoice) : List.of());
        when(encounters.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(visit));
        return visit;
    }

    /** The stay's own visit, billed at discharge with nothing recorded on it. */
    private List<VisitChargeSource.VisitCharge> stayLines(Admission stay) {
        Encounter stayVisit = new Encounter();
        stayVisit.setId(UUID.randomUUID());
        stayVisit.setHospital(hospital);
        stayVisit.setAdmissionId(stay.getId());
        when(admissions.findById(stay.getId())).thenReturn(Optional.of(stay));
        when(treatments.findByEncounter_Id(stayVisit.getId())).thenReturn(List.of());
        ConsultationChargeSource source = new ConsultationChargeSource(treatments, admissions);
        source.setEmergencyEpisode(rule);
        return source.charges(new VisitChargeSource.Visit(stayVisit, LocalDateTime.now(),
            new VisitPriceList(List.of()), null));
    }

    @ParameterizedTest
    @EnumSource(value = AdmissionType.class, names = {"EMERGENCY", "URGENT", "OBSERVATION"})
    @DisplayName("the stay that follows a billed emergency visit of the same day gets no second consultation line")
    void noSecondConsultation(AdmissionType type) {
        emergencyVisit(ADMITTED.minusHours(3), ADMITTED, true);

        assertThat(stayLines(stay(type))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = AdmissionType.class, names = {"EMERGENCY", "URGENT", "OBSERVATION"})
    @DisplayName("a direct admission with no emergency visit before it keeps its one consultation line")
    void aDirectAdmissionKeepsItsLine(AdmissionType type) {
        when(encounters.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of());

        assertThat(stayLines(stay(type))).hasSize(1);
    }

    @Test
    @DisplayName("an emergency visit never billed, cancelled, or of another episode does not take the stay's line away")
    void onlyABilledVisitOfTheSameEpisode() {
        Admission stay = stay(AdmissionType.EMERGENCY);

        emergencyVisit(ADMITTED.minusHours(3), ADMITTED, false);
        assertThat(rule.alreadyBilledOnTheEmergencyVisit(stay)).as("never billed").isFalse();

        emergencyVisit(ADMITTED.minusDays(30), ADMITTED.minusDays(30).plusHours(2), true);
        assertThat(rule.alreadyBilledOnTheEmergencyVisit(stay)).as("last month's visit").isFalse();

        Encounter cancelled = emergencyVisit(ADMITTED.minusHours(3), ADMITTED, true);
        cancelled.setStatus(EncounterStatus.CANCELLED);
        assertThat(rule.alreadyBilledOnTheEmergencyVisit(stay)).as("a cancelled visit").isFalse();

        Encounter later = emergencyVisit(ADMITTED.plusHours(5), ADMITTED.plusHours(6), true);
        assertThat(rule.alreadyBilledOnTheEmergencyVisit(stay)).as("a visit begun after the admission").isFalse();
        later.setEncounterType(EncounterType.CONSULTATION);
        later.setEncounterDate(ADMITTED.minusHours(1));
        later.setCheckoutTimestamp(ADMITTED);
        assertThat(rule.alreadyBilledOnTheEmergencyVisit(stay)).as("an outpatient consultation is not the rule").isFalse();

        Encounter stayVisit = emergencyVisit(ADMITTED.minusHours(3), ADMITTED, true);
        stayVisit.setAdmissionId(UUID.randomUUID());
        assertThat(rule.alreadyBilledOnTheEmergencyVisit(stay)).as("a stay visit is not an emergency visit").isFalse();
    }

    @Test
    @DisplayName("a visit closed after midnight by an admission recorded a little late is still the same episode")
    void theAdmissionRecordedLate() {
        Admission stay = stay(AdmissionType.URGENT);
        stay.setAdmissionDateTime(LocalDateTime.of(2026, 11, 20, 23, 55));
        stay.setCreatedAt(LocalDateTime.of(2026, 11, 21, 0, 10));
        emergencyVisit(LocalDateTime.of(2026, 11, 20, 21, 0), LocalDateTime.of(2026, 11, 21, 0, 10), true);

        assertThat(rule.alreadyBilledOnTheEmergencyVisit(stay)).isTrue();
    }

    @Test
    @DisplayName("only the check-in copay on the emergency visit is not a billed consultation; planned admissions are another rule")
    void theCopayAloneIsNotAConsultation() {
        Encounter visit = emergencyVisit(ADMITTED.minusHours(3), ADMITTED, false);
        BillingInvoice copay = new BillingInvoice();
        copay.setCheckInCopay(true);
        when(invoices.findByEncounter_IdAndStatusNot(visit.getId(), InvoiceStatus.CANCELLED)).thenReturn(List.of(copay));

        assertThat(rule.alreadyBilledOnTheEmergencyVisit(stay(AdmissionType.EMERGENCY))).isFalse();
        assertThat(rule.alreadyBilledOnTheEmergencyVisit(stay(AdmissionType.ELECTIVE))).isFalse();
        assertThat(rule.alreadyBilledOnTheEmergencyVisit(null)).isFalse();
    }
}
