package com.example.hms.fhir.mapper;

import com.example.hms.enums.DischargeDisposition;
import com.example.hms.enums.PatientDocumentType;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientUploadedDocument;
import com.example.hms.model.User;
import com.example.hms.model.discharge.DischargeSummary;
import org.hl7.fhir.r4.model.DocumentReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentReferenceFhirMapperTest {

    private final DocumentReferenceFhirMapper mapper = new DocumentReferenceFhirMapper();

    @Test
    @DisplayName("uploaded document maps metadata + public URL — and NEVER the server file path")
    void uploadedDocumentMapsMetadataWithoutTheFilePath() {
        UUID patientId = UUID.randomUUID();
        Patient patient = new Patient();
        patient.setId(patientId);
        User uploader = new User();
        uploader.setFirstName("Awa");
        uploader.setLastName("Traoré");

        PatientUploadedDocument src = PatientUploadedDocument.builder()
            .patient(patient)
            .uploadedByUser(uploader)
            .documentType(PatientDocumentType.LAB_RESULT)
            .displayName("cbc-2026.pdf")
            .filePath("/var/hms/uploads/patient-documents/secret-server-path.pdf")
            .fileUrl("https://api.e-keneya.com/uploads/doc-123.pdf")
            .mimeType("application/pdf")
            .fileSizeBytes(48_211L)
            .checksumSha256("ab".repeat(32))
            .collectionDate(LocalDate.of(2026, 8, 14))
            .notes("Brought in from the reference lab")
            .build();
        src.setId(UUID.randomUUID());

        DocumentReference doc = mapper.toFhir(src);

        assertThat(doc.getIdElement().getIdPart()).isEqualTo("upl-" + src.getId());
        assertThat(doc.getStatus().toCode()).isEqualTo("current");
        assertThat(doc.getType().getCodingFirstRep().getSystem()).isEqualTo("http://loinc.org");
        assertThat(doc.getType().getCodingFirstRep().getCode()).isEqualTo("11502-2");
        assertThat(doc.getSubject().getReference()).isEqualTo("Patient/" + patientId);
        assertThat(doc.getAuthorFirstRep().getDisplay()).isEqualTo("Awa Traoré");
        assertThat(doc.getDescription()).isEqualTo("Brought in from the reference lab");
        var attachment = doc.getContentFirstRep().getAttachment();
        assertThat(attachment.getContentType()).isEqualTo("application/pdf");
        assertThat(attachment.getTitle()).isEqualTo("cbc-2026.pdf");
        assertThat(attachment.getSize()).isEqualTo(48_211);
        assertThat(attachment.getUrl()).isEqualTo("https://api.e-keneya.com/uploads/doc-123.pdf");
        // The two things that must never leak: the server-side path, and a
        // SHA-256 published in attachment.hash (R4 defines hash as SHA-1).
        String serialized = ca.uhn.fhir.context.FhirContext.forR4Cached()
            .newJsonParser().encodeResourceToString(doc);
        assertThat(serialized).doesNotContain("secret-server-path");
        assertThat(attachment.getHash()).isNull();
    }

    @Test
    @DisplayName("document types without a solid LOINC code keep the local system")
    void unknownTypesStayOnTheLocalSystem() {
        PatientUploadedDocument src = PatientUploadedDocument.builder()
            .documentType(PatientDocumentType.INSURANCE_DOCUMENT)
            .displayName("card.jpg")
            .filePath("/x")
            .build();
        src.setId(UUID.randomUUID());

        DocumentReference doc = mapper.toFhir(src);

        assertThat(doc.getType().getCodingFirstRep().getSystem())
            .isEqualTo("urn:hms:patient-document-type");
        assertThat(doc.getType().getCodingFirstRep().getCode()).isEqualTo("INSURANCE_DOCUMENT");
    }

    @Test
    @DisplayName("discharge summary renders as an inline text/plain attachment, LOINC 18842-5")
    void dischargeSummaryRendersInlineText() {
        UUID patientId = UUID.randomUUID();
        Patient patient = new Patient();
        patient.setId(patientId);

        DischargeSummary src = DischargeSummary.builder()
            .patient(patient)
            .dischargeDate(LocalDate.of(2026, 9, 1))
            .disposition(DischargeDisposition.HOME)
            .dischargeDiagnosis("Paludisme simple")
            .hospitalCourse("Treated with artemether-lumefantrine, afebrile at discharge.")
            .followUpInstructions("Return in 7 days for review.")
            .build();
        src.setId(UUID.randomUUID());

        DocumentReference doc = mapper.toFhir(src);

        assertThat(doc.getIdElement().getIdPart()).isEqualTo("discharge-" + src.getId());
        assertThat(doc.getType().getCodingFirstRep().getCode()).isEqualTo("18842-5");
        assertThat(doc.getSubject().getReference()).isEqualTo("Patient/" + patientId);
        var attachment = doc.getContentFirstRep().getAttachment();
        assertThat(attachment.getContentType()).isEqualTo("text/plain");
        String text = new String(attachment.getData(), StandardCharsets.UTF_8);
        assertThat(text)
            .contains("Discharge diagnosis:", "Paludisme simple")
            .contains("Disposition:", "HOME")
            .contains("Follow-up:", "Return in 7 days for review.")
            .doesNotContain("Diet:");
    }
}
