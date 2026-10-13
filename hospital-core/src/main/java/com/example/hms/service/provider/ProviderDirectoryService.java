package com.example.hms.service.provider;

import com.example.hms.payload.dto.provider.ProviderDirectoryPageDTO;

/**
 * The provider directory hospitals pick from (provider plan §6.5, AC-14): the
 * verified, active pharmacies and laboratories on the platform.
 *
 * <p>Behind {@code provider.organisations.enabled} (default OFF), checked
 * here, before anything else, so every caller of the directory shares the
 * one gate: with it off the answer is empty, whatever the caller or the
 * parameters (plan AC-14).
 */
public interface ProviderDirectoryService {

    /** At most this many entries per search. */
    int MAX_RESULTS = 50;

    /**
     * Verified, active providers, by name: at most {@link #MAX_RESULTS}, with
     * {@code hasMore} when more matched. Empty when the flag is off.
     *
     * @param type  {@code PHARMACY}, {@code LABORATORY}, or blank for both; any
     *              other value is a 400 ({@code provider.type.invalid})
     * @param query a name fragment, or blank for every one
     * @throws org.springframework.security.access.AccessDeniedException unless
     *         the caller acts at a HOSPITAL and holds a directory role there
     *         (live), or is a verified super-admin acting at a HOSPITAL
     */
    ProviderDirectoryPageDTO search(String type, String query);
}
