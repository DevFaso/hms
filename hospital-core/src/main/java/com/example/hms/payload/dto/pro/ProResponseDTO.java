package com.example.hms.payload.dto.pro;

import com.example.hms.enums.pro.ProResponseSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** The clinician's view of one administered instrument. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProResponseDTO {

    private UUID id;
    private String instrumentCode;
    private String instrumentName;
    private UUID patientId;
    private UUID hospitalId;
    private UUID carePlanId;
    private ProResponseSource source;
    private String language;
    private LocalDateTime administeredAt;
    private UUID recordedByUserId;
    private Map<Integer, Integer> answers;
    private String notes;
    private int totalScore;
    private int maxScore;
    private int answeredItems;
    private int totalItems;
    private boolean complete;
    private boolean screenPositive;
    private Integer criticalItemScore;
    private boolean criticalItemPositive;
    private int escalationLevel;
    private LocalDateTime acknowledgedAt;
    private String acknowledgedByDisplay;
    private String acknowledgementNote;
}
