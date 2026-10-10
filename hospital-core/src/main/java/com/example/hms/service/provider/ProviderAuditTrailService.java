package com.example.hms.service.provider;

import com.example.hms.payload.dto.provider.ProviderAuditPageDTO;

import java.util.List;
import java.util.Optional;

/**
 * A provider facility's own audit trail (provider plan §3.1, "See the
 * facility's own audit trail"): what its staff did while acting there.
 *
 * <p>Its PROVIDER_ADMIN only, decided from live assignments at the facility
 * the request acts at ({@link ProviderSeatResolver#currentAdmin()}). Anyone
 * else gets {@link Optional#empty()}, which the controller answers exactly as
 * an unmapped path. The paging parameters arrive raw and are parsed AFTER the
 * seat check, so a malformed one cannot tell a caller with no seat that the
 * page exists.
 */
public interface ProviderAuditTrailService {

    /** The page size when none is asked for. */
    int DEFAULT_PAGE_SIZE = 20;

    /** The largest page served; a larger size is capped to it. */
    int MAX_PAGE_SIZE = 100;

    /**
     * The only kinds of record the facility trail shows: administering the
     * facility (its profile, its staff, and role assignments there; a staff
     * registration at the facility shows as its USER_ROLE_ASSIGNMENT row). An
     * allowlist, so nothing clinical written at the facility can ever reach
     * it. Deliberately not {@code USER}: that is any {@code /users/**} write,
     * a patient account's included. Compared exactly as the producers write
     * them (upper-case).
     */
    List<String> FACILITY_ENTITY_TYPES = List.of("PROVIDER_FACILITY", "PROVIDER_STAFF", "USER_ROLE_ASSIGNMENT");

    /**
     * One page of the facility's audit rows, newest first: only the
     * {@link #FACILITY_ENTITY_TYPES}, ids and codes, no patient row (plan §6.9).
     *
     * @param page zero-based page number as sent, or {@code null} for the first page
     * @param size page size as sent, or {@code null} for {@link #DEFAULT_PAGE_SIZE}
     * @throws IllegalArgumentException for the admin's page or size that is not a
     *         non-negative (page) or positive (size) whole number
     */
    Optional<ProviderAuditPageDTO> trail(String page, String size);
}
