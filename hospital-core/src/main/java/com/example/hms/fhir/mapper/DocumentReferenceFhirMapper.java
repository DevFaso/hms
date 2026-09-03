package com.example.hms.fhir.mapper;

import com.example.hms.enums.PatientDocumentType;
import com.example.hms.model.PatientUploadedDocument;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.discharge.DischargeSummary;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Maps the two document domains onto FHIR R4 {@code DocumentReference}
 * (Tier 2 item 44):
 * <ul>
 *   <li>{@code upl-{uuid}} — a {@link PatientUploadedDocument}: metadata plus
 *       the public download URL. The server-side {@code filePath} is NEVER
 *       exposed, and the stored SHA-256 checksum is deliberately not mapped —
 *       R4 defines {@code attachment.hash} as the base64 of a <em>SHA-1</em>
 *       digest, so publishing our SHA-256 there would be a wrong claim.</li>
 *   <li>{@code discharge-{uuid}} — a {@link DischargeSummary}: the composed
 *       narrative rides inline as a {@code text/plain} attachment, since the
 *       summary is structured rows, not a stored file.</li>
 * </ul>
 */
@Component
public class DocumentReferenceFhirMapper {

    private static final String LOINC = "http://loinc.org";
    /** Local fallback for document types without a solid LOINC document-ontology code. */
    private static final String LOCAL_DOC_TYPE = "urn:hms:patient-document-type";
    private static final String PATIENT_REF = "Patient/";

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
        Attachment attachment = new Attachment();
        if (src.getMimeType() != null && !src.getMimeType().isBlank()) {
            attachment.setContentType(src.getMimeType());
        }
        if (src.getDisplayName() != null && !src.getDisplayName().isBlank()) {
            attachment.setTitle(src.getDisplayName());
        }
        if (src.getFileSizeBytes() != null) {
            attachment.setSize(Math.toIntExact(Math.min(src.getFileSizeBytes(), Integer.MAX_VALUE)));
        }
        if (src.getFileUrl() != null && !src.getFileUrl().isBlank()) {
            attachment.setUrl(src.getFileUrl());
        }
        if (src.getCollectionDate() != null) {
            attachment.setCreation(Date.from(
                src.getCollectionDate().atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        doc.addContent().setAttachment(attachment);
        return doc;
    }

    public DocumentReference toFhir(DischargeSummary src) {
        if (src == null || src.getId() == null) return null;
        DocumentReference doc = new DocumentReference();
        doc.setId("discharge-" + src.getId());
        doc.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
        doc.setType(new CodeableConcept().addCoding(new org.hl7.fhir.r4.model.Coding(
            LOINC, "18842-5", "Discharge summary")));
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
        LocalDateTime when = src.getDischargeTime() != null
            ? src.getDischargeTime()
            : src.getDischargeDate() != null ? src.getDischargeDate().atStartOfDay() : null;
        if (when != null) {
            doc.setDate(toDate(when));
        }
        Attachment attachment = new Attachment();
        attachment.setContentType("text/plain");
        attachment.setTitle("Discharge summary"
            + (src.getDischargeDate() != null ? " — " + src.getDischargeDate() : ""));
        attachment.setData(renderDischargeText(src).getBytes(StandardCharsets.UTF_8));
        doc.addContent().setAttachment(attachment);
        return doc;
    }

    /**
     * LOINC document-ontology codes only where the mapping is unambiguous;
     * everything else keeps the local system rather than borrowing a
     * nearly-right standard code.
     */
    private static CodeableConcept uploadedType(PatientDocumentType type) {
        if (type == null) {
            return new CodeableConcept().addCoding(
                new org.hl7.fhir.r4.model.Coding(LOCAL_DOC_TYPE, "UNKNOWN", "Unknown document"));
        }
        return switch (type) {
            case LAB_RESULT -> loinc("11502-2", "Laboratory report");
            case IMAGING_REPORT -> loinc("18748-4", "Diagnostic imaging study");
            case DISCHARGE_SUMMARY -> loinc("18842-5", "Discharge summary");
            case REFERRAL_LETTER -> loinc("57133-1", "Referral note");
            case PRESCRIPTION -> loinc("57833-6", "Prescription for medication");
            default -> new CodeableConcept().addCoding(
                new org.hl7.fhir.r4.model.Coding(LOCAL_DOC_TYPE, type.name(), type.name()));
        };
    }

    private static CodeableConcept loinc(String code, String display) {
        return new CodeableConcept().addCoding(new org.hl7.fhir.r4.model.Coding(LOINC, code, display));
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
        if (body.length() > 0) body.append('\n').append('\n');
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
