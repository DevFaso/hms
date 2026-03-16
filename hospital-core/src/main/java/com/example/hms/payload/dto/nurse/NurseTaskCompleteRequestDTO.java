package com.example.hms.payload.dto.nurse;

import lombok.Data;

/**
 * Request body for completing a nursing task (MVP 13).
 */
@Data
public class NurseTaskCompleteRequestDTO {

    /** Optional free-text note documenting what was done. */
    private String completionNote;
}
