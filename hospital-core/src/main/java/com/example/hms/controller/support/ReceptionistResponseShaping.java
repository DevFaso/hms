package com.example.hms.controller.support;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.FlowBoardDTO;
import com.example.hms.payload.dto.ReceptionQueueItemDTO;
import com.example.hms.payload.dto.prenatal.PrenatalScheduleResponseDTO;
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
 *
 * <p>User decision 2026-11-06, the desk's clinical fields one by one: the booked visit reason and a
 * complaint the desk typed itself stay; triage's complaint, the blood type and the high-risk flag go;
 * the isolation precaution stays as a type, without organism or reason; the gestational week stays.
 */
@ControllerAdvice
public class ReceptionistResponseShaping implements ResponseBodyAdvice<Object> {

    private static final String RECEPTIONIST = "ROLE_RECEPTIONIST";
    /** Roles that read nothing clinical of other people, so holding them does not lift the shaping. */
    private static final Set<String> NOT_CLINICAL_READERS = Set.of(RECEPTIONIST, "ROLE_PATIENT", "ROLE_STAFF");
    private static final String ROLE_PREFIX = "ROLE_";

    /**
     * The roles that read a visit itself ({@code EncounterController}'s list), and so its reason
     * wherever it shows. Everyone else on a front-desk view (the hospital admin, billing, accounts)
     * reads the reason as the desk does: what was booked or typed at the desk, never triage's
     * complaint. {@code DeskViewVisitReasonGuardTest} holds this set equal to the visit readers.
     */
    public static final Set<String> VISIT_READERS = Set.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE",
        "ROLE_RADIOLOGIST", "ROLE_ANESTHESIOLOGIST", "ROLE_PHYSIOTHERAPIST", "ROLE_SUPER_ADMIN");

    /**
     * The front-desk view types whose visit reason is shaped for every caller outside
     * {@link #VISIT_READERS}, with the field: the class of QA D4265.
     */
    public static final Map<Class<?>, String> DESK_VIEW_VISIT_REASON =
        Map.of(ReceptionQueueItemDTO.class, "appointmentReason");
    private static final List<String> OWN_RECORD_PREFIXES = List.of("/me/", "/api/me/");

    /**
     * The fields removed, by response type. The guard reads this; {@link #shape}
     * is what does it, and {@code ReceptionistResponseShapingTest} holds the two equal.
     */
    public static final Map<Class<?>, Set<String>> REMOVED = Map.ofEntries(
        Map.entry(PatientHospitalRegistrationResponseDTO.class, Set.of("readyForDischargeNote")),
        Map.entry(PatientResponseDTO.class, Set.of("bloodType", "bloodGroup", "allergies", "chronicConditions", "flags", "risks",
            "medicalHistorySummary", "careTeamNotes", "hr", "bp", "spo2", "lastVitals", "chartRestrictionReason")),
        Map.entry(EncounterResponseDTO.class, Set.of("notes", "esiScore", "urgency", "followUpInstructions",
            "dischargeDiagnoses", "extraFields", "note", "noteEntries", "notePendingSignature")),
        Map.entry(AfterVisitSummaryDTO.class, Set.of("chiefComplaint", "dischargeDiagnoses", "prescriptionSummary",
            "referralSummary", "followUpInstructions", "patientEducationMaterials",
            // User decision 2026-11-12: the summary's content sections; the contact numbers stay.
            "vitalSigns", "orderedTests", "allergies", "currentMedications", "clinicalAssessment", "clinicalPlan",
            // QA D2260 (r63): the addenda of the signed note are clinical text too.
            "noteAddenda",
            "medicalHistory", "surgicalHistory", "familyHistory", "socialHistory",
            // QA r70 D3220: the blood group printed on the summary.
            "patientBloodType",
            // QA D3901: the declared and confirmed group and the antibody flag beside it.
            "patientBloodGroup")),
        Map.entry(BedBoardDTO.OccupantDTO.class, Set.of("primaryDiagnosis")),
        Map.entry(TransferOrderResponseDTO.class, Set.of("reason", "notes", "cancellationReason", "isolationOverrideReason")),
        Map.entry(IsolationPrecautionResponseDTO.class, Set.of("reason", "suspectedOrganism", "notes", "discontinuationReason")),
        Map.entry(PatientTrackerItemDTO.class, Set.of("acuityLevel")),
        Map.entry(PrenatalScheduleResponseDTO.class, Set.of("highRisk")));

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
        if (body == null || body instanceof byte[] || isOwnRecord(request)) {
            return body;
        }
        if (isReceptionistOnly()) {
            shape(body);
        } else if (!readsVisits()) {
            shapeDeskView(body);
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

    /** Holds a role that reads a visit itself; a request with no authentication is not shaped here. */
    public static boolean readsVisits() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(VISIT_READERS::contains);
    }

    /**
     * A front-desk view for a caller who reads no visit: the visit reason becomes the desk's,
     * exactly as for a receptionist, through the wrappers a desk view comes in.
     */
    public static void shapeDeskView(Object body) {
        switch (body) {
            case Page<?> page -> page.getContent().forEach(ReceptionistResponseShaping::shapeDeskViewIfPresent);
            case Collection<?> many -> many.forEach(ReceptionistResponseShaping::shapeDeskViewIfPresent);
            case ApiResponseWrapper<?> wrapper -> shapeDeskViewIfPresent(wrapper.getData());
            case ReceptionQueueItemDTO row -> row.setAppointmentReason(row.getDeskAppointmentReason());
            case FlowBoardDTO flow -> java.util.stream.Stream.of(flow.getScheduled(), flow.getConfirmed(),
                    flow.getArrived(), flow.getInProgress(), flow.getNoShow(), flow.getCompleted(), flow.getWalkIn())
                .forEach(ReceptionistResponseShaping::shapeDeskViewIfPresent);
            default -> { /* Not a front-desk view: DeskViewVisitReasonGuardTest keeps this true. */ }
        }
    }

    private static void shapeDeskViewIfPresent(Object body) {
        if (body != null) {
            shapeDeskView(body);
        }
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
            case ReceptionQueueItemDTO row -> row.setAppointmentReason(row.getDeskAppointmentReason());
            case FlowBoardDTO flow -> shapeFlow(flow);
            case PrenatalScheduleResponseDTO schedule -> schedule.setHighRisk(null);
            default -> { /* Nothing clinical: the guard keeps this true. */ }
        }
    }

    private static void shapeIfPresent(Object body) {
        if (body != null) {
            shape(body);
        }
    }

    private static void shapeFlow(FlowBoardDTO flow) {
        java.util.stream.Stream.of(flow.getScheduled(), flow.getConfirmed(), flow.getArrived(), flow.getInProgress(),
                flow.getNoShow(), flow.getCompleted(), flow.getWalkIn())
            .forEach(ReceptionistResponseShaping::shapeIfPresent);
    }

    private static void shapePatient(PatientResponseDTO patient) {
        patient.setBloodType(null);
        patient.setBloodGroup(null);
        patient.setAllergies(null);
        patient.setChronicConditions(List.of());
        patient.setFlags(List.of());
        patient.setRisks(List.of());
        patient.setMedicalHistorySummary(null);
        patient.setCareTeamNotes(null);
        patient.setHr(null);
        patient.setBp(null);
        patient.setSpo2(null);
        patient.setLastVitals(null);
        patient.setChartRestrictionReason(null);
    }

    private static void shapeEncounter(EncounterResponseDTO encounter) {
        encounter.setChiefComplaint(encounter.getDeskChiefComplaint());
        encounter.setNotes(null);
        encounter.setEsiScore(null);
        encounter.setUrgency(null);
        encounter.setFollowUpInstructions(null);
        encounter.setDischargeDiagnoses(null);
        encounter.setExtraFields(null);
        encounter.setNote(null);
        encounter.setNoteEntries(null);
        encounter.setNotePendingSignature(null);
    }

    /** The lists stay lists: the check-out dialog reads their length. */
    private static void shapeSummary(AfterVisitSummaryDTO summary) {
        summary.setChiefComplaint(null);
        summary.setDischargeDiagnoses(List.of());
        summary.setPrescriptionSummary(null);
        summary.setReferralSummary(null);
        summary.setFollowUpInstructions(null);
        summary.setPatientEducationMaterials(null);
        summary.setVitalSigns(null);
        summary.setOrderedTests(null);
        summary.setAllergies(null);
        summary.setCurrentMedications(null);
        summary.setClinicalAssessment(null);
        summary.setClinicalPlan(null);
        summary.setNoteAddenda(null);
        summary.setMedicalHistory(null);
        summary.setSurgicalHistory(null);
        summary.setFamilyHistory(null);
        summary.setSocialHistory(null);
        summary.setPatientBloodType(null);
        summary.setPatientBloodGroup(null);
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
