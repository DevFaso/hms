package com.example.hms.model;

import com.example.hms.enums.InBasketItemStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "in_basket_items",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_inbasket_recipient_status", columnList = "recipient_user_id, status"),
        @Index(name = "idx_inbasket_recipient_created", columnList = "recipient_user_id, created_at"),
        @Index(name = "idx_inbasket_hospital", columnList = "hospital_id"),
        @Index(name = "idx_inbasket_reference", columnList = "reference_id, reference_type")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"recipientUser", "hospital", "encounter", "patient"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InBasketItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_user_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_inbasket_recipient"))
    private User recipientUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_inbasket_hospital"))
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 40)
    private InBasketItemType itemType;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    @Builder.Default
    private InBasketPriority priority = InBasketPriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private InBasketItemStatus status = InBasketItemStatus.UNREAD;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "reference_type", length = 60)
    private String referenceType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id",
        foreignKey = @ForeignKey(name = "fk_inbasket_encounter"))
    private Encounter encounter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id",
        foreignKey = @ForeignKey(name = "fk_inbasket_patient"))
    private Patient patient;

    @Column(name = "patient_name", length = 255)
    private String patientName;

    @Column(name = "ordering_provider_name", length = 255)
    private String orderingProviderName;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by")
    private UUID acknowledgedBy;
}
