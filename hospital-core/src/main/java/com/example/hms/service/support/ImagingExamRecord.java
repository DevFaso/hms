package com.example.hms.service.support;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.imaging.ImagingOrderStatusUpdateRequestDTO;
import com.example.hms.utility.MessageUtil;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Policy rule 3 (owner 2026-11-20, PROVISIONAL until the clinic's radiologist or medical
 * director signs it off): the exam record of an imaging order, and what the imaging technician
 * (« Technicien d'imagerie médicale / Manipulateur ») may do to a study's status.
 *
 * <p>The technician executes: they check the patient in, start the exam, complete it or
 * discontinue it with a reason, and record how it went. They decide nothing about the order
 * itself: they do not schedule it, record its pre-authorisation or call off an exam that has not
 * started (the ordering clinician or a radiologist does). Administrative, not clinical: who
 * takes which step. Nothing here grades an exam.
 */
@ProvisionalRule({ClinicalRuleSetKey.IMAGING_TECHNICIAN_SCOPE, ClinicalRuleSetKey.NON_PHYSICIAN_ULTRASOUND})
public final class ImagingExamRecord {

    static final String TECHNICIAN_STEPS_KEY = "imaging.technician.examStepsOnly";
    static final String CHECK_IN_CLOSED_KEY = "imaging.exam.checkIn.notOpen";
    static final String RECORD_CLOSED_KEY = "imaging.exam.record.notOpen";

    /** An order a patient can still be checked in for: placed and not yet done. */
    private static final Set<ImagingOrderStatus> CHECK_IN_OPEN = EnumSet.of(ImagingOrderStatus.ORDERED,
        ImagingOrderStatus.PENDING_AUTHORIZATION, ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.IN_PROGRESS);

    /** A study whose record can still be written: started, and not yet read (a signed report closes it). */
    private static final Set<ImagingOrderStatus> RECORD_OPEN =
        EnumSet.of(ImagingOrderStatus.IN_PROGRESS, ImagingOrderStatus.COMPLETED);

    private ImagingExamRecord() {
    }

    /**
     * The status writes a technician makes: start, complete, and discontinue a started exam (the
     * cancel reason is required by the status write itself). Anything else, and any scheduling or
     * pre-authorisation field, is refused with 403: the order is not theirs to change.
     */
    public static void requireTechnicianStep(ImagingOrderStatus from, ImagingOrderStatusUpdateRequestDTO request) {
        ImagingOrderStatus to = request.getStatus();
        boolean starts = to == ImagingOrderStatus.IN_PROGRESS && from != ImagingOrderStatus.IN_PROGRESS;
        boolean discontinues = to == ImagingOrderStatus.CANCELLED && from == ImagingOrderStatus.IN_PROGRESS;
        boolean step = starts || to == ImagingOrderStatus.COMPLETED || discontinues;
        if (!step || changesTheOrder(request)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw(TECHNICIAN_STEPS_KEY));
        }
    }

    /** The request carries a scheduling or pre-authorisation field: a change to the order itself. */
    private static boolean changesTheOrder(ImagingOrderStatusUpdateRequestDTO request) {
        return java.util.stream.Stream.of(request.getScheduledDate(), request.getScheduledTime(),
                request.getAppointmentLocation(), request.getWorkflowNotes(), request.getRequiresAuthorization(),
                request.getAuthorizationNumber())
            .anyMatch(java.util.Objects::nonNull);
    }

    /**
     * What a record write changes, for its audit row. The contrast flag and the dose as
     * "before -> after"; the two free texts as set, changed or cleared (never their content: they
     * are encrypted at rest and can name the patient). Empty when nothing changes.
     */
    public static java.util.Map<String, String> changes(ImagingOrder order,
                                                       com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO request) {
        java.util.Map<String, String> changes = new java.util.LinkedHashMap<>();
        textChange(changes, "technicalNote", order.getExamTechnicalNote(), request.getTechnicalNote());
        if (!java.util.Objects.equals(order.getExamContrastAdministered(), request.getContrastAdministered())) {
            changes.put("contrastAdministered",
                order.getExamContrastAdministered() + " -> " + request.getContrastAdministered());
        }
        textChange(changes, "contrastDetails", order.getExamContrastDetails(), request.getContrastDetails());
        java.math.BigDecimal before = order.getExamRadiationDoseMgy();
        java.math.BigDecimal after = request.getRadiationDoseMgy();
        boolean sameDose = before == null ? after == null : after != null && before.compareTo(after) == 0;
        if (!sameDose) {
            changes.put("radiationDoseMgy", plain(before) + " -> " + plain(after));
        }
        return changes;
    }

    private static void textChange(java.util.Map<String, String> changes, String field, String before, String after) {
        String was = before == null || before.isBlank() ? null : before.trim();
        String now = after == null || after.isBlank() ? null : after.trim();
        if (java.util.Objects.equals(was, now)) {
            return;
        }
        String change;
        if (was == null) {
            change = "set";
        } else if (now == null) {
            change = "cleared";
        } else {
            change = "changed";
        }
        changes.put(field, change);
    }

    private static String plain(java.math.BigDecimal value) {
        // The audit row is read in any language: the neutral form, through the one number formatter.
        return value == null ? "null"
            : com.example.hms.utility.DisplayNumbers.measure(value, 3, java.util.Locale.ROOT);
    }

    public static void requireCheckInOpen(ImagingOrderStatus status) {
        if (!CHECK_IN_OPEN.contains(status)) {
            throw new BusinessException(CHECK_IN_CLOSED_KEY);
        }
    }

    public static void requireRecordOpen(ImagingOrderStatus status) {
        if (!RECORD_OPEN.contains(status)) {
            throw new BusinessException(RECORD_CLOSED_KEY);
        }
    }

    /**
     * Who performed the exam, from the session: whoever starts it, and whoever completes one that
     * has no performer yet (a study started before V346). {@code staff} is the actor's staff row
     * at the order's hospital, or null: it becomes the report's "performed by".
     */
    public static void stampPerformer(ImagingOrder order, ImagingOrderStatus from, ImagingOrderStatus to,
                                      UUID actorUserId, String actorName, Staff staff, LocalDateTime now) {
        if (actorUserId == null) {
            return;
        }
        // A real start only: a same-status write on a study in progress (a schedule, a location)
        // is not performing it and never replaces who did.
        boolean starting = to == ImagingOrderStatus.IN_PROGRESS && from != ImagingOrderStatus.IN_PROGRESS;
        boolean completingUnperformed = to == ImagingOrderStatus.COMPLETED && order.getPerformedByUserId() == null;
        if (starting && order.getExamStartedAt() == null) {
            order.setExamStartedAt(now);
        }
        if (starting || completingUnperformed) {
            order.setPerformedByUserId(actorUserId);
            order.setPerformedByName(actorName);
            order.setPerformedByStaffId(staff != null ? staff.getId() : null);
        }
    }
}
