package com.example.hms.mapper;

import com.example.hms.model.PatientVitalSign;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.utility.NewsScoreCalculator;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PatientVitalSignMapper {

    public PatientVitalSignResponseDTO toResponse(PatientVitalSign entity) {
        if (entity == null) {
            return null;
        }

        // NEWS2 is computed on read, never stored — a stored score would go
        // stale the moment the scoring table changed (P3 #25b). Explicitly
        // partial: missing parameters are named, not silently zeroed.
        NewsScoreCalculator.NewsScoreResult news = NewsScoreCalculator.score(entity);

        return PatientVitalSignResponseDTO.builder()
            .id(entity.getId())
            .patientId(entity.getPatient() != null ? entity.getPatient().getId() : null)
            .registrationId(entity.getRegistration() != null ? entity.getRegistration().getId() : null)
            .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
            .hospitalName(entity.getHospital() != null ? entity.getHospital().getName() : null)
            .recordedByStaffId(entity.getRecordedByStaff() != null ? entity.getRecordedByStaff().getId() : null)
            .recordedByAssignmentId(entity.getRecordedByAssignment() != null ? entity.getRecordedByAssignment().getId() : null)
            .recordedByName(resolveRecorderName(entity))
            .source(entity.getSource())
            .temperatureCelsius(entity.getTemperatureCelsius())
            .heartRateBpm(entity.getHeartRateBpm())
            .respiratoryRateBpm(entity.getRespiratoryRateBpm())
            .systolicBpMmHg(entity.getSystolicBpMmHg())
            .diastolicBpMmHg(entity.getDiastolicBpMmHg())
            .spo2Percent(entity.getSpo2Percent())
            .bloodGlucoseMgDl(entity.getBloodGlucoseMgDl())
            .weightKg(entity.getWeightKg())
            .heightCm(entity.getHeightCm())
            .headCircumferenceCm(entity.getHeadCircumferenceCm())
            .bodyPosition(entity.getBodyPosition())
            .notes(entity.getNotes())
            .clinicallySignificant(entity.isClinicallySignificant())
            .onOxygen(entity.getOnOxygen())
            .consciousnessLevel(entity.getConsciousnessLevel())
            .newsScore(news.total())
            .newsRiskBand(news.riskBand().name())
            .newsComplete(news.complete())
            .newsMissing(news.missingParameters())
            .recordedAt(entity.getRecordedAt())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    public void applyRequestToEntity(PatientVitalSignRequestDTO request, PatientVitalSign entity) {
        if (request == null || entity == null) {
            return;
        }
        entity.setTemperatureCelsius(request.getTemperatureCelsius());
        entity.setHeartRateBpm(request.getHeartRateBpm());
        entity.setRespiratoryRateBpm(request.getRespiratoryRateBpm());
        entity.setSystolicBpMmHg(request.getSystolicBpMmHg());
        entity.setDiastolicBpMmHg(request.getDiastolicBpMmHg());
        entity.setSpo2Percent(request.getSpo2Percent());
        entity.setBloodGlucoseMgDl(request.getBloodGlucoseMgDl());
        entity.setWeightKg(request.getWeightKg());
        entity.setHeightCm(request.getHeightCm());
        entity.setHeadCircumferenceCm(request.getHeadCircumferenceCm());
        entity.setBodyPosition(request.getBodyPosition());
        entity.setOnOxygen(request.getOnOxygen());
        entity.setConsciousnessLevel(request.getConsciousnessLevel());
        entity.setNotes(request.getNotes());
        entity.setSource(request.getSource());
        entity.setRecordedAt(request.getRecordedAt());
        entity.setClinicallySignificant(Boolean.TRUE.equals(request.getClinicallySignificant()));
    }

    public PatientResponseDTO.VitalSnapshot toSnapshot(PatientVitalSign entity) {
        if (entity == null) {
            return null;
        }
        String bloodPressure = null;
        if (entity.getSystolicBpMmHg() != null && entity.getDiastolicBpMmHg() != null) {
            bloodPressure = entity.getSystolicBpMmHg() + "/" + entity.getDiastolicBpMmHg();
        }
        return PatientResponseDTO.VitalSnapshot.builder()
            .heartRate(entity.getHeartRateBpm())
            .bloodPressure(bloodPressure)
            .systolicBp(entity.getSystolicBpMmHg())
            .diastolicBp(entity.getDiastolicBpMmHg())
            .respiratoryRate(entity.getRespiratoryRateBpm())
            .spo2(entity.getSpo2Percent())
            .bloodGlucose(entity.getBloodGlucoseMgDl())
            .temperature(entity.getTemperatureCelsius())
            .weightKg(entity.getWeightKg())
            .bodyPosition(entity.getBodyPosition())
            .notes(entity.getNotes())
            .clinicallySignificant(entity.isClinicallySignificant())
            .recordedAt(entity.getRecordedAt())
            .build();
    }

    private String resolveRecorderName(PatientVitalSign entity) {
        return Optional.ofNullable(entity.getRecordedByStaff())
            .map(staff -> {
                if (staff.getUser() == null) {
                    return staff.getName();
                }
                String first = staff.getUser().getFirstName();
                String last = staff.getUser().getLastName();
                String combined = ((first != null ? first.trim() : "") + " " + (last != null ? last.trim() : "")).trim();
                return combined.isEmpty() ? staff.getName() : combined;
            })
            .orElse(null);
    }
}
