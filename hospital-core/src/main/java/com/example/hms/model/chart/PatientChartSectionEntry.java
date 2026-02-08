package com.example.hms.model.chart;

import com.example.hms.enums.DoctorChartSectionType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientChartSectionEntry {

    @Enumerated(EnumType.STRING)
    @Column(name = "section_type", length = 40, nullable = false)
    private DoctorChartSectionType sectionType;

    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "display", length = 255)
    private String display;

    @Column(name = "narrative", length = 2048)
    private String narrative;

    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "severity", length = 50)
    private String severity;

    @Column(name = "source_system", length = 120)
    private String sourceSystem;

    @Column(name = "occurred_on")
    private LocalDate occurredOn;

    @Column(name = "linked_resource_id")
    private UUID linkedResourceId;

    @Column(name = "sensitive_flag")
    private Boolean sensitive;

    @Column(name = "author_notes", length = 2048)
    private String authorNotes;

    @Column(name = "details_json", columnDefinition = "TEXT")
    private String detailsJson;
}
