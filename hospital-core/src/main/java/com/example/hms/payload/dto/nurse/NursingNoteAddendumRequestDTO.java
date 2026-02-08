package com.example.hms.payload.dto.nurse;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
public class NursingNoteAddendumRequestDTO {

    @NotBlank
    @Size(max = 4000)
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mmXXX")
    private OffsetDateTime eventOccurredAt;

    @NotNull
    private Boolean attestAccuracy;

    @NotNull
    private Boolean attestNoAbbreviations;
}
