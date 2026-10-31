package com.example.hms.model.pharmacy;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * QA D1032: the last pharmacy receipt number handed out for one hospital in
 * one calendar year. Read under a row lock by
 * {@code PharmacyReceiptNumberAllocator}, so two concurrent payments never
 * receive the same number (the invoice counter's shape, QA D94).
 */
@Entity
@Table(name = "pharmacy_receipt_sequences", schema = "billing",
    uniqueConstraints = @UniqueConstraint(name = "uk_prs_hospital_year",
        columnNames = {"hospital_id", "sequence_year"}))
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PharmacyReceiptSequence extends BaseEntity {

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "sequence_year", nullable = false)
    private Integer sequenceYear;

    @Column(name = "last_value", nullable = false)
    private Long lastValue;
}
