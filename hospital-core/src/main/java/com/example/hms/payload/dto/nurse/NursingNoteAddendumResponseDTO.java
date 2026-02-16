package com.example.hms.payload.dto.nurse;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NursingNoteAddendumResponseDTO {

    private UUID id;

    private UUID noteId;

    private UUID authorUserId;

    private UUID authorStaffId;

    private String authorName;

    private String authorCredentials;

    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mmXXX")
    private OffsetDateTime eventOccurredAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mmXXX")
    private OffsetDateTime documentedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mmXXX")
    private OffsetDateTime signedAt;

    private boolean attestAccuracy;

    private boolean attestNoAbbreviations;
}
