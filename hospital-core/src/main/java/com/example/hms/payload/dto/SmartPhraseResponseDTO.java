package com.example.hms.payload.dto;

import com.example.hms.enums.SmartPhraseScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Persisted SmartPhrase macro.")
public class SmartPhraseResponseDTO {

    private UUID id;
    private String trigger;
    private String title;
    private String expansion;
    private SmartPhraseScope scope;
    private UUID hospitalId;
    private UUID ownerUserId;
    private String specialty;
    private long usageCount;
    private LocalDateTime lastUsedAt;
}
