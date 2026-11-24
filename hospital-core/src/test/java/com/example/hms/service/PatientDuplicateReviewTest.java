package com.example.hms.service;

import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.DuplicateCandidateDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PatientDuplicateReviewTest {
    private final ReceptionService reception = mock(ReceptionService.class);
    private final RequiredAuditEventLogPort repository = mock(RequiredAuditEventLogPort.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final PatientDuplicateReview service = new PatientDuplicateReview(reception, repository, roles);
    private final UUID hospital = UUID.randomUUID();
    private final UUID candidate = UUID.randomUUID();

    private PatientRequestDTO request(String reason) {
        PatientRequestDTO request = new PatientRequestDTO();
        request.setFirstName("cdx Awa"); request.setLastName("Ouédraogo");
        request.setDateOfBirth(LocalDate.of(1990, 1, 1)); request.setDuplicateReviewReason(reason);
        return request;
    }
    private void match() {
        when(reception.getDuplicateCandidates(anyString(), anyString(), nullable(String.class), eq(hospital)))
            .thenReturn(List.of(DuplicateCandidateDTO.builder().patientId(candidate).build()));
    }

    @Test void candidateSearchIncludesEveryStoredNamePart() {
        var dto = request("cdx separate person");
        dto.setMiddleName("Wendkouni");
        when(reception.getDuplicateCandidates(anyString(), anyString(), nullable(String.class), eq(hospital))).thenReturn(List.of());
        assertThat(service.prepare(dto, hospital)).isNull();
        verify(reception).getDuplicateCandidates("cdx Awa Wendkouni Ouédraogo", "1990-01-01", null, hospital);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", "\t"})
    void aPossibleDuplicateCannotBeKeptWithoutAnExplicitReason(String reason) {
        match();
        assertThatThrownBy(() -> service.prepare(request(reason), hospital))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class);
        verifyNoInteractions(repository);
    }

    @Test void noMatchNeedsNoOverrideAndWritesNoDecision() {
        when(reception.getDuplicateCandidates(anyString(), anyString(), nullable(String.class), eq(hospital))).thenReturn(List.of());
        var decision = service.prepare(request(null), hospital);
        assertThat(decision).isNull(); service.record(decision, new Patient());
        verifyNoInteractions(repository, roles);
    }

    @Test void aDecisionKeepsTheServerScopedCandidatesActorReasonAndNewPatientTogether() {
        match(); UUID actor = UUID.randomUUID(); when(roles.getCurrentUserId()).thenReturn(actor);
        AuditEventLogResponseDTO receipt = new AuditEventLogResponseDTO(); receipt.setId(UUID.randomUUID());
        when(repository.logRequiredEvent(any())).thenReturn(receipt);
        var decision = service.prepare(request("  cdx Prénoms et documents différents  "), hospital);
        Patient patient = new Patient(); patient.setId(UUID.randomUUID()); service.record(decision, patient);
        var capture = ArgumentCaptor.forClass(AuditEventRequestDTO.class); verify(repository).logRequiredEvent(capture.capture());
        var row = capture.getValue();
        assertThat(row.getHospitalId()).isEqualTo(hospital); assertThat(row.getResourceId()).isEqualTo(patient.getId().toString());
        assertThat(row.getUserId()).isEqualTo(actor);
        assertThat(((java.util.Map<?,?>)row.getDetails()).get("reason")).isEqualTo("cdx Prénoms et documents différents");
        verify(reception).getDuplicateCandidates("cdx Awa Ouédraogo", "1990-01-01", null, hospital);
        assertThat(patient.getId()).isNotEqualTo(candidate); // no patient is merged
    }

    @Test void missingActorAndOversizedReasonsFailBeforeAnyDecisionWrite() {
        match();
        assertThatThrownBy(() -> service.prepare(request("x".repeat(2001)), hospital)).isInstanceOf(com.example.hms.exception.FieldBusinessException.class);
        assertThatThrownBy(() -> service.prepare(request("cdx separate person"), hospital)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(repository);
    }

    @Test void aDurabilityFailurePropagatesToTheRegistrationTransaction() {
        match(); when(roles.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(repository.logRequiredEvent(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("cdx unavailable"));
        var decision = service.prepare(request("cdx separate person"), hospital);
        Patient patient = new Patient(); patient.setId(UUID.randomUUID());
        assertThatThrownBy(() -> service.record(decision, patient)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void aMissingAuditReceiptCannotBeTreatedAsRecorded() {
        match(); when(roles.getCurrentUserId()).thenReturn(UUID.randomUUID());
        var decision = service.prepare(request("cdx different person"), hospital);
        Patient patient = new Patient(); patient.setId(UUID.randomUUID());
        var refusal = catchThrowableOfType(() -> service.record(decision, patient), IllegalStateException.class);
        assertThat(refusal).hasMessage("patient.identity.auditMissing");
        var request = mock(org.springframework.web.context.request.WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/patients");
        var response = new com.example.hms.exception.GlobalExceptionHandler().handleIllegalStateException(refusal, request);
        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test void manualApiMergeRequestsRequireANonblankReason() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var patient = new com.example.hms.payload.dto.empi.EmpiPatientMergeRequestDTO();
            var identity = new com.example.hms.payload.dto.empi.EmpiMergeRequestDTO();
            assertThat(validator.validateProperty(patient, "notes")).isNotEmpty();
            assertThat(validator.validateProperty(identity, "notes")).isNotEmpty();
            patient.setNotes("cdx documented identity review"); identity.setNotes(patient.getNotes());
            assertThat(validator.validateProperty(patient, "notes")).isEmpty();
            assertThat(validator.validateProperty(identity, "notes")).isEmpty();
        }
    }
}
