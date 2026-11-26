package com.example.hms.model.pharmacy;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

/** A physical observation; recording it never changes the stock ledger. */
@Entity
@Table(name = "pharmacy_stock_counts", schema = "clinical", uniqueConstraints =
    @UniqueConstraint(name = "uq_stock_count_request", columnNames = {"hospital_id", "client_request_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StockCount extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItem inventoryItem;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_lot_id")
    private StockLot stockLot;
    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;
    @Column(name = "book_quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal bookQuantity;
    /** Null on historical observations: they must be recounted before an adjustment. */
    @Column(name = "book_item_revision")
    private Long itemRevision;
    @Column(name = "book_lot_revision")
    private Long lotRevision;
    @Column(name = "counted_quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal countedQuantity;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal variance;
    @Column(length = 60)
    private String unit;
    @Column(nullable = false, length = 300)
    private String reason;
    @Column(name = "medication_name_fr", length = 255)
    private String medicationNameFr;
    @Column(name = "medication_name_en", length = 255)
    private String medicationNameEn;
    @Column(name = "lot_number", length = 120)
    private String lotNumber;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recorded_by_user_id", nullable = false)
    private User recordedBy;
    @Column(name = "recorded_by_name", length = 201)
    private String recordedByName;
    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applied_by_user_id")
    private User appliedBy;
    @Column(name = "applied_by_name", length = 201)
    private String appliedByName;
    @Column(name = "same_person_confirmation")
    private Boolean samePersonConfirmation;
    @Column(name = "adjustment_id", unique = true)
    private UUID adjustmentId;
}
