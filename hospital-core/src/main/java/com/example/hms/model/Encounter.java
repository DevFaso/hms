package com.example.hms.model;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.model.encounter.EncounterNote;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(
    name = "encounters",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_encounter_patient", columnList = "patient_id"),
        @Index(name = "idx_encounter_staff", columnList = "staff_id"),
        @Index(name = "idx_encounter_hospital", columnList = "hospital_id"),
        @Index(name = "idx_encounter_date", columnList = "encounter_date"),
        @Index(name = "idx_encounter_status", columnList = "status")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"patient", "staff", "hospital", "appointment", "department", "assignment", "encounterTreatments", "encounterNote"})
public class Encounter extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_encounter_patient"))
    private Patient patient;

    @Builder.Default
    @OneToMany(mappedBy = "encounter", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<EncounterTreatment> encounterTreatments = new HashSet<>();

    @OneToOne(mappedBy = "encounter", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private EncounterNote encounterNote;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_encounter_staff"))
    private Staff staff;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_encounter_hospital"))
    private Hospital hospital;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "encounter_type", nullable = false, length = 50)
    private EncounterType encounterType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", foreignKey = @ForeignKey(name = "fk_encounter_appointment"))
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", foreignKey = @ForeignKey(name = "fk_encounter_department"))
    private Department department;

    @NotNull
    @Column(name = "encounter_date", nullable = false)
    private LocalDateTime encounterDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    @Builder.Default
    private EncounterStatus status = EncounterStatus.IN_PROGRESS;

    @Column(name = "notes", length = 2048)
    private String notes;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;
    
        // Audit fields
        @Column(name = "created_by", length = 100)
        private String createdBy;
    
        @Column(name = "updated_by", length = 100)
        private String updatedBy;
    
        // Extensibility: custom fields for future-proofing
        @Convert(converter = com.example.hms.utility.JsonMapConverter.class)
        @Column(name = "extra_fields", columnDefinition = "TEXT")
        private java.util.Map<String, Object> extraFields;


    public String generateEncounterCode() {
        // Example: ENC-<YYYYMMDD>-<6 chars>
        String date = java.time.LocalDate.now().toString().replace("-", "");
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "ENC-" + date + "-" + suffix;
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_encounter_assignment"))
    private UserRoleHospitalAssignment assignment;

    @Version
    private Long version;

    /* ---------- Helpers ---------- */
    @Transient
    public String getDescription() { return this.getNotes(); }

    @Transient
    public EncounterType getType() {
        return this.getEncounterType();
    }

    @Transient
    public java.time.LocalTime getEncounterTime() {
        return this.encounterDate != null ? this.encounterDate.toLocalTime() : null;
    }

    public void setEncounterNote(EncounterNote note) {
        this.encounterNote = note;
        if (note != null) {
            note.setEncounter(this);
            if (this.patient != null) {
                note.setPatient(this.patient);
            }
            if (this.hospital != null) {
                note.setHospital(this.hospital);
            }
        }
    }

    /* ---------- Integrity ---------- */
    @PrePersist
    @PreUpdate
    private void validate() {
        if (encounterDate == null) encounterDate = LocalDateTime.now();

        // Staff must belong to the encounter hospital
        if (staff == null || staff.getHospital() == null || hospital == null
            || !Objects.equals(staff.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Encounter.staff must belong to encounter.hospital");
        }

        // Assignment must be hospital-scoped and match encounter hospital
        if (assignment == null || assignment.getHospital() == null
            || !Objects.equals(assignment.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Encounter.assignment.hospital must match encounter.hospital");
        }

        // If department set, it must belong to the same hospital
        if (department != null && department.getHospital() != null
            && !Objects.equals(department.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Encounter.department must belong to encounter.hospital");
        }

        // If appointment linked, it must match patient/staff/hospital
        if (appointment != null) {
            if (!Objects.equals(appointment.getHospital().getId(), hospital.getId())) {
                throw new IllegalStateException("Encounter.appointment.hospital must match encounter.hospital");
            }
            if (!Objects.equals(appointment.getPatient().getId(), patient.getId())) {
                throw new IllegalStateException("Encounter.appointment.patient must match encounter.patient");
            }
            if (!Objects.equals(appointment.getStaff().getId(), staff.getId())) {
                throw new IllegalStateException("Encounter.appointment.staff must match encounter.staff");
            }
        }

        if (status == null) status = EncounterStatus.IN_PROGRESS;
    }

}
