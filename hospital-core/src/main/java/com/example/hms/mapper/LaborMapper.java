package com.example.hms.mapper;

import com.example.hms.utility.ElapsedTime;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.LaborAlert;
import com.example.hms.model.labor.LaborEpisode;
import com.example.hms.model.labor.LaborPartographEntry;
import com.example.hms.payload.dto.clinical.labor.DeliveryRecordResponseDTO;
import com.example.hms.payload.dto.clinical.labor.LaborAlertDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeResponseDTO;
import com.example.hms.payload.dto.clinical.labor.PartographEntryResponseDTO;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** Hand-written mapper for the Labor & Delivery DTOs (house convention — no MapStruct). */
@Component
public class LaborMapper {

    public LaborEpisodeResponseDTO toEpisodeResponse(LaborEpisode episode, long entryCount, boolean deliveryRecorded) {
        if (episode == null) {
            return null;
        }
        return LaborEpisodeResponseDTO.builder()
            .id(episode.getId())
            .patientId(episode.getPatient() != null ? episode.getPatient().getId() : null)
            .patientName(episode.getPatient() != null ? episode.getPatient().getFullName() : null)
            .hospitalId(episode.getHospital() != null ? episode.getHospital().getId() : null)
            .registrationId(episode.getRegistration() != null ? episode.getRegistration().getId() : null)
            .maternalHistoryId(episode.getMaternalHistory() != null ? episode.getMaternalHistory().getId() : null)
            .admittedByStaffName(episode.getAdmittedByStaff() != null ? episode.getAdmittedByStaff().getFullName() : null)
            .laborOnsetAt(episode.getLaborOnsetAt())
            .admittedAt(episode.getAdmittedAt())
            .membraneStatus(episode.getMembraneStatus() != null ? episode.getMembraneStatus().name() : null)
            .membraneRuptureAt(episode.getMembraneRuptureAt())
            .gestationalAgeWeeks(episode.getGestationalAgeWeeks())
            .gravida(episode.getGravida())
            .para(episode.getPara())
            .activePhaseStartAt(episode.getActivePhaseStartAt())
            .status(episode.getStatus() != null ? episode.getStatus().name() : null)
            .outcome(episode.getOutcome() != null ? episode.getOutcome().name() : null)
            .riskNotes(episode.getRiskNotes())
            .entryCount(entryCount)
            .deliveryRecorded(deliveryRecorded)
            .build();
    }

    public PartographEntryResponseDTO toEntryResponse(LaborPartographEntry entry, LocalDateTime activePhaseStartAt) {
        if (entry == null) {
            return null;
        }
        return PartographEntryResponseDTO.builder()
            .id(entry.getId())
            .episodeId(entry.getEpisode() != null ? entry.getEpisode().getId() : null)
            .patientId(entry.getPatient() != null ? entry.getPatient().getId() : null)
            .observationTime(entry.getObservationTime())
            .documentedAt(entry.getDocumentedAt())
            .lateEntry(entry.isLateEntry())
            .recordedByStaffName(entry.getRecordedByStaff() != null ? entry.getRecordedByStaff().getFullName() : null)
            .fetalHeartRateBpm(entry.getFetalHeartRateBpm())
            .liquorColour(entry.getLiquorColour() != null ? entry.getLiquorColour().name() : null)
            .mouldingDegree(entry.getMouldingDegree() != null ? entry.getMouldingDegree().name() : null)
            .cervicalDilationCm(entry.getCervicalDilationCm())
            .descentFifths(entry.getDescentFifths())
            .contractionsPerTenMinutes(entry.getContractionsPerTenMinutes())
            .contractionDurationSeconds(entry.getContractionDurationSeconds())
            .oxytocinDropsPerMinute(entry.getOxytocinDropsPerMinute())
            .drugsGiven(entry.getDrugsGiven())
            .ivFluids(entry.getIvFluids())
            .pulseBpm(entry.getPulseBpm())
            .systolicBpMmHg(entry.getSystolicBpMmHg())
            .diastolicBpMmHg(entry.getDiastolicBpMmHg())
            .temperatureCelsius(entry.getTemperatureCelsius())
            .urineOutputMl(entry.getUrineOutputMl())
            .urineProtein(entry.getUrineProtein())
            .urineAcetone(entry.getUrineAcetone())
            .notes(entry.getNotes())
            .alerts(mapAlerts(entry.getAlerts()))
            .hoursSinceActivePhaseStart(hoursSince(activePhaseStartAt, entry.getObservationTime()))
            .build();
    }

    public DeliveryRecordResponseDTO toDeliveryResponse(DeliveryRecord record) {
        if (record == null) {
            return null;
        }
        return DeliveryRecordResponseDTO.builder()
            .id(record.getId())
            .episodeId(record.getEpisode() != null ? record.getEpisode().getId() : null)
            .patientId(record.getPatient() != null ? record.getPatient().getId() : null)
            .deliveredByStaffName(record.getDeliveredByStaff() != null ? record.getDeliveredByStaff().getFullName() : null)
            .birthDateTime(record.getBirthDateTime())
            .deliveryMode(record.getDeliveryMode() != null ? record.getDeliveryMode().name() : null)
            .liveBirth(record.isLiveBirth())
            .numberOfInfants(record.getNumberOfInfants())
            .infantSex(record.getInfantSex() != null ? record.getInfantSex().name() : null)
            .birthWeightGrams(record.getBirthWeightGrams())
            .gestationalAgeWeeksAtBirth(record.getGestationalAgeWeeksAtBirth())
            .apgarOneMinute(record.getApgarOneMinute())
            .apgarFiveMinute(record.getApgarFiveMinute())
            .placentaDeliveredAt(record.getPlacentaDeliveredAt())
            .placentaComplete(record.getPlacentaComplete())
            .uterotonicGiven(record.getUterotonicGiven())
            .estimatedBloodLossMl(record.getEstimatedBloodLossMl())
            .perinealTear(record.getPerinealTear() != null ? record.getPerinealTear().name() : null)
            .notes(record.getNotes())
            .alerts(mapAlerts(record.getAlerts()))
            .build();
    }

    private List<LaborAlertDTO> mapAlerts(List<LaborAlert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return List.of();
        }
        return alerts.stream()
            .map(alert -> LaborAlertDTO.builder()
                .type(alert.getType() != null ? alert.getType().name() : null)
                .severity(alert.getSeverity() != null ? alert.getSeverity().name() : null)
                .code(alert.getCode())
                .message(alert.getMessage())
                .triggeredBy(alert.getTriggeredBy())
                .createdAt(alert.getCreatedAt())
                .build())
            .toList();
    }

    private Double hoursSince(LocalDateTime anchor, LocalDateTime observationTime) {
        if (anchor == null || observationTime == null || observationTime.isBefore(anchor)) {
            return null;
        }
        return ElapsedTime.minutesBetween(anchor, observationTime) / 60.0;
    }
}
