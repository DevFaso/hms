package com.example.hms.service.recordaccess;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.PatientImmunization;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.discharge.DischargeSummary;
import com.example.hms.model.medication.PharmacyFill;
import com.example.hms.model.referral.ObgynReferral;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRecordSharingOptOutRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D4261: content tied to a restricted visit follows the visit's
 * restriction in every shared view. The two halves of the rule: which visits
 * a read at another hospital is refused ({@code withheldVisits}), and what
 * each kind of record does with that answer ({@link RestrictedVisitScope}).
 */
@DisplayName("D4261: what a visit produced follows the visit's restriction")
class RestrictedVisitScopeTest {

    private final UUID actor = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID actingId = UUID.randomUUID();
    private final Hospital recordingHospital = hospital(UUID.randomUUID());

    private static Hospital hospital(UUID id) {
        Hospital hospital = new Hospital();
        hospital.setId(id);
        return hospital;
    }

    private Encounter visit(SensitivityCategory tag, Department department) {
        Encounter visit = new Encounter();
        visit.setId(UUID.randomUUID());
        visit.setHospital(recordingHospital);
        visit.setSensitivityCategory(tag);
        visit.setDepartment(department);
        return visit;
    }

    @Nested
    @DisplayName("the visits withheld from a read at another hospital")
    class WithheldVisits {

        private EncounterRepository encounterRepository;
        private BreakGlassGate breakGlassGate;
        private RecordAccessPolicyImpl policy;

        @BeforeEach
        void setUp() {
            encounterRepository = mock(EncounterRepository.class);
            breakGlassGate = mock(BreakGlassGate.class);
            policy = new RecordAccessPolicyImpl(mock(HospitalRepository.class),
                mock(PatientRecordSharingOptOutRepository.class), mock(StaffRepository.class),
                mock(TreatmentRelationshipResolver.class), mock(PatientHospitalRegistrationRepository.class),
                breakGlassGate, mock(PatientRepository.class), encounterRepository, new SensitivityClassifierImpl());
        }

        @Test
        @DisplayName("a visit tagged by hand and a visit of a department with a default category are withheld; an ordinary one is not")
        void taggedVisitsAreWithheld() {
            Department hivClinic = new Department();
            hivClinic.setDefaultSensitivityCategory(SensitivityCategory.HIV);
            Encounter tagged = visit(SensitivityCategory.BEHAVIOURAL_HEALTH, null);
            Encounter byDepartment = visit(null, hivClinic);
            Encounter ordinary = visit(null, new Department());
            when(encounterRepository.findRecordedElsewhere(patientId, actingId))
                .thenReturn(List.of(tagged, byDepartment, ordinary));

            assertThat(policy.withheldVisits(actor, patientId, actingId)).containsExactly(tagged, byDepartment);
        }

        @Test
        @DisplayName("a live break-the-glass session unlocks the visits, and what they produced with them")
        void breakTheGlassUnlocks() {
            when(breakGlassGate.isUnlocked(actor, patientId, actingId)).thenReturn(true);

            assertThat(policy.withheldVisits(actor, patientId, actingId)).isEmpty();
            verify(encounterRepository, never()).findRecordedElsewhere(patientId, actingId);
        }

        @Test
        @DisplayName("a read with no acting hospital measures against none")
        void noActingHospital() {
            assertThat(policy.withheldVisits(actor, patientId, null)).isEmpty();
            assertThat(policy.withheldVisits(actor, null, actingId)).isEmpty();
        }
    }

    @Nested
    @DisplayName("what each record does with them")
    class Scope {

        private final Encounter restricted = visit(SensitivityCategory.HIV, null);
        private final Encounter ordinary = visit(null, null);
        private final RestrictedVisitScope scope = RestrictedVisitScope.of(List.of(restricted));

        @Test
        @DisplayName("a prescription, and the dispensing that fills it")
        void prescriptionAndFill() {
            assertThat(scope.admits(prescriptionIn(restricted))).isFalse();
            assertThat(scope.admits(prescriptionIn(ordinary))).isTrue();
            assertThat(scope.admits(fillOf(prescriptionIn(restricted)))).isFalse();
            assertThat(scope.admits(fillOf(prescriptionIn(ordinary)))).isTrue();
            assertThat(scope.admits(fillOf(null))).as("a dispensing with no prescription names no visit").isTrue();
        }

        @Test
        @DisplayName("a dispensing the acting hospital handed out is shown there, its withheld prescription still is not")
        void dispensedHereIsShownHere() {
            RestrictedVisitScope atActing = RestrictedVisitScope.of(List.of(restricted), actingId);
            Prescription withheld = prescriptionIn(restricted);
            PharmacyFill dispensedHere = fillOf(withheld);
            dispensedHere.setHospital(hospital(actingId));
            PharmacyFill dispensedAtTheRecordingHospital = fillOf(withheld);
            dispensedAtTheRecordingHospital.setHospital(recordingHospital);

            assertThat(atActing.admits(dispensedHere)).isTrue();
            assertThat(atActing.admits(withheld)).as("the prescription stays withheld").isFalse();
            assertThat(atActing.admits(dispensedAtTheRecordingHospital)).isFalse();
            assertThat(scope.admits(dispensedHere)).as("a scope that knows no acting hospital").isFalse();
        }

        @Test
        @DisplayName("a lab order or result the acting hospital performed for a withheld visit stays withheld")
        void performedHereForAWithheldVisitStaysWithheld() {
            RestrictedVisitScope atActing = RestrictedVisitScope.of(List.of(restricted), actingId);
            LabOrder performedHere = labOrderIn(restricted);
            performedHere.setHospital(recordingHospital);
            performedHere.setPerformingHospital(hospital(actingId));
            LabResult result = resultOf(performedHere);

            assertThat(atActing.admits(performedHere)).isFalse();
            assertThat(atActing.admits(result)).isFalse();
        }

        @Test
        @DisplayName("a lab order and its result")
        void labOrderAndResult() {
            assertThat(scope.admits(labOrderIn(restricted))).isFalse();
            assertThat(scope.admits(labOrderIn(ordinary))).isTrue();
            assertThat(scope.admits(labOrderIn(null))).isTrue();
            assertThat(scope.admits(resultOf(labOrderIn(restricted)))).isFalse();
            assertThat(scope.admits(resultOf(labOrderIn(ordinary)))).isTrue();
        }

        @Test
        @DisplayName("an imaging order and its report")
        void imagingOrderAndReport() {
            assertThat(scope.admits(imagingOrderIn(restricted))).isFalse();
            assertThat(scope.admits(imagingOrderIn(ordinary))).isTrue();
            assertThat(scope.admits(imagingOrderIn(null))).isTrue();
            ImagingReport report = new ImagingReport();
            report.setImagingOrder(imagingOrderIn(restricted));
            assertThat(scope.admits(report)).isFalse();
        }

        @Test
        @DisplayName("a procedure order, a discharge summary, a vaccination and a referral")
        void theOtherRecordsOfAVisit() {
            ProcedureOrder procedure = new ProcedureOrder();
            procedure.setEncounter(restricted);
            DischargeSummary summary = new DischargeSummary();
            summary.setEncounter(restricted);
            PatientImmunization vaccination = new PatientImmunization();
            vaccination.setEncounter(restricted);
            ObgynReferral referral = new ObgynReferral();
            referral.setEncounter(restricted);

            assertThat(scope.admits(procedure)).isFalse();
            assertThat(scope.admits(summary)).isFalse();
            assertThat(scope.admits(vaccination)).isFalse();
            assertThat(scope.admits(referral)).isFalse();
            assertThat(scope.admits(new ProcedureOrder())).isTrue();
            assertThat(scope.admits(new PatientImmunization())).as("a vaccination given outside a visit").isTrue();
        }

        @Test
        @DisplayName("a vital sign follows the hospital and the days of the visit")
        void vitalSign() {
            LocalDateTime day = LocalDateTime.of(2026, 11, 17, 9, 0);
            restricted.setEncounterDate(day);
            restricted.setCheckoutTimestamp(day.plusHours(2));
            restricted.setStatus(EncounterStatus.COMPLETED);

            assertThat(scope.admits(vital(recordingHospital, day.plusMinutes(10)))).isFalse();
            assertThat(scope.admits(vital(recordingHospital, day.plusDays(1)))).as("another day").isTrue();
            assertThat(scope.admits(vital(hospital(actingId), day.plusMinutes(10)))).as("another hospital").isTrue();
            assertThat(scope.admits(vital(recordingHospital, null))).as("no time to place it by").isFalse();
        }

        @Test
        @DisplayName("with no visit withheld, everything is shown and a query is still given an id")
        void nothingWithheld() {
            RestrictedVisitScope none = RestrictedVisitScope.of(List.of());
            assertThat(none.isEmpty()).isTrue();
            assertThat(none).isSameAs(RestrictedVisitScope.none());
            assertThat(none.admits(prescriptionIn(restricted))).isTrue();
            assertThat(none.admits(vital(recordingHospital, null))).isTrue();
            assertThat(none.visitIdsForQuery()).as("an empty NOT IN list is not portable").hasSize(1);
            assertThat(scope.visitIdsForQuery()).containsExactly(restricted.getId());
        }

        private Prescription prescriptionIn(Encounter visit) {
            Prescription prescription = new Prescription();
            prescription.setEncounter(visit);
            return prescription;
        }

        private PharmacyFill fillOf(Prescription prescription) {
            PharmacyFill fill = new PharmacyFill();
            fill.setPrescription(prescription);
            return fill;
        }

        private LabOrder labOrderIn(Encounter visit) {
            LabOrder order = new LabOrder();
            order.setEncounter(visit);
            return order;
        }

        private LabResult resultOf(LabOrder order) {
            LabResult result = new LabResult();
            result.setLabOrder(order);
            return result;
        }

        private ImagingOrder imagingOrderIn(Encounter visit) {
            ImagingOrder order = new ImagingOrder();
            order.setEncounterId(visit == null ? null : visit.getId());
            return order;
        }

        private PatientVitalSign vital(Hospital at, LocalDateTime when) {
            PatientVitalSign vital = new PatientVitalSign();
            vital.setHospital(at);
            vital.setRecordedAt(when);
            return vital;
        }
    }
}
