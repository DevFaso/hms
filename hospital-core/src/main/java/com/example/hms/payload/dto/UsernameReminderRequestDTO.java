package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UsernameReminderRequestDTO {
    @NotBlank
    private String identifier;
}
