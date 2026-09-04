package com.example.hms.model;

import com.example.hms.enums.RoiRequestStatus;
import com.example.hms.enums.RoiRequesterType;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One release-of-information request (Tier 2 item 39b): a patient or an
 * authorised third party formally asks for a copy of the record; staff
 * triage and fulfil or deny. Fulfilment is itself a DISCLOSURE — the
 * service emits a {@code PATIENT_EXPORT} audit row keyed by patient, which
 * is exactly what item 39's disclosure accounting whitelists as
 * COPY_RELEASED, so every fulfilled request appears on the patient's own
 * "Who Viewed My Records" report with no further wiring.
 *
 * <p>Requester identity, purpose, scope and the decision note are
 * patient-specific narrative — encrypted at rest.
 */
@Entity
@Table(
    name = "roi_requests",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_roi_patient",  columnList = "patient_id"),
        @Index(name = "idx_roi_worklist", columnList = "hospital_id, status, requested_on")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "decidedBy"})
public class RoiRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_roi_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_roi_hospital"))
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "requester_type", nullable = false, length = 20)
    private RoiRequesterType requesterType;

    /** Who is asking — the third party's name, or the patient's own. Encrypted narrative. */
    @Size(max = 200)
    @Column(name = "requester_name", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String requesterName;

    /** Where the copy goes / how to reach the requester. Encrypted narrative. */
    @Size(max = 500)
    @Column(name = "requester_contact", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String requesterContact;

    /** Why the records are requested. Encrypted narrative. */
    @Size(max = 500)
    @Column(name = "purpose", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String purpose;

    /** Which part of the record ("full record", "labs 2025", ...). Encrypted narrative. */
    @Size(max = 500)
    @Column(name = "scope_description", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String scopeDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RoiRequestStatus status = RoiRequestStatus.PENDING;

    /** May predate the row for requests received on paper. */
    @Column(name = "requested_on", nullable = false)
    private LocalDate requestedOn;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_roi_decided_by"))
    private Staff decidedBy;

    /** Required on DENY — the refusal is the outcome the requester is told. Encrypted narrative. */
    @Size(max = 500)
    @Column(name = "decision_note", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String decisionNote;

    /**
     * Optimistic lock (the #549 lesson): two concurrent decisions on the
     * same request must not both report success — the loser gets a clean
     * retryable refusal.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
