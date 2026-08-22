package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.payload.dto.GrowthChartDTO;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Growth-chart series assembly (P3 item 18). The chart plots the patient's
 * OWN trajectory; percentile curves are deliberately absent until a verified
 * WHO reference dataset lands.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GrowthChartServiceImplTest {

    @Mock private PatientRepository patientRepository;
    @Mock private PatientVitalSignRepository vitalSignRepository;
    @Mock private NewbornAssessmentRepository newbornAssessmentRepository;

    private GrowthChartServiceImpl service;

    private UUID patientId;
    private Patient patient;
    private final LocalDate dob = LocalDate.of(2026, 1, 10);

    @BeforeEach
    void setUp() {
        service = new GrowthChartServiceImpl(
            patientRepository, vitalSignRepository, newbornAssessmentRepository);

        patientId = UUID.randomUUID();
        patient = Patient.builder()
            .firstName("Awa").lastName("Kaboré")
            .dateOfBirth(dob)
            .gender("FEMALE")
            .build();
        patient.setId(patientId);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(vitalSignRepository.findGrowthSeries(any(), any())).thenReturn(List.of());
        when(newbornAssessmentRepository
            .findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(any()))
            .thenReturn(Optional.empty());
    }

    private PatientVitalSign vitalsRow(int ageDays, Double weightKg, Double heightCm) {
        PatientVitalSign row = PatientVitalSign.builder()
            .recordedAt(dob.plusDays(ageDays).atTime(9, 0))
            .weightKg(weightKg)
            .heightCm(heightCm)
            .source("NURSE_STATION")
            .build();
        row.setId(UUID.randomUUID());
        return row;
    }

    private NewbornAssessment assessmentWithDelivery(Hospital hospital, int infants, Integer grams) {
        DeliveryRecord delivery = DeliveryRecord.builder()
            .birthDateTime(dob.atTime(4, 30))
            .numberOfInfants(infants)
            .birthWeightGrams(grams)
            .build();
        delivery.setId(UUID.randomUUID());
        NewbornAssessment assessment = NewbornAssessment.builder()
            .hospital(hospital)
            .deliveryRecord(delivery)
            .assessmentTime(dob.atTime(5, 0))
            .build();
        assessment.setId(UUID.randomUUID());
        return assessment;
    }

    @Test
    void assemblesThePatientsOwnTrajectoryWithAgeAtEachPoint() {
        when(vitalSignRepository.findGrowthSeries(patientId, null))
            .thenReturn(List.of(vitalsRow(30, 4.1, null), vitalsRow(90, 5.6, 58.0)));

        GrowthChartDTO chart = service.getGrowthChart(patientId, null);

        assertThat(chart.getDateOfBirth()).isEqualTo(dob);
        assertThat(chart.getGender()).isEqualTo("FEMALE");
        assertThat(chart.getPoints()).hasSize(2);
        assertThat(chart.getPoints().get(0).getAgeDays()).isEqualTo(30);
        assertThat(chart.getPoints().get(0).getWeightKg()).isEqualTo(4.1);
        assertThat(chart.getPoints().get(1).getAgeDays()).isEqualTo(90);
        assertThat(chart.getPoints().get(1).getHeightCm()).isEqualTo(58.0);
        assertThat(chart.getPoints().get(1).getSource()).isEqualTo("NURSE_STATION");
    }

    @Test
    void aRowRecordedBeforeTheDateOfBirthIsNotPlotted() {
        // A data-entry error would fold the age axis back on itself.
        PatientVitalSign impossible = vitalsRow(0, 3.0, null);
        impossible.setRecordedAt(dob.minusDays(3).atTime(9, 0));
        when(vitalSignRepository.findGrowthSeries(patientId, null))
            .thenReturn(List.of(impossible, vitalsRow(10, 3.4, null)));

        GrowthChartDTO chart = service.getGrowthChart(patientId, null);

        assertThat(chart.getPoints()).hasSize(1);
        assertThat(chart.getPoints().get(0).getAgeDays()).isEqualTo(10);
    }

    @Test
    void seedsDayZeroFromASingleInfantDeliveryRecord() {
        when(vitalSignRepository.findGrowthSeries(patientId, null))
            .thenReturn(List.of(vitalsRow(30, 4.1, null)));
        when(newbornAssessmentRepository
            .findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(patientId))
            .thenReturn(Optional.of(assessmentWithDelivery(null, 1, 3200)));

        GrowthChartDTO chart = service.getGrowthChart(patientId, null);

        assertThat(chart.getPoints()).hasSize(2);
        assertThat(chart.getPoints().get(0).getAgeDays()).isZero();
        assertThat(chart.getPoints().get(0).getWeightKg()).isEqualTo(3.2);
        assertThat(chart.getPoints().get(0).getSource()).isEqualTo("DELIVERY");
    }

    @Test
    void refusesToSeedSiblingsFromAMultipleBirthRow() {
        // DeliveryRecord.birthWeightGrams is the PRIMARY infant's weight; copying
        // it onto every sibling's chart would fabricate clinical data.
        when(newbornAssessmentRepository
            .findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(patientId))
            .thenReturn(Optional.of(assessmentWithDelivery(null, 2, 2400)));

        assertThat(service.getGrowthChart(patientId, null).getPoints()).isEmpty();
    }

    @Test
    void aDeliveryWithoutARecordedWeightSeedsNothing() {
        when(newbornAssessmentRepository
            .findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(patientId))
            .thenReturn(Optional.of(assessmentWithDelivery(null, 1, null)));

        assertThat(service.getGrowthChart(patientId, null).getPoints()).isEmpty();
    }

    @Test
    void scopedCallersDoNotSeeAnotherHospitalsBirthRecord() {
        // Cross-hospital clinical data flows through record sharing, not around it.
        Hospital mine = new Hospital();
        mine.setId(UUID.randomUUID());
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        registerAt(mine);
        when(newbornAssessmentRepository
            .findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(patientId))
            .thenReturn(Optional.of(assessmentWithDelivery(other, 1, 3200)));

        GrowthChartDTO chart = service.getGrowthChart(patientId, mine.getId());

        assertThat(chart.getPoints()).isEmpty();
    }

    @Test
    void anUnscopedReadIncludesTheBirthSeedFromAnyHospital() {
        Hospital anywhere = new Hospital();
        anywhere.setId(UUID.randomUUID());
        when(newbornAssessmentRepository
            .findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(patientId))
            .thenReturn(Optional.of(assessmentWithDelivery(anywhere, 1, 3200)));

        assertThat(service.getGrowthChart(patientId, null).getPoints()).hasSize(1);
    }

    @Test
    void aPatientForeignToTheCallersHospitalReadsAsNotFound() {
        UUID foreignHospitalId = UUID.randomUUID();

        assertThatThrownBy(() -> service.getGrowthChart(patientId, foreignHospitalId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anUnknownPatientIsNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(patientRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getGrowthChart(unknownId, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    private void registerAt(Hospital hospital) {
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setHospital(hospital);
        registration.setActive(true);
        patient.setHospitalRegistrations(Set.of(registration));
    }
}
