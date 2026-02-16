package com.example.hms.model;

import com.example.hms.enums.EducationResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Legacy entity retained for historical migrations only. Use the rich model in
// com.example.hms.model.education.EducationResource for all new features.
@Entity(name = "LegacyEducationResource")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "education_resources")
public class EducationResource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EducationResourceType type;

    private String url;

    @Column(nullable = false)
    private String category;
}

