package com.example.hms.payload.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor
public class TestimonialResponseDTO {
    private UUID id;
    private String author;
    private String role;
    private String text;
    private Integer rating;
    private String avatar;
    private OffsetDateTime createdAt;
}
