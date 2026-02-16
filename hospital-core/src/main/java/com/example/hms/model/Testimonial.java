package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "testimonials")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Testimonial {
    @Id @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;
    @Column(nullable = false)
    private String author;
    private String roleLabel;
    @Column(length = 1000)
    private String text;
    private Integer rating; // 1..5
    private String avatarUrl;
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
