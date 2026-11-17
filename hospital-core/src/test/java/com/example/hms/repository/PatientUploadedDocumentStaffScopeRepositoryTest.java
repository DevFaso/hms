package com.example.hms.repository;

import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PatientDocumentType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientUploadedDocument;
import com.example.hms.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D961 (review of #971): every staff read of a patient's documents at a
 * hospital returns the patient's own uploads and the documents this hospital
 * issued, never a certificate another hospital issued. The filter is in the
 * queries, so it is checked against the real tables (built from the entities).
 */
@DataJpaTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@DisplayName("Staff document reads stay within the issuing hospital (QA D961)")
class PatientUploadedDocumentStaffScopeRepositoryTest {

    @Autowired private PatientUploadedDocumentRepository repository;
    @Autowired private TestEntityManager em;

    private Hospital hospitalA;
    private Patient patient;
    private UUID ownUpload;
    private UUID issuedAtA;
    private UUID issuedAtB;

    @BeforeEach
    void setUp() {
        Organization organization = em.persist(Organization.builder()
                .name("Docs Network").code("ORG-DS").type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospitalA = em.persist(hospital(organization, "HDSA"));
        Hospital hospitalB = em.persist(hospital(organization, "HDSB"));
        User patientUser = em.persist(user("patientds"));
        patient = em.persist(Patient.builder()
                .firstName("Awa").lastName("Kabore").dateOfBirth(LocalDate.of(1991, 3, 4)).gender("F")
                .address("Patient address").city("Ouagadougou").country("Burkina Faso")
                .phoneNumberPrimary("+22678000044").email("awa.docs@patient.test")
                .organizationId(organization.getId()).hospitalId(hospitalA.getId()).user(patientUser).build());
        ownUpload = em.persist(document(patientUser, PatientDocumentType.LAB_RESULT, null)).getId();
        issuedAtA = em.persist(document(patientUser, PatientDocumentType.MEDICAL_CERTIFICATE, hospitalA)).getId();
        issuedAtB = em.persist(document(patientUser, PatientDocumentType.MEDICAL_CERTIFICATE, hospitalB)).getId();
        em.flush();
        em.clear();
    }

    @Test
    void theStaffListShowsOwnUploadsAndThisHospitalsCertificatesOnly() {
        assertThat(ids(repository.findForStaff(patient.getId(), hospitalA.getId(), PageRequest.of(0, 10)).getContent()))
                .containsExactlyInAnyOrder(ownUpload, issuedAtA);
        assertThat(ids(repository.findForStaffByType(patient.getId(), hospitalA.getId(),
                PatientDocumentType.MEDICAL_CERTIFICATE, PageRequest.of(0, 10)).getContent()))
                .containsExactly(issuedAtA);
    }

    @Test
    void theFhirReadPathShowsOwnUploadsAndThisHospitalsCertificatesOnly() {
        assertThat(ids(repository.findForStaffNewestFirst(patient.getId(), hospitalA.getId(), PageRequest.of(0, 10))
                .getContent()))
                .containsExactlyInAnyOrder(ownUpload, issuedAtA)
                .doesNotContain(issuedAtB);
    }

    private static List<UUID> ids(List<PatientUploadedDocument> docs) {
        return docs.stream().map(PatientUploadedDocument::getId).toList();
    }

    private PatientUploadedDocument document(User uploader, PatientDocumentType type, Hospital issuer) {
        return PatientUploadedDocument.builder()
                .patient(patient).uploadedByUser(uploader).issuingHospital(issuer)
                .documentType(type).displayName(type.name().toLowerCase() + ".pdf")
                .filePath("/patient-documents/" + UUID.randomUUID() + ".pdf").mimeType("application/pdf")
                .build();
    }

    private static Hospital hospital(Organization organization, String code) {
        return Hospital.builder()
                .name("Docs Hospital " + code).code(code)
                .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
                .phoneNumber("+2265550" + code.charAt(3)).email(code.toLowerCase() + "@hospital.test")
                .organization(organization).build();
    }

    private static User user(String prefix) {
        return User.builder()
                .username(prefix + "-ds").passwordHash("hashed-password")
                .email(prefix + "@docs.test").firstName(prefix + "FN").lastName("User")
                .phoneNumber("+22677" + Math.abs(prefix.hashCode() % 1000000)).isActive(true).build();
    }
}
