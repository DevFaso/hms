package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InstrumentOutboxResponseDTO {

    private UUID id;
    private UUID labOrderId;
    private String messageType;
    private String payload;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
}
