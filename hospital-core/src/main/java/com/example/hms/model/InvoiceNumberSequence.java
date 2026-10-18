package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * The last invoice number handed out for one hospital in one calendar year
 * (QA D94). Read under a row lock by {@code InvoiceNumberAllocator}, so two
 * concurrent creates at one hospital never receive the same number.
 */
@Entity
@Table(name = "invoice_number_sequences", schema = "billing",
    uniqueConstraints = @UniqueConstraint(name = "uk_ins_hospital_year",
        columnNames = {"hospital_id", "sequence_year"}))
@Getter
@Setter
@NoArgsConstructor
public class InvoiceNumberSequence extends BaseEntity {

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "sequence_year", nullable = false)
    private Integer sequenceYear;

    @Column(name = "last_value", nullable = false)
    private Long lastValue;
}
