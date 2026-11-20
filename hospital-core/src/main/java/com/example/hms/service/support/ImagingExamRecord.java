package com.example.hms.service.support;

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
public final class ImagingExamRecord {

    static final String TECHNICIAN_STEPS_KEY = "imaging.technician.examStepsOnly";
    static final String CHECK_IN_CLOSED_KEY = "imaging.exam.checkIn.notOpen";
    static final String RECORD_CLOSED_KEY = "imaging.exam.record.notOpen";
    static final String RECORD_EMPTY_KEY = "imaging.exam.record.empty";

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
        boolean step = to == ImagingOrderStatus.IN_PROGRESS && from != ImagingOrderStatus.IN_PROGRESS
            || to == ImagingOrderStatus.COMPLETED
            || to == ImagingOrderStatus.CANCELLED && from == ImagingOrderStatus.IN_PROGRESS;
        boolean changesTheOrder = request.getScheduledDate() != null || request.getScheduledTime() != null
            || request.getAppointmentLocation() != null || request.getWorkflowNotes() != null
            || request.getRequiresAuthorization() != null || request.getAuthorizationNumber() != null;
        if (!step || changesTheOrder) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw(TECHNICIAN_STEPS_KEY));
        }
    }

    public static void requireCheckInOpen(ImagingOrderStatus status) {
        if (!CHECK_IN_OPEN.contains(status)) {
            throw new BusinessException(CHECK_IN_CLOSED_KEY);
        }
    }

    /**
     * An exam record says something: a note, whether contrast was given, its details or the dose.
     * An empty one is refused on the note, before any lookup (the portal refuses it the same way).
     */
    public static void requireSomethingRecorded(String technicalNote, Boolean contrastAdministered,
                                                String contrastDetails, java.math.BigDecimal radiationDoseMgy) {
        boolean nothing = isBlank(technicalNote) && contrastAdministered == null && isBlank(contrastDetails)
            && radiationDoseMgy == null;
        if (nothing) {
            throw new com.example.hms.exception.FieldBusinessException("technicalNote", RECORD_EMPTY_KEY);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
    public static void stampPerformer(ImagingOrder order, ImagingOrderStatus to, UUID actorUserId, String actorName,
                                      Staff staff, LocalDateTime now) {
        if (actorUserId == null) {
            return;
        }
        boolean starting = to == ImagingOrderStatus.IN_PROGRESS;
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
