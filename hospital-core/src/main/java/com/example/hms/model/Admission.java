package com.example.hms.model;

import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.DischargeDisposition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hospital Admission entity tracking inpatient admissions
 */
@Entity
@Table(name = "admissions", indexes = {
    @Index(name = "idx_admission_patient", columnList = "patient_id"),
    @Index(name = "idx_admission_hospital", columnList = "hospital_id"),
    @Index(name = "idx_admission_status", columnList = "status"),
    @Index(name = "idx_admission_date", columnList = "admission_date_time"),
    @Index(name = "idx_admission_provider", columnList = "admitting_provider_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Admission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Patient being admitted
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    /**
     * Hospital where patient is admitted
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    /**
     * Primary admitting provider (doctor)
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admitting_provider_id", nullable = false)
    private Staff admittingProvider;

    /**
     * Department/ward where patient is admitted
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    /**
     * Room/bed assignment (optional)
     */
    @Column(length = 50)
    private String roomBed;

    /**
     * Type of admission
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdmissionType admissionType;

    /**
     * Current status of admission
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdmissionStatus status = AdmissionStatus.PENDING;

    /**
     * Patient acuity level (severity)
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AcuityLevel acuityLevel;

    /**
     * Date and time of admission
     */
    @Column(nullable = false)
    private LocalDateTime admissionDateTime;

    /**
     * Expected discharge date (if known)
     */
    private LocalDateTime expectedDischargeDateTime;

    /**
     * Actual discharge date and time
     */
    private LocalDateTime actualDischargeDateTime;

    /**
     * Chief complaint/reason for admission
     */
    @Column(nullable = false, length = 500)
    private String chiefComplaint;

    /**
     * Primary diagnosis (ICD-10 code)
     */
    @Column(length = 20)
    private String primaryDiagnosisCode;

    /**
     * Primary diagnosis description
     */
    @Column(length = 500)
    private String primaryDiagnosisDescription;

    /**
     * Secondary diagnoses as JSON array
     * Example: [{"code": "I10", "description": "Essential hypertension"}, ...]
     */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, String>> secondaryDiagnoses = new ArrayList<>();

    /**
     * Admission source (e.g., "Emergency Department", "Direct from clinic", "Transfer from SNF")
     */
    @Column(length = 200)
    private String admissionSource;

    /**
     * Order sets applied during admission
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "admission_applied_order_sets",
        joinColumns = @JoinColumn(name = "admission_id"),
        inverseJoinColumns = @JoinColumn(name = "order_set_id")
    )
    private List<AdmissionOrderSet> appliedOrderSets = new ArrayList<>();

    /**
     * Custom orders/instructions not part of an order set
     */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> customOrders = new ArrayList<>();

    /**
     * Clinical notes on admission
     */
    @Column(length = 2000)
    private String admissionNotes;

    /**
     * Attending physician (may differ from admitting provider)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attending_physician_id")
    private Staff attendingPhysician;

    /**
     * Consulting physicians (stored as JSON array of staff IDs and names)
     */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, String>> consultingPhysicians = new ArrayList<>();

    /**
     * Discharge disposition (where patient goes after discharge)
     */
    @Enumerated(EnumType.STRING)
    private DischargeDisposition dischargeDisposition;

    /**
     * Discharge summary
     */
    @Column(length = 5000)
    private String dischargeSummary;

    /**
     * Discharge instructions
     */
    @Column(length = 2000)
    private String dischargeInstructions;

    /**
     * Discharging provider
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discharging_provider_id")
    private Staff dischargingProvider;

    /**
     * Follow-up appointments scheduled (as JSON)
     */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> followUpAppointments = new ArrayList<>();

    /**
     * Insurance authorization number
     */
    @Column(length = 100)
    private String insuranceAuthNumber;

    /**
     * Length of stay in days (calculated)
     */
    private Integer lengthOfStayDays;

    /**
     * Additional metadata (e.g., isolation precautions, fall risk, etc.)
     */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // Business methods

    /**
     * Calculate length of stay
     */
    public void calculateLengthOfStay() {
        if (admissionDateTime != null && actualDischargeDateTime != null) {
            long days = java.time.Duration.between(admissionDateTime, actualDischargeDateTime).toDays();
            this.lengthOfStayDays = (int) days;
        }
    }

    /**
     * Mark admission as active
     */
    public void activate() {
        this.status = AdmissionStatus.ACTIVE;
    }

    /**
     * Discharge the patient
     */
    public void discharge(DischargeDisposition disposition, String summary, String instructions, Staff provider) {
        this.status = AdmissionStatus.DISCHARGED;
        this.actualDischargeDateTime = LocalDateTime.now();
        this.dischargeDisposition = disposition;
        this.dischargeSummary = summary;
        this.dischargeInstructions = instructions;
        this.dischargingProvider = provider;
        calculateLengthOfStay();
    }

    /**
     * Cancel admission
     */
    public void cancel() {
        this.status = AdmissionStatus.CANCELLED;
    }

    /**
     * Check if admission is active
     */
    public boolean isActive() {
        return status == AdmissionStatus.ACTIVE || status == AdmissionStatus.ON_LEAVE;
    }

    /**
     * Apply an order set to this admission
     */
    public void applyOrderSet(AdmissionOrderSet orderSet) {
        if (this.appliedOrderSets == null) {
            this.appliedOrderSets = new ArrayList<>();
        }
        if (!this.appliedOrderSets.contains(orderSet)) {
            this.appliedOrderSets.add(orderSet);
        }
    }

    /**
     * Add a consulting physician
     */
    public void addConsultingPhysician(UUID staffId, String staffName, String specialty) {
        if (this.consultingPhysicians == null) {
            this.consultingPhysicians = new ArrayList<>();
        }
        this.consultingPhysicians.add(Map.of(
            "staffId", staffId.toString(),
            "staffName", staffName,
            "specialty", specialty
        ));
    }

    /**
     * Add a secondary diagnosis
     */
    public void addSecondaryDiagnosis(String code, String description) {
        if (this.secondaryDiagnoses == null) {
            this.secondaryDiagnoses = new ArrayList<>();
        }
        this.secondaryDiagnoses.add(Map.of(
            "code", code,
            "description", description
        ));
    }
}
