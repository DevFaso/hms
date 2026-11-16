package com.example.hms.security;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.PatientNationalUniqueIdService;
import com.example.hms.service.RequiredAuditEventLogPort;
import com.example.hms.service.empi.PatientNationalUniqueIdAliases;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PatientNationalUniqueIdServiceTest {
    private static final String VALUE = "000123456789";
    private final PatientRepository patients = mock(PatientRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final EntityManager em = mock(EntityManager.class);
    private final Session session = mock(Session.class);
    private final PatientNationalUniqueIdAliases aliases = mock(PatientNationalUniqueIdAliases.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RequiredAuditEventLogPort> auditProvider = mock(ObjectProvider.class);
    private final RequiredAuditEventLogPort audit = mock(RequiredAuditEventLogPort.class);
    private final PatientNationalUniqueIdService service = new PatientNationalUniqueIdService(patients, roles, em, aliases, auditProvider);
    private SecretKey priorKey;

    @BeforeEach
    void setup() {
        priorKey = EncryptionKeyHolder.getKeyForTesting();
        EncryptionKeyHolder.setKeyForTesting(new SecretKeySpec(new byte[32], "AES"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("staff", null,
            List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))));
        when(em.unwrap(Session.class)).thenReturn(session);
        when(session.doReturningWork(any())).thenReturn(false);
        when(auditProvider.getObject()).thenReturn(audit);
    }
    @AfterEach
    void cleanup() { EncryptionKeyHolder.setKeyForTesting(priorKey); SecurityContextHolder.clearContext(); }

    @Test
    void omissionAndCanonicalUnchangedValuesNeedNoWriteOrNewAudit() {
        Patient patient = new Patient(); patient.setNationalUniqueId(VALUE);
        assertThat(service.prepare(patient, null, false, null).changed()).isFalse();
        assertThat(service.prepare(patient, "0001 2345-6789", false, null).changed()).isFalse();
        service.complete(patient, new PatientNationalUniqueIdService.Change(false, "", null), null);
        verifyNoInteractions(aliases, audit);
    }

    @Test
    void changingAValueRequiresAnIdentityEditorAndTheExistingPatientScope() {
        Patient patient = new Patient(); patient.setId(UUID.randomUUID());
        assertThatThrownBy(() -> service.prepare(patient, VALUE, false, null)).isInstanceOf(ResourceNotFoundException.class);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.prepare(patient, VALUE, false, null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(aliases, audit);
    }

    @Test
    void anOutOfScopeRevealRefusesBeforeLoadingTheUnscopedRecordOrAuditing() {
        UUID patientId = UUID.randomUUID();
        when(patients.isFindableByPatientSearch(patientId)).thenReturn(false);
        assertThatThrownBy(() -> service.reveal(patientId)).isInstanceOf(ResourceNotFoundException.class);
        verify(patients, never()).findByIdUnscoped(patientId);
        verifyNoInteractions(aliases, audit);
    }

    @Test
    void invalidValueNeverBecomesAnExceptionArgument() {
        assertThatThrownBy(() -> service.prepare(new Patient(), "00012345678", false, null))
            .isInstanceOf(FieldBusinessException.class).hasMessageNotContaining("00012345678");
    }

    @Test
    void outsideDuplicateRequiresExplicitOverrideAndANonBlankBoundedReason() {
        when(patients.existsOtherNationalUniqueIdHash(anyString(), isNull())).thenReturn(true);
        assertThatThrownBy(() -> service.prepare(new Patient(), VALUE, false, null)).isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.prepare(new Patient(), VALUE, true, " ")).isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.prepare(new Patient(), VALUE, true, "a".repeat(1001))).isInstanceOf(FieldBusinessException.class);
        Patient patient = new Patient(); patient.setId(UUID.randomUUID());
        when(patients.isFindableByPatientSearch(patient.getId())).thenReturn(true);
        when(patients.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(patients.existsOtherNationalUniqueIdHash(anyString(), eq(patient.getId()))).thenReturn(true);
        var change = service.prepare(patient, VALUE, true, "Confirmed " + VALUE);
        assertThat(change.changed()).isTrue(); assertThat(change.operation()).isEqualTo("CREATE");
        service.complete(patient, change, null);
        verify(aliases).synchronize(patient);
        var event = org.mockito.ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logRequiredEvent(event.capture());
        assertThat(event.getValue().getPatientId()).isEqualTo(patient.getId());
        assertThat(event.getValue().toString()).doesNotContain(VALUE, patient.getNationalUniqueIdHash());
    }

    @Test
    void createUpdateAndClearAreDistinctAndPreserveStringIdentity() {
        Patient patient = new Patient();
        assertThat(service.prepare(patient, VALUE, false, null).operation()).isEqualTo("CREATE");
        assertThat(patient.getNationalUniqueId()).isEqualTo(VALUE);
        assertThat(patient.getNationalUniqueIdHash()).isEqualTo(PatientNationalUniqueId.fingerprint(VALUE));
        assertThat(service.prepare(patient, "999123456789", false, null).operation()).isEqualTo("UPDATE");
        assertThat(service.prepare(patient, "", false, null).operation()).isEqualTo("CLEAR");
        assertThat(patient.getNationalUniqueId()).isNull(); assertThat(patient.getNationalUniqueIdHash()).isNull();
    }

    @Test
    void excludeCannotBeAnOracleForAnInaccessiblePatient() {
        UUID excluded = UUID.randomUUID();
        assertThatThrownBy(() -> service.check(VALUE, excluded)).isInstanceOf(ResourceNotFoundException.class);
        verify(patients, never()).existsOtherNationalUniqueIdHash(anyString(), any());
        assertThat(service.check("", null).duplicate()).isFalse();
    }

    @Test
    void anAccessibleDuplicateDoesNotRevealWhetherOtherHospitalsAlsoHaveMatches() {
        Patient patient = new Patient(); patient.setId(UUID.randomUUID());
        when(patients.findByNationalUniqueIdHashInScope(anyString())).thenReturn(List.of(patient));
        var warning = service.check(VALUE, null);
        assertThat(warning.accessiblePatientIds()).containsExactly(patient.getId());
        assertThat(warning.registeredElsewhere()).isFalse();
        verify(patients, never()).existsOtherNationalUniqueIdHash(anyString(), any());
    }

    @Test
    void aFullDisclosureCannotReturnWhenTheRequiredAuditFails() {
        Patient patient = new Patient(); patient.setId(UUID.randomUUID()); patient.setNationalUniqueId(VALUE);
        when(patients.isFindableByPatientSearch(patient.getId())).thenReturn(true);
        when(patients.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        doThrow(new IllegalStateException("Synthetic audit refusal")).when(audit).logRequiredEvent(any());
        assertThatThrownBy(() -> service.reveal(patient.getId())).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining(VALUE);
        var event = org.mockito.ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logRequiredEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(com.example.hms.enums.AuditEventType.PATIENT_ACCESS);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,true", "true,false"})
    void exactIdentitySearchKeepsTheExistingSafetyStatusWithoutClinicalDetails(boolean disputed, boolean blocked) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID()); patient.setFirstName("Existing"); patient.setLastName("Patient");
        patient.setActive(true); patient.setNationalUniqueId(VALUE);
        var diedAt = java.time.LocalDateTime.of(2026, 11, 15, 10, 0);
        patient.setDeceasedAt(diedAt); patient.setChartRestricted(true);
        patient.setChartRestrictionReason("Private restriction detail");
        patient.setMedicalHistorySummary("Private clinical history");
        UUID hospital = UUID.randomUUID();
        when(roles.requireActiveHospitalId()).thenReturn(hospital);
        when(patients.findByNationalUniqueIdHashInScope(anyString())).thenReturn(List.of(patient));
        try (var death = mockStatic(com.example.hms.service.support.DeceasedPatientGuard.class)) {
            death.when(() -> com.example.hms.service.support.DeceasedPatientGuard.deathDisputedHere(patient)).thenReturn(disputed);
            death.when(() -> com.example.hms.service.support.DeceasedPatientGuard.careBlocked(patient)).thenReturn(blocked);
            var identity = service.search(VALUE).getFirst();
            var ordinary = new com.example.hms.mapper.PatientMapper(new org.springframework.context.support.StaticMessageSource())
                .toPatientDTO(patient, hospital, false, false);
            for (var dto : List.of(identity, ordinary)) {
                assertThat(dto.isActive()).isTrue();
                assertThat(dto.isDeceased()).isTrue();
                assertThat(dto.getDeceasedAt()).isEqualTo(diedAt);
                assertThat(dto.isDeathDisputed()).isEqualTo(disputed);
                assertThat(dto.isCareBlockedByDeath()).isEqualTo(blocked);
                assertThat(dto.isChartRestricted()).isTrue();
                assertThat(dto.getNationalUniqueIdMasked()).isEqualTo("\u2022\u2022\u2022\u2022 \u2022\u2022\u2022\u2022 6789");
            }
            assertThat(identity.getMedicalHistorySummary()).isNull();
            assertThat(identity.getChartRestrictionReason()).isNull();
        }
    }
}
