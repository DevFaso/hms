package com.example.hms.fhir.mapper;

import com.example.hms.enums.PatientDocumentType;
import com.example.hms.model.PatientUploadedDocument;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.discharge.DischargeSummary;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Maps the two document domains onto FHIR R4 {@code DocumentReference}
 * (Tier 2 item 44):
 * <ul>
 *   <li>{@code upl-{uuid}} — a {@link PatientUploadedDocument}: metadata
 *       plus {@code attachment.url} pointing at the staff download route
 *       ({@code /api/patients/{patientId}/documents/{id}/download}), which
 *       is bearer-authenticated and scoped to a hospital the patient is
 *       registered at — the same gate this resource is served under. The
 *       server-side {@code filePath} is never exposed, and the stored
 *       {@code fileUrl} column is not mapped either: historical rows carry
 *       dead {@code /uploads} paths from when that tree was served
 *       permitAll. The SHA-256 checksum is also deliberately not mapped —
 *       R4 defines {@code attachment.hash} as a <em>SHA-1</em> digest.</li>
 *   <li>{@code discharge-{uuid}} — a {@link DischargeSummary}: the composed
 *       narrative rides inline as a {@code text/plain} attachment, with
 *       {@code docStatus} carrying the draft/final lifecycle.</li>
 * </ul>
 */
@Component
public class DocumentReferenceFhirMapper {

    private static final String LOINC = "http://loinc.org";
    /** Local fallback for document types without a solid LOINC document-ontology code. */
    private static final String LOCAL_DOC_TYPE = "urn:hms:patient-document-type";
    private static final String PATIENT_REF = "Patient/";
    private static final String DISCHARGE_SUMMARY_DISPLAY = "Discharge summary";

    /**
     * Origin the API is reachable at from outside (scheme + host, no path),
     * so {@code attachment.url} is absolute as FHIR expects. Empty on
     * environments that have not configured {@code app.public-base-url};
     * the link is then origin-relative, which a same-origin portal still
     * resolves.
     */
    private final String publicBaseUrl;

    public DocumentReferenceFhirMapper(@Value("${app.public-base-url:}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    public DocumentReference toFhir(PatientUploadedDocument src) {
        if (src == null || src.getId() == null) return null;
        DocumentReference doc = new DocumentReference();
        doc.setId("upl-" + src.getId());
        doc.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
        doc.setType(uploadedType(src.getDocumentType()));
        if (src.getPatient() != null && src.getPatient().getId() != null) {
            doc.setSubject(new Reference(PATIENT_REF + src.getPatient().getId()));
        }
        if (src.getCreatedAt() != null) {
            doc.setDate(toDate(src.getCreatedAt()));
        }
        String uploader = displayName(src.getUploadedByUser());
        if (uploader != null) {
            doc.addAuthor(new Reference().setDisplay(uploader));
        }
        if (src.getNotes() != null && !src.getNotes().isBlank()) {
            doc.setDescription(src.getNotes());
        }
        doc.addContent().setAttachment(uploadedAttachment(src));
        return doc;
    }

    /** Extracted from {@link #toFhir(PatientUploadedDocument)} — Sonar S3776. */
    private Attachment uploadedAttachment(PatientUploadedDocument src) {
        Attachment attachment = new Attachment();
        if (src.getPatient() != null && src.getPatient().getId() != null) {
            attachment.setUrl(publicBaseUrl + "/api/patients/" + src.getPatient().getId()
                + "/documents/" + src.getId() + "/download");
        }
        if (src.getMimeType() != null && !src.getMimeType().isBlank()) {
            attachment.setContentType(src.getMimeType());
        }
        if (src.getDisplayName() != null && !src.getDisplayName().isBlank()) {
            attachment.setTitle(src.getDisplayName());
        }
        if (src.getFileSizeBytes() != null) {
            attachment.setSize(Math.toIntExact(Math.min(src.getFileSizeBytes(), Integer.MAX_VALUE)));
        }
        if (src.getCollectionDate() != null) {
            attachment.setCreation(Date.from(
                src.getCollectionDate().atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        // No inline data and no hash — see the class javadoc for why each is
        // withheld on purpose.
        return attachment;
    }

    public DocumentReference toFhir(DischargeSummary src) {
        if (src == null || src.getId() == null) return null;
        DocumentReference doc = new DocumentReference();
        doc.setId("discharge-" + src.getId());
        doc.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
        // The draft/final lifecycle is real information for a consumer: a
        // PRELIMINARY summary can still change under them.
        doc.setDocStatus(Boolean.TRUE.equals(src.getIsFinalized())
            ? org.hl7.fhir.r4.model.DocumentReference.ReferredDocumentStatus.FINAL
            : org.hl7.fhir.r4.model.DocumentReference.ReferredDocumentStatus.PRELIMINARY);
        doc.setType(new CodeableConcept().addCoding(
            new Coding(LOINC, "18842-5", DISCHARGE_SUMMARY_DISPLAY)));
        if (src.getPatient() != null && src.getPatient().getId() != null) {
            doc.setSubject(new Reference(PATIENT_REF + src.getPatient().getId()));
        }
        if (src.getEncounter() != null && src.getEncounter().getId() != null) {
            doc.getContext().addEncounter(new Reference("Encounter/" + src.getEncounter().getId()));
        }
        if (src.getHospital() != null && src.getHospital().getName() != null) {
            doc.setCustodian(new Reference().setDisplay(src.getHospital().getName()));
        }
        Staff provider = src.getDischargingProvider();
        if (provider != null && provider.getName() != null && !provider.getName().isBlank()) {
            doc.addAuthor(new Reference().setDisplay(provider.getName()));
        }
        LocalDateTime when = resolveDischargeMoment(src);
        if (when != null) {
            doc.setDate(toDate(when));
        }
        Attachment attachment = new Attachment();
        attachment.setContentType("text/plain");
        attachment.setTitle(src.getDischargeDate() != null
            ? DISCHARGE_SUMMARY_DISPLAY + " — " + src.getDischargeDate()
            : DISCHARGE_SUMMARY_DISPLAY);
        attachment.setData(renderDischargeText(src).getBytes(StandardCharsets.UTF_8));
        doc.addContent().setAttachment(attachment);
        return doc;
    }

    /** Prefers the precise discharge time; falls back to the date's start of day. */
    private static LocalDateTime resolveDischargeMoment(DischargeSummary src) {
        if (src.getDischargeTime() != null) {
            return src.getDischargeTime();
        }
        if (src.getDischargeDate() != null) {
            return src.getDischargeDate().atStartOfDay();
        }
        return null;
    }

    /**
     * LOINC document-ontology codes only where the mapping is unambiguous;
     * everything else keeps the local system rather than borrowing a
     * nearly-right standard code.
     */
    private static CodeableConcept uploadedType(PatientDocumentType type) {
        if (type == null) {
            return new CodeableConcept().addCoding(
                new Coding(LOCAL_DOC_TYPE, "UNKNOWN", "Unknown document"));
        }
        return switch (type) {
            case LAB_RESULT -> loinc("11502-2", "Laboratory report");
            case IMAGING_REPORT -> loinc("18748-4", "Diagnostic imaging study");
            case DISCHARGE_SUMMARY -> loinc("18842-5", DISCHARGE_SUMMARY_DISPLAY);
            case REFERRAL_LETTER -> loinc("57133-1", "Referral note");
            case PRESCRIPTION -> loinc("57833-6", "Prescription for medication");
            default -> new CodeableConcept().addCoding(
                new Coding(LOCAL_DOC_TYPE, type.name(), type.name()));
        };
    }

    private static CodeableConcept loinc(String code, String display) {
        return new CodeableConcept().addCoding(new Coding(LOINC, code, display));
    }

    private static String renderDischargeText(DischargeSummary src) {
        StringBuilder body = new StringBuilder();
        appendSection(body, "Discharge diagnosis", src.getDischargeDiagnosis());
        if (src.getDisposition() != null) {
            appendSection(body, "Disposition", src.getDisposition().name());
        }
        appendSection(body, "Hospital course", src.getHospitalCourse());
        appendSection(body, "Condition at discharge", src.getDischargeCondition());
        appendSection(body, "Activity restrictions", src.getActivityRestrictions());
        appendSection(body, "Diet", src.getDietInstructions());
        appendSection(body, "Wound care", src.getWoundCareInstructions());
        appendSection(body, "Follow-up", src.getFollowUpInstructions());
        appendSection(body, "Warning signs", src.getWarningSigns());
        appendSection(body, "Patient education provided", src.getPatientEducationProvided());
        return body.toString();
    }

    private static void appendSection(StringBuilder body, String label, String value) {
        if (value == null || value.isBlank()) return;
        if (!body.isEmpty()) body.append('\n').append('\n');
        body.append(label).append(":\n").append(value.strip());
    }

    private static String displayName(User user) {
        if (user == null) return null;
        String first = user.getFirstName() == null ? "" : user.getFirstName().strip();
        String last = user.getLastName() == null ? "" : user.getLastName().strip();
        String full = (first + " " + last).strip();
        if (!full.isEmpty()) return full;
        return user.getUsername();
    }

    private static Date toDate(LocalDateTime value) {
        return Date.from(value.atZone(ZoneId.systemDefault()).toInstant());
    }
}
