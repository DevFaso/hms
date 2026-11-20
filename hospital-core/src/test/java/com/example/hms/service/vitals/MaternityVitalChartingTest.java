package com.example.hms.service.vitals;

import com.example.hms.enums.LaborAlertSeverity;
import com.example.hms.enums.LaborAlertType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.labor.LaborPartographEntry;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.model.postpartum.PostpartumObservation;
import com.example.hms.repository.PatientVitalSignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("QA D2580: maternity readings are charted where the chart reads")
class MaternityVitalChartingTest {

    private PatientVitalSignRepository repository;
    private VitalSignJudgement judgement;
    private MaternityVitalCharting charting;
    private Patient mother;
    private Hospital hospital;
    private final LocalDateTime at = LocalDateTime.of(2026, 11, 20, 0, 30);

    @BeforeEach
    void setUp() {
        repository = mock(PatientVitalSignRepository.class);
        judgement = mock(VitalSignJudgement.class);
        when(repository.save(any(PatientVitalSign.class))).thenAnswer(inv -> inv.getArgument(0));
        charting = new MaternityVitalCharting(repository, judgement);
        mother = new Patient();
        mother.setId(UUID.randomUUID());
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
    }

    @Test
    void partographMaternalVitalsAreChartedOnTheMotherFlaggedByTheirAlert() {
        LaborPartographEntry entry = LaborPartographEntry.builder().patient(mother).hospital(hospital)
            .observationTime(at).pulseBpm(118).systolicBpMmHg(162).diastolicBpMmHg(112).temperatureCelsius(38.4)
            .fetalHeartRateBpm(100).build();
        entry.addAlert(LaborAlertType.MATERNAL_VITALS, LaborAlertSeverity.URGENT, "c", "m", "t");

        PatientVitalSign vital = charting.chartLaborPartographEntry(entry);

        assertThat(vital.getPatient()).isSameAs(mother);
        assertThat(vital.getSource()).isEqualTo(MaternityVitalCharting.SOURCE_LABOR);
        assertThat(vital.getRecordedAt()).isEqualTo(at);
        assertThat(vital.getSystolicBpMmHg()).isEqualTo(162);
        assertThat(vital.getDiastolicBpMmHg()).isEqualTo(112);
        assertThat(vital.getHeartRateBpm()).isEqualTo(118);
        assertThat(vital.getTemperatureCelsius()).isEqualTo(38.4);
        assertThat(vital.isClinicallySignificant()).isTrue();
        verify(judgement).judge(vital);
        // NEWS2 escalates as for every vitals write, without the judgement: the maternity service
        // raised its own severe-pressure and newborn alerts, which are not sent twice.
        verify(judgement).escalate(org.mockito.ArgumentMatchers.eq(vital), org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(mother.getId()), org.mockito.ArgumentMatchers.eq(hospital.getId()),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
            // Review of #1237: an URGENT maternity alert already paged for this reading: NEWS2 does not page twice.
            org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void aFetalOnlyEntryChartsNothingOnTheMother() {
        LaborPartographEntry entry = LaborPartographEntry.builder().patient(mother).hospital(hospital)
            .observationTime(at).fetalHeartRateBpm(100).cervicalDilationCm(6).build();

        assertThat(charting.chartLaborPartographEntry(entry)).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void postpartumVitalsAreCharted() {
        PostpartumObservation obs = PostpartumObservation.builder().patient(mother).hospital(hospital)
            .observationTime(at).systolicBpMmHg(162).diastolicBpMmHg(104).pulseBpm(96).respirationsPerMin(20)
            .temperatureCelsius(37.2).painScore(3).build();

        PatientVitalSign vital = charting.chartPostpartumObservation(obs);

        ArgumentCaptor<PatientVitalSign> saved = ArgumentCaptor.forClass(PatientVitalSign.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(vital);
        assertThat(vital.getSource()).isEqualTo(MaternityVitalCharting.SOURCE_POSTPARTUM);
        assertThat(vital.getSystolicBpMmHg()).isEqualTo(162);
        assertThat(vital.getRespiratoryRateBpm()).isEqualTo(20);
        assertThat(vital.getPainScore()).isEqualTo(3);
    }

    @Test
    void newbornVitalsAreChartedOnTheBabyOnly() {
        Patient baby = new Patient();
        baby.setId(UUID.randomUUID());
        NewbornAssessment assessment = NewbornAssessment.builder().patient(baby).hospital(hospital)
            .assessmentTime(at).temperatureCelsius(35.4).heartRateBpm(182).respirationsPerMin(68)
            .oxygenSaturationPercent(89).build();

        PatientVitalSign vital = charting.chartNewbornAssessment(assessment, true);

        assertThat(vital.getPatient()).isSameAs(baby);
        assertThat(vital.getSource()).isEqualTo(MaternityVitalCharting.SOURCE_NEWBORN);
        assertThat(vital.getSpo2Percent()).isEqualTo(89);
        assertThat(vital.getHeartRateBpm()).isEqualTo(182);
        assertThat(vital.getRespiratoryRateBpm()).isEqualTo(68);
    }

    @Test
    void aNewbornAssessmentLeftOnTheMothersChartIsNotChartedAsHerVitals() {
        NewbornAssessment assessment = NewbornAssessment.builder().patient(mother).hospital(hospital)
            .assessmentTime(at).heartRateBpm(182).build();

        assertThat(charting.chartNewbornAssessment(assessment, false)).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void review1237_onlyCautionOrUrgentAlertsFlagTheRow_andNews2StillPagesWithoutAnUrgentOne() {
        LaborPartographEntry info = LaborPartographEntry.builder().patient(mother).hospital(hospital)
            .observationTime(at).systolicBpMmHg(118).build();
        info.addAlert(LaborAlertType.GENERAL, LaborAlertSeverity.INFO, "c", "m", "t");
        assertThat(charting.chartLaborPartographEntry(info).isClinicallySignificant()).isFalse();

        LaborPartographEntry caution = LaborPartographEntry.builder().patient(mother).hospital(hospital)
            .observationTime(at).systolicBpMmHg(88).pulseBpm(132).build();
        caution.addAlert(LaborAlertType.MATERNAL_VITALS, LaborAlertSeverity.CAUTION, "c", "m", "t");
        PatientVitalSign vital = charting.chartLaborPartographEntry(caution);
        assertThat(vital.isClinicallySignificant()).isTrue();
        // No URGENT alert covered either reading (a low pressure and fast pulse): the NEWS2 page fires (safety net).
        verify(judgement, times(2)).escalate(any(), org.mockito.ArgumentMatchers.isNull(),
            any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void review1237_anApgarNoticeNeitherFlagsNorCoversTheNewbornRow() {
        Patient baby = new Patient();
        baby.setId(UUID.randomUUID());
        NewbornAssessment assessment = NewbornAssessment.builder().patient(baby).hospital(hospital)
            .assessmentTime(at).heartRateBpm(140).build();
        assessment.addAlert(com.example.hms.model.neonatal.NewbornAssessmentAlert.builder()
            .type(com.example.hms.enums.NewbornAlertType.APGAR)
            .severity(com.example.hms.enums.NewbornAlertSeverity.URGENT).code("apgar").message("m").build());
        PatientVitalSign vital = charting.chartNewbornAssessment(assessment, true);
        assertThat(vital.isClinicallySignificant()).isFalse();
        verify(judgement).escalate(org.mockito.ArgumentMatchers.eq(vital), org.mockito.ArgumentMatchers.isNull(),
            any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void review1237_aCorrectionRetiresTheChartedRowOfTheObservationItSupersedes() {
        PostpartumObservation original = PostpartumObservation.builder().patient(mother).hospital(hospital)
            .observationTime(at.minusMinutes(30)).systolicBpMmHg(190).build();
        PatientVitalSign oldRow = PatientVitalSign.builder().patient(mother).build();
        when(repository.findByPatient_IdAndHospital_IdAndSourceAndRecordedAt(mother.getId(), hospital.getId(),
            MaternityVitalCharting.SOURCE_POSTPARTUM, at.minusMinutes(30))).thenReturn(java.util.List.of(oldRow));
        PostpartumObservation corrected = PostpartumObservation.builder().patient(mother).hospital(hospital)
            .observationTime(at.minusMinutes(30)).systolicBpMmHg(130).supersedesObservation(original).build();

        charting.chartPostpartumObservation(corrected);

        verify(repository).deleteAll(java.util.List.of(oldRow));
    }

    @Test
    void review1237_aCorrectionWithNoChartedRowOrNoTimeDeletesNothing_andAnEmptyRecordChartsNothing() {
        PostpartumObservation untimed = PostpartumObservation.builder().patient(mother).hospital(hospital).build();
        PostpartumObservation corrected = PostpartumObservation.builder().patient(mother).hospital(hospital)
            .observationTime(at).supersedesObservation(untimed).build();
        // The corrected observation records no vital sign: nothing charted, and the untimed original has no row.
        assertThat(charting.chartPostpartumObservation(corrected)).isNull();

        PostpartumObservation original = PostpartumObservation.builder().patient(mother).hospital(hospital)
            .observationTime(at.minusMinutes(10)).build();
        when(repository.findByPatient_IdAndHospital_IdAndSourceAndRecordedAt(mother.getId(), hospital.getId(),
            MaternityVitalCharting.SOURCE_POSTPARTUM, at.minusMinutes(10))).thenReturn(java.util.List.of());
        charting.chartPostpartumObservation(PostpartumObservation.builder().patient(mother).hospital(hospital)
            .observationTime(at).supersedesObservation(original).build());

        verify(repository, org.mockito.Mockito.never()).deleteAll(any());
        verify(repository, org.mockito.Mockito.never()).save(any());
        assertThat(charting.chartPostpartumObservation(null)).isNull();
        assertThat(charting.chartLaborPartographEntry(null)).isNull();
        assertThat(charting.chartNewbornAssessment(null, true)).isNull();
    }

    @Test
    void review1237_theRecordersAssignmentAtAnotherHospitalIsNotCarried() {
        com.example.hms.model.Hospital other = new com.example.hms.model.Hospital();
        other.setId(UUID.randomUUID());
        com.example.hms.model.UserRoleHospitalAssignment elsewhere = new com.example.hms.model.UserRoleHospitalAssignment();
        elsewhere.setHospital(other);
        com.example.hms.model.Staff staff = new com.example.hms.model.Staff();
        staff.setAssignment(elsewhere);
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setUsername("midwife.a");
        staff.setUser(user);
        LaborPartographEntry entry = LaborPartographEntry.builder().patient(mother).hospital(hospital)
            .recordedByStaff(staff).systolicBpMmHg(120).build();

        PatientVitalSign vital = charting.chartLaborPartographEntry(entry);

        assertThat(vital.getRecordedByAssignment()).isNull();
        assertThat(vital.getRecordedAt()).isNotNull();
        verify(judgement).escalate(any(), org.mockito.ArgumentMatchers.isNull(), any(), any(),
            org.mockito.ArgumentMatchers.eq("midwife.a"), any(), org.mockito.ArgumentMatchers.eq(false));
    }
}
