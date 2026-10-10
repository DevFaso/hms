package com.example.hms.service.provider;

import com.example.hms.model.Hospital;

import java.util.UUID;

/**
 * Where the caller of a {@code /provider/**} handler works: the provider
 * facility (a PHARMACY or LABORATORY row) this request acts at, read from the
 * caller's LIVE active assignments there, never from token authorities.
 *
 * @param facility the provider facility
 * @param userId   the caller's local user id
 * @param admin    the caller holds an ACTIVE PROVIDER_ADMIN assignment there
 */
public record ProviderSeat(Hospital facility, UUID userId, boolean admin) {
}
