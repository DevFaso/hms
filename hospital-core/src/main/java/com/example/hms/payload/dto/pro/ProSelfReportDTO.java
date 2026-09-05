package com.example.hms.payload.dto.pro;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The patient's own view of the screening surface. Deliberately carries no
 * score and no interpretation: a number without a clinician to explain it
 * is not care, and "positive for depression" on a phone screen at 2am is
 * the wrong way to learn it. What the patient IS told: whether the care
 * team will follow up, and — for a self-harm-positive answer — that they
 * have already been alerted.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProSelfReportDTO {

    /** Instruments the patient may answer right now. */
    private List<Available> available;
    /** What the patient has answered before, newest first. */
    private List<Entry> history;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Available {
        private String code;
        private String name;
        private List<String> languages;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Entry {
        private UUID id;
        private String instrumentCode;
        private String instrumentName;
        private LocalDateTime administeredAt;
        /** The care team plans to follow up on this answer set. */
        private boolean followUpPlanned;
        /** The care team was alerted immediately. */
        private boolean careTeamAlerted;
    }
}
