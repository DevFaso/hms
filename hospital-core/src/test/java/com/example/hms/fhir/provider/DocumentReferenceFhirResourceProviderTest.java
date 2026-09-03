package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.DocumentReferenceFhirMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientUploadedDocument;
import com.example.hms.model.discharge.DischargeSummary;
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientUploadedDocumentRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tenant contract for the DocumentReference provider. Mapper stubs are
 * LENIENT and non-null on purpose: with a defaulting mock mapper, deleting
 * a tenant guard still collapses reads to not-found (null mapped resource)
 * and the test lies — {@code verifyNoInteractions(mapper)} is the guard's
 * real witness (lesson from the item-42 mutation run).
 */
@ExtendWith(MockitoExtension.class)
class DocumentReferenceFhirResourceProviderTest {

    @Mock private PatientUploadedDocumentRepository uploadedDocumentRepository;
    @Mock private DischargeSummaryRepository dischargeSummaryRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private DocumentReferenceFhirMapper mapper;

    @InjectMocks
    private DocumentReferenceFhirResourceProvider provider;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void stubMapperNonNull() {
        Mockito.lenient().when(mapper.toFhir(any(PatientUploadedDocument.class)))
            .thenReturn(new DocumentReference());
        Mockito.lenient().when(mapper.toFhir(any(DischargeSummary.class)))
            .thenReturn(new DocumentReference());
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("no active hospital scope → 403, nothing queried")
    void noScopeForbidden() {
        HospitalContextHolder.setContext(HospitalContext.empty());
        IdType id = new IdType("DocumentReference", "upl-" + UUID.randomUUID());
        assertThatThrownBy(() -> provider.read(id))
            .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(uploadedDocumentRepository, dischargeSummaryRepository, mapper);
    }

    @Test
    @DisplayName("uploaded doc of a patient NOT registered at the caller's hospital collapses to not-found")
    void foreignUploadedDocumentIsNotFound() {
        setScope();
        PatientUploadedDocument doc = uploadedDoc();
        when(uploadedDocumentRepository.findById(doc.getId())).thenReturn(Optional.of(doc));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(Optional.empty());

        IdType id = new IdType("DocumentReference", "upl-" + doc.getId());
        assertThatThrownBy(() -> provider.read(id))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("soft-deleted uploaded doc is not-found even at the right hospital")
    void softDeletedUploadedDocumentIsNotFound() {
        setScope();
        PatientUploadedDocument doc = uploadedDoc();
        doc.setDeletedAt(java.time.LocalDateTime.now());
        when(uploadedDocumentRepository.findById(doc.getId())).thenReturn(Optional.of(doc));

        IdType id = new IdType("DocumentReference", "upl-" + doc.getId());
        assertThatThrownBy(() -> provider.read(id))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("discharge summary of another hospital collapses to not-found")
    void foreignDischargeSummaryIsNotFound() {
        setScope();
        DischargeSummary summary = dischargeSummary(UUID.randomUUID());
        when(dischargeSummaryRepository.findById(summary.getId())).thenReturn(Optional.of(summary));

        IdType id = new IdType("DocumentReference", "discharge-" + summary.getId());
        assertThatThrownBy(() -> provider.read(id))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("read dispatches by prefix and maps the tenant-owned row")
    void readHappyPaths() {
        setScope();
        PatientUploadedDocument doc = uploadedDoc();
        when(uploadedDocumentRepository.findById(doc.getId())).thenReturn(Optional.of(doc));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        assertThat(provider.read(new IdType("DocumentReference", "upl-" + doc.getId()))).isNotNull();

        DischargeSummary summary = dischargeSummary(hospitalId);
        when(dischargeSummaryRepository.findById(summary.getId())).thenReturn(Optional.of(summary));
        assertThat(provider.read(new IdType("DocumentReference", "discharge-" + summary.getId()))).isNotNull();
    }

    @Test
    @DisplayName("search for an unregistered patient returns only what the hospital-scoped query yields")
    void searchSkipsUploadedSectionForForeignPatient() {
        setScope();
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(Optional.empty());
        when(dischargeSummaryRepository
            .findWithAssociationsByPatient_IdAndHospital_IdOrderByDischargeDateDesc(patientId, hospitalId))
            .thenReturn(List.of());

        List<DocumentReference> out = provider.search(new ReferenceParam("Patient/" + patientId), null);

        assertThat(out).isEmpty();
        verifyNoInteractions(uploadedDocumentRepository, mapper);
    }

    @Test
    @DisplayName("search merges both sections for a registered patient")
    void searchMergesBothSections() {
        setScope();
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(uploadedDocumentRepository.findByPatient_IdAndDeletedAtIsNullOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(patientId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(uploadedDoc())));
        when(dischargeSummaryRepository
            .findWithAssociationsByPatient_IdAndHospital_IdOrderByDischargeDateDesc(patientId, hospitalId))
            .thenReturn(List.of(dischargeSummary(hospitalId)));

        List<DocumentReference> out = provider.search(new ReferenceParam("Patient/" + patientId), null);

        assertThat(out).hasSize(2);
    }

    private void setScope() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId)
            .build());
    }

    private PatientUploadedDocument uploadedDoc() {
        Patient patient = new Patient();
        patient.setId(patientId);
        PatientUploadedDocument doc = PatientUploadedDocument.builder()
            .patient(patient)
            .displayName("doc.pdf")
            .filePath("/x")
            .build();
        doc.setId(UUID.randomUUID());
        return doc;
    }

    private DischargeSummary dischargeSummary(UUID ownerHospitalId) {
        Hospital hospital = new Hospital();
        hospital.setId(ownerHospitalId);
        DischargeSummary summary = DischargeSummary.builder().hospital(hospital).build();
        summary.setId(UUID.randomUUID());
        return summary;
    }
}
