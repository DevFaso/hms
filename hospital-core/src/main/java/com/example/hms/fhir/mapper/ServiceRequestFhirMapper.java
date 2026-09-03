package com.example.hms.fhir.mapper;

import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.LabOrder;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Maps the two order domains into FHIR R4 {@code ServiceRequest} (Tier 2
 * item 42): lab orders ({@code laborder-{uuid}}) and imaging orders
 * ({@code imgorder-{uuid}}). Namespaced ids, the Observation-provider
 * convention, so one FHIR id maps unambiguously back to a source row.
 *
 * <p>Read-only on purpose. Orders are placed through the clinical surfaces
 * that enforce signature, contrast-safety and standing-order review; a FHIR
 * write path would bypass every one of those ceremonies.
 */
@Component
public class ServiceRequestFhirMapper {

    public ServiceRequest toFhir(LabOrder src) {
        if (src == null || src.getId() == null) return null;
        ServiceRequest sr = new ServiceRequest();
        sr.setId("laborder-" + src.getId());
        sr.setStatus(mapLabStatus(src.getStatus()));
        sr.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
        sr.setPriority(mapLabPriority(src.getPriority()));
        sr.addCategory(new CodeableConcept().addCoding(new Coding()
            .setSystem("http://snomed.info/sct")
            .setCode("108252007")
            .setDisplay("Laboratory procedure")));
        sr.setCode(LabCodes.codeFor(src.getLabTestDefinition(), "Laboratory order"));
        if (src.getPatient() != null && src.getPatient().getId() != null) {
            sr.setSubject(new Reference("Patient/" + src.getPatient().getId()));
        }
        if (src.getEncounter() != null && src.getEncounter().getId() != null) {
            sr.setEncounter(new Reference("Encounter/" + src.getEncounter().getId()));
        }
        sr.setAuthoredOnElement(dateTime(src.getOrderDatetime()));
        setRequester(sr, src);
        addReason(sr, src.getClinicalIndication());
        addNote(sr, src.getNotes());
        return sr;
    }

    private static void setRequester(ServiceRequest sr, LabOrder src) {
        if (src.getOrderingStaff() == null || src.getOrderingStaff().getUser() == null) {
            return;
        }
        var user = src.getOrderingStaff().getUser();
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
            + (user.getLastName() == null ? "" : user.getLastName())).trim();
        if (!name.isEmpty()) {
            sr.setRequester(new Reference().setDisplay(name));
        }
    }

    private static void addReason(ServiceRequest sr, String reason) {
        if (reason != null && !reason.isBlank()) {
            sr.addReasonCode(new CodeableConcept().setText(reason));
        }
    }

    private static void addNote(ServiceRequest sr, String note) {
        if (note != null && !note.isBlank()) {
            sr.addNote().setText(note);
        }
    }

    public ServiceRequest toFhir(ImagingOrder src) {
        if (src == null || src.getId() == null) return null;
        ServiceRequest sr = new ServiceRequest();
        sr.setId("imgorder-" + src.getId());
        sr.setStatus(mapImagingStatus(src.getStatus()));
        sr.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
        sr.setPriority(mapImagingPriority(src.getPriority()));
        sr.addCategory(new CodeableConcept().addCoding(new Coding()
            .setSystem("http://snomed.info/sct")
            .setCode("363679005")
            .setDisplay("Imaging")));
        CodeableConcept code = new CodeableConcept();
        if (src.getStudyType() != null && !src.getStudyType().isBlank()) {
            code.setText(src.getStudyType());
        }
        if (src.getModality() != null) {
            code.addCoding(new Coding()
                .setSystem("urn:hms:imaging:modality")
                .setCode(src.getModality().name()));
        }
        sr.setCode(code);
        if (src.getBodyRegion() != null && !src.getBodyRegion().isBlank()) {
            sr.addBodySite(new CodeableConcept().setText(src.getBodyRegion()));
        }
        if (src.getPatient() != null && src.getPatient().getId() != null) {
            sr.setSubject(new Reference("Patient/" + src.getPatient().getId()));
        }
        sr.setAuthoredOnElement(dateTime(src.getOrderedAt()));
        addReason(sr, src.getClinicalQuestion());
        addNote(sr, src.getSpecialInstructions());
        return sr;
    }

    /**
     * The lab pipeline distinguishes eight working states; FHIR asks only
     * where the order stands. Everything between placement and a result is
     * {@code active} — a FHIR client asking "is this still open" does not
     * care whether the tube is in a centrifuge.
     */
    private static ServiceRequest.ServiceRequestStatus mapLabStatus(LabOrderStatus status) {
        if (status == null) return ServiceRequest.ServiceRequestStatus.UNKNOWN;
        return switch (status) {
            case ORDERED, PENDING, COLLECTED, RECEIVED, IN_PROGRESS ->
                ServiceRequest.ServiceRequestStatus.ACTIVE;
            case RESULTED, VERIFIED, COMPLETED -> ServiceRequest.ServiceRequestStatus.COMPLETED;
            case CANCELLED -> ServiceRequest.ServiceRequestStatus.REVOKED;
        };
    }

    private static ServiceRequest.ServiceRequestStatus mapImagingStatus(ImagingOrderStatus status) {
        if (status == null) return ServiceRequest.ServiceRequestStatus.UNKNOWN;
        return switch (status) {
            case DRAFT -> ServiceRequest.ServiceRequestStatus.DRAFT;
            case ORDERED, PENDING_AUTHORIZATION, SCHEDULED, IN_PROGRESS ->
                ServiceRequest.ServiceRequestStatus.ACTIVE;
            case COMPLETED, RESULTS_AVAILABLE -> ServiceRequest.ServiceRequestStatus.COMPLETED;
            case CANCELLED -> ServiceRequest.ServiceRequestStatus.REVOKED;
        };
    }

    /**
     * All three values survive the mapping. The first cut collapsed STAT to
     * routine via a two-way ternary — understating the urgency of a valid
     * order is the one direction a priority mapping must never fail in.
     */
    private static ServiceRequest.ServiceRequestPriority mapImagingPriority(
        ImagingOrderPriority priority) {
        if (priority == null) return ServiceRequest.ServiceRequestPriority.ROUTINE;
        return switch (priority) {
            case STAT -> ServiceRequest.ServiceRequestPriority.STAT;
            case URGENT -> ServiceRequest.ServiceRequestPriority.URGENT;
            case ROUTINE -> ServiceRequest.ServiceRequestPriority.ROUTINE;
        };
    }

    private static ServiceRequest.ServiceRequestPriority mapLabPriority(String priority) {
        if (priority == null) return ServiceRequest.ServiceRequestPriority.ROUTINE;
        return switch (priority.toUpperCase()) {
            case "STAT" -> ServiceRequest.ServiceRequestPriority.STAT;
            case "URGENT" -> ServiceRequest.ServiceRequestPriority.URGENT;
            case "ASAP" -> ServiceRequest.ServiceRequestPriority.ASAP;
            default -> ServiceRequest.ServiceRequestPriority.ROUTINE;
        };
    }

    private static org.hl7.fhir.r4.model.DateTimeType dateTime(LocalDateTime value) {
        if (value == null) return null;
        return new org.hl7.fhir.r4.model.DateTimeType(
            Date.from(value.atZone(ZoneId.systemDefault()).toInstant()));
    }
}
