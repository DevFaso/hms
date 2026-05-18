package com.example.hms.fhir.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import com.example.hms.fhir.FhirWriteProperties;
import com.example.hms.fhir.mapper.EncounterFhirMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Row 20 follow-on: optimistic-concurrency precondition tests for
 * {@link EncounterFhirWriteService}. Pin both the version-id parser
 * (weak / strong / blank ETag) and the precondition-fail surface
 * (412 + OperationOutcome CONFLICT) before the contract ships in any
 * partner integration.
 */
@ExtendWith(MockitoExtension.class)
class EncounterFhirWriteServiceIfMatchTest {

    @Mock private FhirWriteProperties writeProperties;
    @Mock private EncounterFhirMapper encounterMapper;
    @Mock private EncounterRepository encounterRepository;
    @Mock private AuditEventLogService auditEventLogService;

    private EncounterFhirWriteService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID encounterId = UUID.randomUUID();
    private final LocalDateTime updatedAt = LocalDateTime.of(2026, 5, 17, 12, 0, 0);

    @BeforeEach
    void setUp() {
        service = new EncounterFhirWriteService(
            writeProperties, encounterMapper, encounterRepository, auditEventLogService);
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId).build());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("parseIfMatch — weak ETag W/\"123\" → 123")
    void parseWeakEtag() {
        assertThat(EncounterFhirWriteService.parseIfMatch("W/\"123\"")).contains("123");
    }

    @Test
    @DisplayName("parseIfMatch — strong ETag \"123\" → 123")
    void parseStrongEtag() {
        assertThat(EncounterFhirWriteService.parseIfMatch("\"123\"")).contains("123");
    }

    @Test
    @DisplayName("parseIfMatch — null / blank → empty (skip precondition)")
    void parseBlankSkips() {
        assertThat(EncounterFhirWriteService.parseIfMatch(null)).isEmpty();
        assertThat(EncounterFhirWriteService.parseIfMatch("   ")).isEmpty();
        assertThat(EncounterFhirWriteService.parseIfMatch("")).isEmpty();
    }

    @Test
    @DisplayName("toVersionId — null updatedAt renders \"0\" so first PUT against a fresh row works")
    void toVersionIdNull() {
        assertThat(EncounterFhirWriteService.toVersionId(null)).isEqualTo("0");
    }

    @Test
    @DisplayName("toVersionId — non-null updatedAt renders epoch-millis")
    void toVersionIdRendersEpochMillis() {
        long expected = updatedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        assertThat(EncounterFhirWriteService.toVersionId(updatedAt)).isEqualTo(Long.toString(expected));
    }

    @Test
    @DisplayName("update with matching If-Match version applies the update + saves")
    void matchingIfMatchAppliesUpdate() {
        when(writeProperties.isEnabled()).thenReturn(true);
        Encounter stored = newStoredEncounter();
        when(encounterRepository.findByIdAndHospital_Id(encounterId, hospitalId))
            .thenReturn(Optional.of(stored));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));

        String currentVersion = EncounterFhirWriteService.toVersionId(updatedAt);
        Encounter result = service.update(
            encounterId,
            new org.hl7.fhir.r4.model.Encounter(),
            "W/\"" + currentVersion + "\"");

        assertThat(result).isSameAs(stored);
        verify(encounterMapper).applyFhirUpdates(any(), any());
        verify(encounterRepository).save(stored);
    }

    @Test
    @DisplayName("update with stale If-Match version throws 412 + does not save")
    void staleIfMatchPreconditionFailed() {
        when(writeProperties.isEnabled()).thenReturn(true);
        Encounter stored = newStoredEncounter();
        when(encounterRepository.findByIdAndHospital_Id(encounterId, hospitalId))
            .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.update(
                encounterId,
                new org.hl7.fhir.r4.model.Encounter(),
                "W/\"0\""))  // 0 != epoch-millis of 2026-05-17T12:00:00Z
            .isInstanceOf(PreconditionFailedException.class)
            .hasMessageContaining("If-Match precondition failed");

        verify(encounterRepository, never()).save(any());
    }

    @Test
    @DisplayName("update with null If-Match header skips the precondition (foundation behaviour preserved)")
    void noIfMatchSkipsPrecondition() {
        when(writeProperties.isEnabled()).thenReturn(true);
        Encounter stored = newStoredEncounter();
        when(encounterRepository.findByIdAndHospital_Id(encounterId, hospitalId))
            .thenReturn(Optional.of(stored));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));

        Encounter result = service.update(encounterId,
            new org.hl7.fhir.r4.model.Encounter(), null);

        assertThat(result).isSameAs(stored);
        verify(encounterRepository).save(stored);
    }

    private Encounter newStoredEncounter() {
        Encounter e = new Encounter();
        e.setId(encounterId);
        Hospital h = new Hospital();
        h.setId(hospitalId);
        e.setHospital(h);
        e.setUpdatedAt(updatedAt);
        return e;
    }
}
