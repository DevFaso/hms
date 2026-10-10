package com.example.hms.service.provider;

import com.example.hms.payload.dto.provider.ProviderDirectoryEntryDTO;

import java.util.List;

/**
 * The provider directory hospitals pick from (provider plan §6.5, AC-14): the
 * verified, active pharmacies and laboratories on the platform.
 *
 * <p>The {@code provider.organisations.enabled} flag is checked by the
 * handler before anything else; this service is reached with it on.
 */
public interface ProviderDirectoryService {

    /** At most this many entries per search. */
    int MAX_RESULTS = 50;

    /**
     * Verified, active providers, by name.
     *
     * @param type  {@code PHARMACY}, {@code LABORATORY}, or blank for both; any
     *              other value is a 400 ({@code provider.type.invalid})
     * @param query a name fragment, or blank for every one
     * @throws org.springframework.security.access.AccessDeniedException unless
     *         the caller acts at a HOSPITAL and holds a directory role there
     *         (live), or is a verified super-admin acting at a HOSPITAL
     */
    List<ProviderDirectoryEntryDTO> search(String type, String query);
}
