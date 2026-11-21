package com.example.hms.payload.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Platform admin rules 2026-11-20, rule 1b: what an organisation admin reads
 * about its hospitals: totals per hospital and per period, and nothing about
 * a person (no patient list, no invoice, no note).
 *
 * @param from                the first day of the period
 * @param to                  the last day of the period
 * @param smallCountThreshold a sensitive count below this (and above zero) is not given
 * @param hospitals           one row per hospital of the caller's organisation
 */
public record OrganisationSummaryDTO(LocalDate from, LocalDate to, int smallCountThreshold,
                                     List<HospitalTotals> hospitals) {

    /**
     * @param currencyCode   the ISO 4217 code the two amounts are in (the hospital's own)
     * @param activeStaff    activated staff accounts today (not a period figure)
     * @param appointments   appointments dated in the period
     * @param admissions     admissions that began in the period
     * @param invoicedTotal  issued invoices of the period, whole units of the currency
     * @param collectedTotal confirmed payments of the period, whole units of the currency
     * @param deaths         deaths recorded in the period: a sensitive count
     */
    public record HospitalTotals(UUID hospitalId, String hospitalName, String currencyCode,
                                 long activeStaff, long appointments, long admissions,
                                 BigDecimal invoicedTotal, BigDecimal collectedTotal,
                                 SensitiveCount deaths) {
    }

    /**
     * A count in a sensitive category. A value from 1 to the threshold minus
     * one could point at a person in a small hospital, so it is withheld:
     * {@code suppressed} is true and {@code value} is null. Zero, and any value
     * at or above the threshold, is given.
     */
    public record SensitiveCount(Long value, boolean suppressed) {
    }

    /**
     * One hospital of the caller's organisation, for a picker: no figure.
     *
     * @param lifecycleState so a picker does not offer a hospital that takes no new role (the server refuses it anyway)
     */
    public record OrganisationHospital(UUID id, String name, String city, String code, String currencyCode,
                                       String lifecycleState) {
    }
}
