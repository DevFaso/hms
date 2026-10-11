package com.example.hms.security;

import com.example.hms.model.SecurityRevocation;
import com.example.hms.repository.SecurityRevocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GlobalSessionRevocationService (MVP-7)")
class GlobalSessionRevocationServiceTest {

    private SecurityRevocationRepository repository;
    private GlobalSessionRevocationService service;

    @BeforeEach
    void setUp() {
        repository = mock(SecurityRevocationRepository.class);
        service = new GlobalSessionRevocationService(repository);
        when(repository.save(any(SecurityRevocation.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("getGlobalMinTokenIat starts at EPOCH before init runs")
    void defaultsToEpoch() {
        assertThat(service.getGlobalMinTokenIat()).isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("refresh hydrates the cached value when the singleton row exists")
    void refreshHydratesFromRow() {
        Instant stored = Instant.parse("2026-05-01T10:00:00Z");
        SecurityRevocation row = new SecurityRevocation();
        row.setId(SecurityRevocation.SINGLETON_ID);
        row.setGlobalMinTokenIat(stored);
        when(repository.findById(SecurityRevocation.SINGLETON_ID)).thenReturn(Optional.of(row));

        service.refresh();

        assertThat(service.getGlobalMinTokenIat()).isEqualTo(stored);
    }

    @Test
    @DisplayName("refresh resets cache to EPOCH when the singleton row is missing (PR #228 fix)")
    void refreshResetsToEpochWhenMissing() {
        when(repository.findById(SecurityRevocation.SINGLETON_ID)).thenReturn(Optional.empty());

        service.refresh();

        assertThat(service.getGlobalMinTokenIat()).isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("refresh keeps the previous cached value when the repository throws")
    void refreshSwallowsRuntimeException() {
        // Seed a known cached value first.
        Instant seeded = Instant.parse("2026-05-02T08:00:00Z");
        SecurityRevocation row = new SecurityRevocation();
        row.setId(SecurityRevocation.SINGLETON_ID);
        row.setGlobalMinTokenIat(seeded);
        when(repository.findById(SecurityRevocation.SINGLETON_ID)).thenReturn(Optional.of(row));
        service.refresh();

        // Now simulate a transient DB blip — cache must not change.
        when(repository.findById(SecurityRevocation.SINGLETON_ID))
            .thenThrow(new RuntimeException("transient failure"));

        service.refresh();

        assertThat(service.getGlobalMinTokenIat()).isEqualTo(seeded);
    }

    @Test
    @DisplayName("revokeAll persists a fresh row when no singleton exists, returns the new timestamp")
    void revokeAllInsertsWhenAbsent() {
        when(repository.findById(SecurityRevocation.SINGLETON_ID)).thenReturn(Optional.empty());

        UUID actorId = UUID.randomUUID();
        Instant before = Instant.now();
        Instant takenAt = service.revokeAll(actorId, "super.alice", "incident X");

        assertThat(takenAt).isAfterOrEqualTo(before);
        ArgumentCaptor<SecurityRevocation> captor = ArgumentCaptor.forClass(SecurityRevocation.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        SecurityRevocation saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(SecurityRevocation.SINGLETON_ID);
        assertThat(saved.getLastRevokedByUserId()).isEqualTo(actorId);
        assertThat(saved.getLastRevokedByUsername()).isEqualTo("super.alice");
        assertThat(saved.getLastRevokedReason()).isEqualTo("incident X");
        // Reported at its real time; the token cut-off is that time rounded up.
        assertThat(service.getGlobalMinTokenIat()).isEqualTo(GlobalSessionRevocationService.cutOff(takenAt));
    }

    @Test
    @DisplayName("revokeAll updates the existing singleton row in place")
    void revokeAllUpdatesExisting() {
        SecurityRevocation row = new SecurityRevocation();
        row.setId(SecurityRevocation.SINGLETON_ID);
        row.setGlobalMinTokenIat(Instant.EPOCH);
        when(repository.findById(SecurityRevocation.SINGLETON_ID)).thenReturn(Optional.of(row));

        Instant takenAt = service.revokeAll(null, null, null);

        assertThat(row.getGlobalMinTokenIat()).isEqualTo(GlobalSessionRevocationService.cutOff(takenAt));
        assertThat(service.getGlobalMinTokenIat()).isEqualTo(GlobalSessionRevocationService.cutOff(takenAt));
        assertThat(row.getUpdatedAt()).isEqualTo(takenAt);
    }

    @Test
    @DisplayName("revokes: only a token issued before the last force-logout-all")
    void revokesOnlyTokensIssuedBeforeTheRevocation() {
        assertThat(service.revokes(new java.util.Date())).isFalse();

        Instant takenAt = service.revokeAll(null, null, null);

        assertThat(service.revokes(java.util.Date.from(takenAt.minusSeconds(1)))).isTrue();
        assertThat(service.revokes(java.util.Date.from(takenAt.plusSeconds(1)))).isFalse();
        assertThat(service.revokes(null)).isFalse();
    }

    @Test
    @DisplayName("revokes: every token of the revocation's whole second is revoked (a kill switch errs safe)")
    void everyTokenOfTheRevocationsSecond_isRevoked() {
        java.time.Instant takenAt = service.revokeAll(java.util.UUID.randomUUID(), "admin", "test");
        java.time.Instant cutOff = GlobalSessionRevocationService.cutOff(takenAt);
        // The revocation is reported at its real time; only the cut-off rounds up.
        assertThat(takenAt).isBeforeOrEqualTo(java.time.Instant.now());
        assertThat(service.getGlobalMinTokenIat()).isEqualTo(cutOff);

        // The stored cut-off is a whole second, rounded UP from the moment of
        // the revocation: a JWT iat is whole seconds, so a token minted in
        // that second (iat = the second before the cut-off) is revoked, even
        // one minted a few milliseconds BEFORE the revocation.
        assertThat(cutOff.getNano()).isZero();
        assertThat(service.revokes(java.util.Date.from(cutOff.minusSeconds(1)))).isTrue();
        assertThat(service.revokes(java.util.Date.from(cutOff))).isFalse();
    }

    @Test
    @DisplayName("the cut-off rounds up to the next whole second, and keeps an exact second as is")
    void theCutOffRoundsUp() {
        assertThat(GlobalSessionRevocationService.cutOff(java.time.Instant.parse("2026-10-11T12:00:00.100Z")))
            .isEqualTo(java.time.Instant.parse("2026-10-11T12:00:01Z"));
        assertThat(GlobalSessionRevocationService.cutOff(java.time.Instant.parse("2026-10-11T12:00:00Z")))
            .isEqualTo(java.time.Instant.parse("2026-10-11T12:00:00Z"));
    }
}
