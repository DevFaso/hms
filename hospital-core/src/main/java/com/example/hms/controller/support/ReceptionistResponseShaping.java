package com.example.hms.controller.support;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.bed.BedBoardDTO;
import com.example.hms.payload.dto.clinical.AfterVisitSummaryDTO;
import com.example.hms.payload.dto.clinical.PatientTrackerBoardDTO;
import com.example.hms.payload.dto.clinical.PatientTrackerItemDTO;
import com.example.hms.payload.dto.isolation.IsolationPrecautionResponseDTO;
import com.example.hms.payload.dto.transfer.TransferOrderResponseDTO;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User decision 2026-11-06 (RCPT-CLIN): a receptionist does not read clinical
 * content. The screens the desk needs (the patient page, the bed board, the
 * tracker, the check-out) keep their endpoint, and every answer on its way out
 * loses its clinical fields here, whichever controller built it: one place, and
 * {@code ReceptionistClinicalReadGuardTest} fails when a response the role can
 * read embeds a clinical field that is neither removed here nor listed there.
 *
 * <p>Only for a caller who is nothing but a receptionist: an account that also
 * holds a clinical or administrative role keeps what that role reads (a token
 * minted after the role picker carries the picked role only). {@code /me/**} is
 * the caller's own record or one they are a proxy for, not the desk's reach.
 *
 * <p>The DTOs are built per request (nothing in the application caches them), so
 * they are changed in place.
 */
@ControllerAdvice
public class ReceptionistResponseShaping implements ResponseBodyAdvice<Object> {

    private static final String RECEPTIONIST = "ROLE_RECEPTIONIST";
    /** Roles that read nothing clinical of other people, so holding them does not lift the shaping. */
    private static final Set<String> NOT_CLINICAL_READERS = Set.of(RECEPTIONIST, "ROLE_PATIENT", "ROLE_STAFF");
    private static final String ROLE_PREFIX = "ROLE_";
    private static final List<String> OWN_RECORD_PREFIXES = List.of("/me/", "/api/me/");

    /**
     * The fields removed, by response type. The guard reads this; {@link #shape}
     * is what does it, and {@code ReceptionistResponseShapingTest} holds the two equal.
     */
    public static final Map<Class<?>, Set<String>> REMOVED = Map.of(
        PatientHospitalRegistrationResponseDTO.class, Set.of("readyForDischargeNote"),
        PatientResponseDTO.class, Set.of("allergies", "medicalHistorySummary", "careTeamNotes", "hr", "bp", "spo2",
            "lastVitals", "chartRestrictionReason"),
        EncounterResponseDTO.class, Set.of("notes", "esiScore", "urgency", "followUpInstructions",
            "dischargeDiagnoses", "extraFields", "note", "noteEntries"),
        AfterVisitSummaryDTO.class, Set.of("chiefComplaint", "dischargeDiagnoses", "prescriptionSummary",
            "referralSummary", "followUpInstructions", "patientEducationMaterials"),
        BedBoardDTO.OccupantDTO.class, Set.of("primaryDiagnosis"),
        TransferOrderResponseDTO.class, Set.of("reason", "notes", "cancellationReason", "isolationOverrideReason"),
        IsolationPrecautionResponseDTO.class, Set.of("reason", "suspectedOrganism", "notes", "discontinuationReason"),
        PatientTrackerItemDTO.class, Set.of("acuityLevel"));

    @Override
    public boolean supports(@NonNull MethodParameter returnType,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, @NonNull MethodParameter returnType,
                                  @NonNull MediaType selectedContentType,
                                  @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  @NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response) {
        if (body != null && !(body instanceof byte[]) && isReceptionistOnly() && !isOwnRecord(request)) {
            shape(body);
        }
        return body;
    }

    private static boolean isOwnRecord(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        return path != null && OWN_RECORD_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /** Holds the receptionist role and no role beyond those that read nothing clinical. */
    public static boolean isReceptionistOnly() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        List<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(authority -> authority != null && authority.startsWith(ROLE_PREFIX))
            .toList();
        return roles.contains(RECEPTIONIST) && NOT_CLINICAL_READERS.containsAll(roles);
    }

    /** Removes the clinical fields from one response body, through its wrappers. */
    public static void shape(Object body) {
        switch (body) {
            case Page<?> page -> page.getContent().forEach(ReceptionistResponseShaping::shapeIfPresent);
            case Collection<?> many -> many.forEach(ReceptionistResponseShaping::shapeIfPresent);
            case ApiResponseWrapper<?> wrapper -> shapeIfPresent(wrapper.getData());
            case PatientResponseDTO patient -> shapePatient(patient);
            case PatientHospitalRegistrationResponseDTO registration -> registration.setReadyForDischargeNote(null);
            case EncounterResponseDTO encounter -> shapeEncounter(encounter);
            case AfterVisitSummaryDTO summary -> shapeSummary(summary);
            case BedBoardDTO board -> shapeBoard(board);
            case TransferOrderResponseDTO transfer -> shapeTransfer(transfer);
            case IsolationPrecautionResponseDTO precaution -> shapePrecaution(precaution);
            case PatientTrackerBoardDTO tracker -> shapeTracker(tracker);
            case PatientTrackerItemDTO item -> item.setAcuityLevel(null);
            default -> { /* Nothing clinical: the guard keeps this true. */ }
        }
    }

    private static void shapeIfPresent(Object body) {
        if (body != null) {
            shape(body);
        }
    }

    private static void shapePatient(PatientResponseDTO patient) {
        patient.setAllergies(null);
        patient.setMedicalHistorySummary(null);
        patient.setCareTeamNotes(null);
        patient.setHr(null);
        patient.setBp(null);
        patient.setSpo2(null);
        patient.setLastVitals(null);
        patient.setChartRestrictionReason(null);
    }

    private static void shapeEncounter(EncounterResponseDTO encounter) {
        encounter.setNotes(null);
        encounter.setEsiScore(null);
        encounter.setUrgency(null);
        encounter.setFollowUpInstructions(null);
        encounter.setDischargeDiagnoses(null);
        encounter.setExtraFields(null);
        encounter.setNote(null);
        encounter.setNoteEntries(null);
    }

    /** The lists stay lists: the check-out dialog reads their length. */
    private static void shapeSummary(AfterVisitSummaryDTO summary) {
        summary.setChiefComplaint(null);
        summary.setDischargeDiagnoses(List.of());
        summary.setPrescriptionSummary(null);
        summary.setReferralSummary(null);
        summary.setFollowUpInstructions(null);
        summary.setPatientEducationMaterials(null);
    }

    private static void shapeBoard(BedBoardDTO board) {
        if (board.getWards() == null) {
            return;
        }
        board.getWards().stream()
            .filter(ward -> ward.getRooms() != null)
            .flatMap(ward -> ward.getRooms().stream())
            .filter(room -> room.getBeds() != null)
            .flatMap(room -> room.getBeds().stream())
            .filter(bed -> bed.getOccupant() != null)
            .forEach(bed -> bed.getOccupant().setPrimaryDiagnosis(null));
    }

    private static void shapeTransfer(TransferOrderResponseDTO transfer) {
        transfer.setReason(null);
        transfer.setNotes(null);
        transfer.setCancellationReason(null);
        transfer.setIsolationOverrideReason(null);
    }

    private static void shapePrecaution(IsolationPrecautionResponseDTO precaution) {
        precaution.setReason(null);
        precaution.setSuspectedOrganism(null);
        precaution.setNotes(null);
        precaution.setDiscontinuationReason(null);
    }

    private static void shapeTracker(PatientTrackerBoardDTO tracker) {
        List.of(tracker.getArrived(), tracker.getTriage(), tracker.getWaitingForPhysician(), tracker.getInProgress(),
                tracker.getAwaitingResults(), tracker.getReadyForDischarge())
            .forEach(ReceptionistResponseShaping::shapeIfPresent);
    }
}
