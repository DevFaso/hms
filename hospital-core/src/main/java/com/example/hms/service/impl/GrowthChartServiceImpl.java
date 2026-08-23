package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.payload.dto.GrowthChartDTO;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.service.GrowthChartService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GrowthChartServiceImpl implements GrowthChartService {

    private static final String MSG_PATIENT_NOT_FOUND = "Patient not found with ID: ";

    private final PatientChartAccess patientChartAccess;
    private final PatientVitalSignRepository vitalSignRepository;
    private final NewbornAssessmentRepository newbornAssessmentRepository;

    @Override
    public GrowthChartDTO getGrowthChart(UUID patientId, UUID hospitalId) {
        // 404-not-403 and cross-hospital safe — see PatientChartAccess.
        Patient patient = patientChartAccess.require(patientId, hospitalId);

        LocalDate dob = patient.getDateOfBirth();
        List<GrowthChartDTO.GrowthPoint> points = new ArrayList<>();

        for (PatientVitalSign row : vitalSignRepository.findGrowthSeries(patientId, hospitalId)) {
            long ageDays = ageDaysAt(dob, row.getRecordedAt());
            if (ageDays < 0) {
                // Recorded before the date of birth — a data-entry error that
                // would fold the chart's x-axis back on itself; skip, don't plot.
                continue;
            }
            points.add(GrowthChartDTO.GrowthPoint.builder()
                .recordedAt(row.getRecordedAt())
                .ageDays(ageDays)
                .weightKg(row.getWeightKg())
                .heightCm(row.getHeightCm())
                .headCircumferenceCm(row.getHeadCircumferenceCm())
                .source(row.getSource())
                .build());
        }

        birthWeightSeed(patientId, hospitalId, dob).ifPresent(points::add);
        points.sort(Comparator.comparingLong(GrowthChartDTO.GrowthPoint::getAgeDays));

        return GrowthChartDTO.builder()
            .patientId(patientId)
            .dateOfBirth(dob)
            .gender(patient.getGender())
            .points(points)
            .build();
    }

    /**
     * The chart's day-zero point, from the linked delivery record. Two guards:
     * single-infant deliveries only — {@code birthWeightGrams} is documented as
     * the primary infant's weight, so seeding every sibling's chart from the
     * same row would fabricate data — and, for scoped callers, only a delivery
     * documented at the caller's hospital (cross-hospital clinical data flows
     * through record sharing, not around it).
     */
    private java.util.Optional<GrowthChartDTO.GrowthPoint> birthWeightSeed(UUID patientId,
                                                                           UUID hospitalId,
                                                                           LocalDate dob) {
        return newbornAssessmentRepository
            .findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(patientId)
            .filter(assessment -> hospitalId == null
                || (assessment.getHospital() != null
                    && hospitalId.equals(assessment.getHospital().getId())))
            .map(NewbornAssessment::getDeliveryRecord)
            .filter(delivery -> delivery.getNumberOfInfants() == 1)
            .filter(delivery -> delivery.getBirthWeightGrams() != null)
            .map(delivery -> toBirthPoint(delivery, dob))
            .filter(point -> point.getAgeDays() >= 0);
    }

    private GrowthChartDTO.GrowthPoint toBirthPoint(DeliveryRecord delivery, LocalDate dob) {
        return GrowthChartDTO.GrowthPoint.builder()
            .recordedAt(delivery.getBirthDateTime())
            .ageDays(ageDaysAt(dob, delivery.getBirthDateTime()))
            .weightKg(delivery.getBirthWeightGrams() / 1000.0)
            .source("DELIVERY")
            .build();
    }

    private long ageDaysAt(LocalDate dob, LocalDateTime at) {
        if (dob == null || at == null) {
            return -1;
        }
        return ChronoUnit.DAYS.between(dob, at.toLocalDate());
    }
}
