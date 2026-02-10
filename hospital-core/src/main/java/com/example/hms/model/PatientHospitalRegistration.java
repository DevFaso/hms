package com.example.hms.model;

import com.example.hms.enums.PatientStayStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "patient_hospital_registrations",
    schema = "clinical",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_phr_patient_hospital", columnNames = {"patient_id", "hospital_id"}),
    @UniqueConstraint(name = "uq_phr_mrn_hospital", columnNames = {"mrn", "hospital_id"})
    },
    indexes = {
        @Index(name = "idx_phr_patient", columnList = "patient_id"),
        @Index(name = "idx_phr_hospital", columnList = "hospital_id"),
        @Index(name = "idx_phr_active", columnList = "is_active")
    }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"patient", "hospital"})
public class PatientHospitalRegistration extends BaseEntity {

    @NotBlank @Size(max = 50)
    @Column(name = "mrn", nullable = false, length = 50)
    private String mrn;

    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_phr_patient"))
    private Patient patient;

    @Column(name = "registration_date", nullable = false)
    private LocalDate registrationDate;

    @Size(max = 255)
    @Column(name = "patient_name", length = 255)
    private String patientFullName;

    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_phr_hospital"))
    private Hospital hospital;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "stay_status", nullable = false, length = 40)
    @Builder.Default
    private PatientStayStatus stayStatus = PatientStayStatus.ADMITTED;

    @Column(name = "stay_status_updated_at")
    private LocalDateTime stayStatusUpdatedAt;

    @Size(max = 30)
    @Column(name = "current_room", length = 30)
    private String currentRoom;

    @Size(max = 30)
    @Column(name = "current_bed", length = 30)
    private String currentBed;

    @Size(max = 150)
    @Column(name = "attending_physician_name", length = 150)
    private String attendingPhysicianName;

    @Column(name = "ready_by_staff_id")
    private UUID readyByStaffId;

    @Size(max = 1000)
    @Column(name = "ready_for_discharge_note", length = 1000)
    private String readyForDischargeNote;

    public synchronized void setStayStatus(PatientStayStatus stayStatus) {
        if (stayStatus != null && stayStatus != this.stayStatus) {
            this.stayStatusUpdatedAt = LocalDateTime.now();
        }
        this.stayStatus = stayStatus;
    }

    public void markReadyForDischarge(UUID staffId, String note) {
        this.readyByStaffId = staffId;
        this.readyForDischargeNote = safeTrim(note);
        setStayStatus(PatientStayStatus.READY_FOR_DISCHARGE);
    }

    public void clearReadyForDischarge() {
        this.readyByStaffId = null;
        this.readyForDischargeNote = null;
        setStayStatus(PatientStayStatus.ADMITTED);
    }

    public void markDischarged() {
        this.readyByStaffId = null;
        this.readyForDischargeNote = null;
        setStayStatus(PatientStayStatus.DISCHARGED);
        this.active = false;
    }

    public PatientHospitalRegistration(Patient patient, Hospital hospital) {
        this.patient = patient;
        this.hospital = hospital;
        this.active  = true;
        this.stayStatus = PatientStayStatus.ADMITTED;
        this.stayStatusUpdatedAt = LocalDateTime.now();
    }

    @PrePersist
    @PreUpdate
    private void normalize() {
        ensureTemporalDefaults();
        ensurePatientFullName();
        normalizeTextualFields();
    }

    private void ensureTemporalDefaults() {
        if (registrationDate == null) registrationDate = LocalDate.now();
        if (stayStatus == null) stayStatus = PatientStayStatus.ADMITTED;
        if (stayStatusUpdatedAt == null) stayStatusUpdatedAt = LocalDateTime.now();
    }

    private void ensurePatientFullName() {
        if (patientFullName != null || patient == null) {
            return;
        }
        String f = safeTrim(patient.getFirstName());
        String m = safeTrim(patient.getMiddleName());
        String l = safeTrim(patient.getLastName());
        String full = ((f == null ? "" : f) + (m == null ? "" : " " + m) + (l == null ? "" : " " + l)).trim();
        patientFullName = full.isEmpty() ? null : full;
    }

    private void normalizeTextualFields() {
        mrn = safeTrim(mrn);
        currentRoom = safeTrim(currentRoom);
        currentBed = safeTrim(currentBed);
        attendingPhysicianName = safeTrim(attendingPhysicianName);
        readyForDischargeNote = safeTrim(readyForDischargeNote);
    }

    private String safeTrim(String value) {
        return value == null ? null : value.trim();
    }
}
