package com.example.hms.repository;

import com.example.hms.model.Hospital;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Platform admin rules 2026-11-20, rule 1b: the TOTALS an organisation admin
 * reads about the hospitals of its own organisation.
 *
 * <p>Every query here returns a number (or the hospital's own name and
 * currency), never a row about a person: no patient, no invoice, no note.
 * Each one names ONE hospital; the caller's right to that hospital is
 * {@code UserAccountAccess.organisationHospitalIds}, decided by the service
 * before any of these runs.
 */
public interface OrganisationTotalsRepository extends Repository<Hospital, UUID> {

    /** The clinical hospitals among these ids, by name. A pharmacy or laboratory row is never returned. */
    @Query("SELECT h FROM Hospital h WHERE h.id IN :ids "
        + "AND h.facilityType = com.example.hms.enums.FacilityType.HOSPITAL ORDER BY LOWER(h.name)")
    List<Hospital> findClinicalByIdIn(@Param("ids") Collection<UUID> ids);

    /** Appointments dated in the period at one hospital, whatever their status. */
    @Query("SELECT COUNT(a) FROM Appointment a WHERE a.hospital.id = :hospitalId "
        + "AND a.appointmentDate BETWEEN :from AND :to")
    long countAppointments(@Param("hospitalId") UUID hospitalId,
                           @Param("from") LocalDate from,
                           @Param("to") LocalDate to);

    /** What one hospital invoiced in the period: issued invoices only (no draft, no cancelled one). */
    @Query("SELECT COALESCE(SUM(bi.totalAmount), 0) FROM BillingInvoice bi WHERE bi.hospital.id = :hospitalId "
        + "AND bi.invoiceDate BETWEEN :from AND :to "
        + "AND bi.status NOT IN (com.example.hms.enums.InvoiceStatus.DRAFT, com.example.hms.enums.InvoiceStatus.CANCELLED)")
    BigDecimal sumInvoiced(@Param("hospitalId") UUID hospitalId,
                           @Param("from") LocalDate from,
                           @Param("to") LocalDate to);

    /** What one hospital collected in the period: confirmed payments, as its daily collections count them. */
    @Query("SELECT COALESCE(SUM(pt.amount), 0) FROM PaymentTransaction pt WHERE pt.invoice.hospital.id = :hospitalId "
        + "AND pt.paymentDate BETWEEN :from AND :to "
        + "AND pt.status = com.example.hms.enums.PaymentStatus.CONFIRMED")
    BigDecimal sumCollected(@Param("hospitalId") UUID hospitalId,
                            @Param("from") LocalDate from,
                            @Param("to") LocalDate to);

    /**
     * Deaths recorded at one hospital in the period (records entered in error
     * left out). A SENSITIVE count: the service never returns a value from 1
     * to 4 ({@code SmallCounts}).
     */
    @Query("SELECT COUNT(d) FROM DeathRecord d WHERE d.hospital.id = :hospitalId "
        + "AND d.enteredInErrorAt IS NULL AND d.diedAt >= :from AND d.diedAt < :toExclusive")
    long countDeaths(@Param("hospitalId") UUID hospitalId,
                     @Param("from") LocalDateTime from,
                     @Param("toExclusive") LocalDateTime toExclusive);
}
