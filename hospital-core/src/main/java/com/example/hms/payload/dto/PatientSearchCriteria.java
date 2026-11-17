package com.example.hms.payload.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.UUID;

/**
 * Aggregates optional filters used when searching for patients.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientSearchCriteria {

    private String mrn;
    private String name;
    private String dateOfBirth;
    private String phone;
    private String email;
    private UUID hospitalId;
    private Boolean active;
    /**
     * Match active and inactive records alike (overrides {@link #active}). The
     * clinical pickers set it: an admitted patient whose record is switched off
     * still needs a task, a handoff or a discharge summary (QA D80).
     */
    private boolean includeInactive;
}
