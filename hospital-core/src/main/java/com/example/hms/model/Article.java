package com.example.hms.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "articles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Article {
    @Id @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;
    @Column(nullable = false)
    private String title;
    @Column(length = 2000)
    private String content;
    private String author;
    private String imageUrl;
    @Builder.Default
    private OffsetDateTime publishedAt = OffsetDateTime.now();
}
