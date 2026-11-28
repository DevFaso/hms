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

    @NotBlank(message = "{addendum.validation.contentRequired}")
    @Size(max = 4000, message = "{addendum.validation.contentTooLong4000}")
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mmXXX")
    private OffsetDateTime eventOccurredAt;

    @NotNull(message = "{addendum.validation.attestAccuracyRequired}")
    private Boolean attestAccuracy;

    @NotNull(message = "{addendum.validation.attestNoAbbreviationsRequired}")
    private Boolean attestNoAbbreviations;
}
