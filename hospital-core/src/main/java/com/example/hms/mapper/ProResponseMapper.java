package com.example.hms.mapper;

import com.example.hms.model.pro.ProInstrument;
import com.example.hms.model.pro.ProResponse;
import com.example.hms.payload.dto.pro.ProResponseDTO;
import com.example.hms.payload.dto.pro.ProSelfReportDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Two views of one response (Tier 2 item 47): the clinician's, with the
 * answers and the score, and the patient's own, with neither — see
 * {@link ProSelfReportDTO} for why.
 */
@Component
@RequiredArgsConstructor
public class ProResponseMapper {

    private static final TypeReference<Map<Integer, Integer>> ANSWERS_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public String answersToJson(Map<Integer, Integer> answers) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(answers));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Answers could not be serialised", ex);
        }
    }

    public Map<Integer, Integer> answersFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, ANSWERS_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored answers could not be read", ex);
        }
    }

    public ProResponseDTO toDto(ProResponse response) {
        ProInstrument instrument = response.getInstrument();
        return ProResponseDTO.builder()
            .id(response.getId())
            .instrumentCode(instrument != null ? instrument.getCode() : null)
            .instrumentName(instrument != null ? instrument.getName() : null)
            .patientId(response.getPatient() != null ? response.getPatient().getId() : null)
            .hospitalId(response.getHospital() != null ? response.getHospital().getId() : null)
            .carePlanId(response.getCarePlan() != null ? response.getCarePlan().getId() : null)
            .source(response.getSource())
            .language(response.getLanguage())
            .administeredAt(response.getAdministeredAt())
            .recordedByUserId(response.getRecordedByUserId())
            .answers(answersFromJson(response.getAnswers()))
            .notes(response.getNotes())
            .totalScore(response.getTotalScore())
            .maxScore(response.getMaxScore())
            .instrumentVersion(response.getInstrumentVersion())
            .answeredItems(response.getAnsweredItems())
            .totalItems(response.getTotalItems())
            .complete(response.isComplete())
            .screenPositive(response.isScreenPositive())
            .criticalItemScore(response.getCriticalItemScore())
            .criticalItemPositive(response.isCriticalItemPositive())
            .escalationLevel(response.getEscalationLevel())
            .acknowledgedAt(response.getAcknowledgedAt())
            .acknowledgedByDisplay(response.getAcknowledgedByDisplay())
            .acknowledgementNote(response.getAcknowledgementNote())
            .build();
    }

    public ProSelfReportDTO.Entry toSelfEntry(ProResponse response) {
        ProInstrument instrument = response.getInstrument();
        return ProSelfReportDTO.Entry.builder()
            .id(response.getId())
            .instrumentCode(instrument != null ? instrument.getCode() : null)
            .instrumentName(instrument != null ? instrument.getName() : null)
            .administeredAt(response.getAdministeredAt())
            .followUpPlanned(response.isScreenPositive() || response.isCriticalItemPositive())
            // A promise, not an inference: true only once a notification
            // reached somebody. A critical answer nobody could be told about
            // must not read as "your care team was alerted".
            .careTeamAlerted(response.getNotifiedAt() != null)
            .build();
    }
}
