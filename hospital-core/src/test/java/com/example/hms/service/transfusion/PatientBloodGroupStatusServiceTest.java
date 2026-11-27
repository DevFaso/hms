package com.example.hms.service.transfusion;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.RhFactor;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientBloodGroup;
import com.example.hms.payload.dto.BloodGroupStatusDTO;
import com.example.hms.payload.dto.transfusion.AntibodyStatusResponseDTO;
import com.example.hms.repository.PatientBloodGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D3901: what the record says the patient's blood group is, and what it rests on. */
@DisplayName("QA D3901: the patient's blood group as it is shown")
class PatientBloodGroupStatusServiceTest {

    private final PatientBloodGroupRepository repository = mock(PatientBloodGroupRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PositiveAntibodyScreenService> provider = mock(ObjectProvider.class);
    private final PositiveAntibodyScreenService antibodies = mock(PositiveAntibodyScreenService.class);
    private final PatientBloodGroupStatusService service = new PatientBloodGroupStatusService(repository, provider);

    private final UUID hospitalId = UUID.randomUUID();
    private final LocalDateTime groupedAt = LocalDateTime.of(2026, 11, 25, 9, 30);
    private Patient patient;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        when(provider.getIfAvailable()).thenReturn(antibodies);
    }

    private void labGrouped(AboGroup abo, RhFactor rh) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Centre Medical Test");
        PatientBloodGroup group = PatientBloodGroup.builder().patient(patient).hospital(hospital)
            .aboGroup(abo).rhFactor(rh).performedAt(groupedAt).build();
        when(repository.findCurrentWithHospital(patient.getId(), hospitalId)).thenReturn(Optional.of(group));
    }

    @Test
    @DisplayName("a typed group with no laboratory grouping is declared only, never confirmed")
    void declaredOnly() {
        patient.setBloodType("A+");
        when(repository.findCurrentWithHospital(any(), any())).thenReturn(Optional.empty());

        BloodGroupStatusDTO status = service.of(patient, hospitalId);

        assertThat(status.getDeclared()).isEqualTo("A+");
        assertThat(status.getConfirmed()).isNull();
        assertThat(status.isDiscrepancy()).isFalse();
        assertThat(status.getAlloImmunised()).as("a list read does not look").isNull();
    }

    @Test
    @DisplayName("the laboratory's grouping reaches the record with where and when (P02: O-, nothing typed)")
    void labGroupingReachesTheRecord() {
        labGrouped(AboGroup.O, RhFactor.NEGATIVE);

        BloodGroupStatusDTO status = service.of(patient, hospitalId);

        assertThat(status.getConfirmed()).isEqualTo("O-");
        assertThat(status.getConfirmedAt()).isEqualTo(groupedAt);
        assertThat(status.getConfirmedHospitalName()).isEqualTo("Centre Medical Test");
        assertThat(status.getDeclared()).isNull();
        assertThat(status.isDiscrepancy()).isFalse();
    }

    @Test
    @DisplayName("a typed group that contradicts the laboratory is flagged, and never replaces it (P03: A+ vs B+)")
    void typedGroupThatDiffersIsFlagged() {
        patient.setBloodType("A+");
        labGrouped(AboGroup.B, RhFactor.POSITIVE);

        BloodGroupStatusDTO status = service.of(patient, hospitalId);

        assertThat(status.getConfirmed()).isEqualTo("B+");
        assertThat(status.getDeclared()).isEqualTo("A+");
        assertThat(status.isDiscrepancy()).isTrue();
    }

    @Test
    @DisplayName("a typed group that agrees is no discrepancy, whatever its case or spacing")
    void agreeingTypedGroup() {
        patient.setBloodType(" b+ ");
        labGrouped(AboGroup.B, RhFactor.POSITIVE);

        assertThat(service.of(patient, hospitalId).isDiscrepancy()).isFalse();
    }

    @Test
    @DisplayName("an unscoped read has no hospital laboratory to ask: declared only")
    void noHospital() {
        patient.setBloodType("A+");

        BloodGroupStatusDTO status = service.of(patient, null);

        assertThat(status.getConfirmed()).isNull();
        verify(repository, never()).findCurrentWithHospital(any(), any());
    }

    @Test
    @DisplayName("a chart read carries the allo-immunisation flag and the antibody identified (P03: anti-E)")
    void chartReadCarriesTheAntibody() {
        labGrouped(AboGroup.B, RhFactor.POSITIVE);
        when(antibodies.status(patient.getId(), hospitalId)).thenReturn(AntibodyStatusResponseDTO.builder()
            .alloImmunised(true).identified(true).antibodySpecificities("anti-E").build());

        BloodGroupStatusDTO status = service.withAntibodies(patient, hospitalId);

        assertThat(status.getConfirmed()).isEqualTo("B+");
        assertThat(status.getAlloImmunised()).isTrue();
        assertThat(status.getAntibodySpecificities()).isEqualTo("anti-E");
    }

    @Test
    @DisplayName("a positive screen not yet identified is flagged, with no antibody named")
    void positiveScreenNotIdentified() {
        when(repository.findCurrentWithHospital(any(), any())).thenReturn(Optional.empty());
        when(antibodies.status(patient.getId(), hospitalId)).thenReturn(AntibodyStatusResponseDTO.builder()
            .alloImmunised(true).identified(false).build());

        BloodGroupStatusDTO status = service.withAntibodies(patient, hospitalId);

        assertThat(status.getAlloImmunised()).isTrue();
        assertThat(status.getAntibodySpecificities()).isNull();
    }

    @Test
    @DisplayName("a chart read of a patient with no antibody says so: false, not unknown")
    void noAntibody() {
        when(repository.findCurrentWithHospital(any(), any())).thenReturn(Optional.empty());
        when(antibodies.status(patient.getId(), hospitalId)).thenReturn(AntibodyStatusResponseDTO.builder()
            .alloImmunised(false).build());

        assertThat(service.withAntibodies(patient, hospitalId).getAlloImmunised()).isFalse();
    }

    @Test
    @DisplayName("the patient's own record shows the newest laboratory grouping of any hospital")
    void ownRecord() {
        patient.setBloodType("A+");
        Hospital hospital = new Hospital();
        hospital.setName("Centre Medical Test");
        when(repository.findCurrentAtEveryHospital(patient.getId())).thenReturn(List.of(
            PatientBloodGroup.builder().hospital(hospital).aboGroup(AboGroup.B).rhFactor(RhFactor.POSITIVE)
                .performedAt(groupedAt).build()));

        BloodGroupStatusDTO status = service.ofOwnRecord(patient);

        assertThat(status.getConfirmed()).isEqualTo("B+");
        assertThat(status.isDiscrepancy()).isTrue();
    }

    @Test
    @DisplayName("a ward's list reads every card's laboratory group at once, and keeps a card with none")
    void ofAllReadsOnce() {
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        other.setBloodType("O+");
        patient.setBloodType("A+");
        Hospital hospital = new Hospital();
        hospital.setName("Centre Medical Test");
        when(repository.findCurrentForPatients(any(), any())).thenReturn(List.of(PatientBloodGroup.builder()
            .patient(patient).hospital(hospital).aboGroup(AboGroup.B).rhFactor(RhFactor.POSITIVE)
            .performedAt(groupedAt).build()));

        var groups = service.ofAll(List.of(patient, other), hospitalId);

        assertThat(groups.get(patient.getId()).getConfirmed()).isEqualTo("B+");
        assertThat(groups.get(patient.getId()).isDiscrepancy()).isTrue();
        assertThat(groups.get(other.getId()).getDeclared()).isEqualTo("O+");
        assertThat(groups.get(other.getId()).getConfirmed()).isNull();
        verify(repository, org.mockito.Mockito.times(1)).findCurrentForPatients(any(), any());
        verify(repository, never()).findCurrentWithHospital(any(), any());
    }

    @Test
    @DisplayName("no patient, no answer; no antibody service, the group alone; no hospital, no grouping read")
    void edges() {
        assertThat(service.of(null, hospitalId)).isNull();
        assertThat(service.withAntibodies(null, hospitalId)).isNull();
        assertThat(service.ofOwnRecord(null)).isNull();
        assertThat(service.ofAll(List.of(patient), null).get(patient.getId()).getConfirmed()).isNull();
        verify(repository, never()).findCurrentForPatients(any(), any());

        when(provider.getIfAvailable()).thenReturn(null);
        when(repository.findCurrentWithHospital(any(), any())).thenReturn(Optional.empty());
        patient.setBloodType("A+");
        BloodGroupStatusDTO status = service.withAntibodies(patient, hospitalId);
        assertThat(status.getDeclared()).isEqualTo("A+");
        assertThat(status.getAlloImmunised()).isNull();
        assertThat(PatientBloodGroupStatusService.declaredOnly(" ").getDeclared()).isNull();
    }

    @SuppressWarnings("unchecked")
    private com.example.hms.repository.TransfusionReactionRepository reactionsWired(
            com.example.hms.service.recordaccess.CrossHospitalReachRecorder recorder) {
        com.example.hms.repository.TransfusionReactionRepository reactions =
            mock(com.example.hms.repository.TransfusionReactionRepository.class);
        ObjectProvider<com.example.hms.service.recordaccess.CrossHospitalReachRecorder> recorders = mock(ObjectProvider.class);
        when(recorders.getIfAvailable()).thenReturn(recorder);
        ObjectProvider<com.example.hms.utility.RoleValidator> roles = mock(ObjectProvider.class);
        SevereTransfusionReactionLookup lookup = new SevereTransfusionReactionLookup(reactions, recorders, roles);
        ObjectProvider<SevereTransfusionReactionLookup> lookups = mock(ObjectProvider.class);
        when(lookups.getIfAvailable()).thenReturn(lookup);
        service.setReactions(lookups);
        when(repository.findCurrentWithHospital(any(), any())).thenReturn(Optional.empty());
        return reactions;
    }

    private static com.example.hms.model.TransfusionReaction haemolyticAt(com.example.hms.model.Hospital at,
                                                                         LocalDateTime onset) {
        com.example.hms.model.TransfusionReaction reaction = new com.example.hms.model.TransfusionReaction();
        reaction.setReactionType(com.example.hms.enums.TransfusionReactionType.ACUTE_HEMOLYTIC);
        reaction.setSeverity(com.example.hms.enums.TransfusionReactionSeverity.SEVERE);
        reaction.setOnsetAt(onset);
        reaction.setHospital(at);
        return reaction;
    }

    @Test
    @DisplayName("QA D4151: a severe transfusion reaction on record is on the chart's safety line, a mild one is not")
    void severeReactionOnRecord() {
        com.example.hms.service.recordaccess.CrossHospitalReachRecorder recorder =
            mock(com.example.hms.service.recordaccess.CrossHospitalReachRecorder.class);
        com.example.hms.repository.TransfusionReactionRepository reactions = reactionsWired(recorder);
        com.example.hms.model.Hospital here = new com.example.hms.model.Hospital();
        here.setId(hospitalId);
        here.setName("Centre Test A");
        LocalDateTime onset = LocalDateTime.of(2026, 11, 27, 5, 37);
        // Only the severities that stay on the record are asked for: a mild or moderate reaction is not a safety line.
        when(reactions.findFirstByPatient_IdAndSeverityInOrderByOnsetAtDesc(patient.getId(),
            java.util.EnumSet.of(com.example.hms.enums.TransfusionReactionSeverity.SEVERE,
                com.example.hms.enums.TransfusionReactionSeverity.LIFE_THREATENING)))
            .thenReturn(Optional.of(haemolyticAt(here, onset)));

        BloodGroupStatusDTO status = service.withAntibodies(patient, hospitalId);

        assertThat(status.getSevereTransfusionReaction()).isTrue();
        assertThat(status.getSevereTransfusionReactionType())
            .isEqualTo(com.example.hms.enums.TransfusionReactionType.ACUTE_HEMOLYTIC);
        assertThat(status.getSevereTransfusionReactionAt()).isEqualTo(onset);
        assertThat(status.getSevereTransfusionReactionHospitalName()).isEqualTo("Centre Test A");
        // Its own hospital's reaction is no cross-hospital read.
        org.mockito.Mockito.verifyNoInteractions(recorder);

        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        assertThat(service.withAntibodies(other, hospitalId).getSevereTransfusionReaction()).isFalse();
        // A list read does not look: null, never « none ».
        assertThat(service.of(patient, hospitalId).getSevereTransfusionReaction()).isNull();
    }

    @Test
    @DisplayName("QA D4151 review: a severe reaction recorded at A shows at B, with A's name, and the read is accounted")
    void aSevereReactionFollowsThePatientToAnotherHospital() {
        com.example.hms.service.recordaccess.CrossHospitalReachRecorder recorder =
            mock(com.example.hms.service.recordaccess.CrossHospitalReachRecorder.class);
        com.example.hms.repository.TransfusionReactionRepository reactions = reactionsWired(recorder);
        com.example.hms.model.Hospital atA = new com.example.hms.model.Hospital();
        atA.setId(UUID.randomUUID());
        atA.setName("Centre Test A");
        when(reactions.findFirstByPatient_IdAndSeverityInOrderByOnsetAtDesc(org.mockito.ArgumentMatchers.eq(patient.getId()), any()))
            .thenReturn(Optional.of(haemolyticAt(atA, LocalDateTime.of(2026, 11, 27, 5, 37))));

        BloodGroupStatusDTO atB = service.withAntibodies(patient, hospitalId);

        assertThat(atB.getSevereTransfusionReaction()).isTrue();
        assertThat(atB.getSevereTransfusionReactionHospitalName()).isEqualTo("Centre Test A");
        org.mockito.Mockito.verify(recorder).recordReach(org.mockito.ArgumentMatchers.eq(patient.getId()),
            org.mockito.ArgumentMatchers.eq(hospitalId), any(), any(),
            org.mockito.ArgumentMatchers.eq(java.util.Map.of(atA.getId().toString(), 1L)),
            org.mockito.ArgumentMatchers.eq(SevereTransfusionReactionLookup.REACH_DESCRIPTION));
        // A proxy's copy never carries it.
        BloodGroupStatusDTO proxy = PatientBloodGroupStatusService.forProxy(atB);
        assertThat(proxy.getSevereTransfusionReaction()).isNull();
        assertThat(proxy.getSevereTransfusionReactionHospitalName()).isNull();
    }

    @Test
    @DisplayName("Rex P2: a proxy's copy keeps the value and the disagreement, drops the provenance and the antibody")
    void forProxy() {
        BloodGroupStatusDTO full = BloodGroupStatusDTO.builder().declared("A+").confirmed("B+").discrepancy(true)
            .confirmedAt(groupedAt).confirmedHospitalName("Centre Medical Test").alloImmunised(true)
            .antibodySpecificities("anti-E").build();

        BloodGroupStatusDTO proxy = PatientBloodGroupStatusService.forProxy(full);

        assertThat(proxy.getDeclared()).isEqualTo("A+");
        assertThat(proxy.getConfirmed()).isEqualTo("B+");
        assertThat(proxy.isDiscrepancy()).isTrue();
        assertThat(proxy.getConfirmedAt()).isNull();
        assertThat(proxy.getConfirmedHospitalName()).isNull();
        assertThat(proxy.getAlloImmunised()).isNull();
        assertThat(proxy.getAntibodySpecificities()).isNull();
        assertThat(PatientBloodGroupStatusService.forProxy(null)).isNull();
    }

    @Test
    @DisplayName("the label is the chart's notation")
    void label() {
        assertThat(PatientBloodGroupStatusService.label(AboGroup.AB, RhFactor.NEGATIVE)).isEqualTo("AB-");
        assertThat(PatientBloodGroupStatusService.label(AboGroup.O, RhFactor.POSITIVE)).isEqualTo("O+");
        assertThat(PatientBloodGroupStatusService.label(null, RhFactor.POSITIVE)).isNull();
    }
}
