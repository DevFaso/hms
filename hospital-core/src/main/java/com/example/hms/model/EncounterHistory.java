package com.example.hms.model;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "encounter_history", schema = "clinical")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EncounterHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "encounter_id", nullable = false)
    private UUID encounterId;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "changed_by", length = 100)
    private String changedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "encounter_type", length = 50)
    private EncounterType encounterType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 40)
    private EncounterStatus status;

    @Column(name = "encounter_date")
    private LocalDateTime encounterDate;

    @Column(name = "notes", length = 2048)
    private String notes;

    @Column(name = "extra_fields", columnDefinition = "TEXT")
    private String extraFieldsJson;

    @Column(name = "change_type", length = 30)
    private String changeType; // CREATED, UPDATED, DELETED

    @Column(name = "previous_values", columnDefinition = "TEXT")
    private String previousValuesJson;
}
