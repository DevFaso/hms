package com.example.hms.payload.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor
public class ArticleResponseDTO {
    private UUID id;
    private String title;
    private String excerpt;
    private String image;
    private String author;
    private OffsetDateTime publishedAt;
}
