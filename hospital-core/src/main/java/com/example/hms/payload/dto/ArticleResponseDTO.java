package com.example.hms.payload.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor
public class ArticleResponseDTO {
    private UUID id;
    private String title;
    private String excerpt;
    private String image;
    private String author;
    private OffsetDateTime publishedAt;
}
