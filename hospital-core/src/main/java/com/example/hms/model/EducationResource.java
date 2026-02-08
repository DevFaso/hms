package com.example.hms.model;

import com.example.hms.enums.EducationResourceType;
import jakarta.persistence.*;
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

    @Lob
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EducationResourceType type;

    private String url;

    @Column(nullable = false)
    private String category;
}

