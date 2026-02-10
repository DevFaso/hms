package com.example.hms.payload.dto.highrisk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HighRiskCareTeamNoteRequestDTO {

    @NotNull
    private LocalDateTime loggedAt;

    @Size(max = 120)
    private String author;

    @NotBlank
    @Size(max = 500)
    private String summary;

    @Size(max = 500)
    private String followUp;
}
