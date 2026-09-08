package com.example.hms.service.recordaccess;

import com.example.hms.config.RecordAccessProperties;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.PanelAssignmentStatus;
import com.example.hms.enums.TreatmentRelationshipKind;
import com.example.hms.model.Admission;
import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.LabOrder;
import com.example.hms.model.PanelAssignment;
import com.example.hms.model.Staff;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.PanelAssignmentRepository;
import com.example.hms.repository.StaffRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Walks the carriers already in the schema, strongest first, and applies the
 * decay rule from {@link RecordAccessProperties}. Every window is read from
 * there; nothing here hard-codes a duration.
 *
 * <p>Carriers and their rule, in the order tried:
 * <ol>
 *   <li><b>Admission</b> — live while PENDING / ACTIVE / ON_LEAVE /
 *       AWAITING_DISCHARGE; DISCHARGED or TRANSFERRED keeps a post-visit tail
 *       from the actual discharge time; CANCELLED never counts.</li>
 *   <li><b>Encounter</b> — live while not COMPLETED / CANCELLED; COMPLETED keeps
 *       the tail from checkout (or the encounter date if checkout was never
 *       stamped); CANCELLED never counts.</li>
 *   <li><b>Appointment</b> — live for SCHEDULED / CONFIRMED / PENDING /
 *       RESCHEDULED / CHECKED_IN / IN_PROGRESS when dated inside
 *       [today − lookback, today + lookahead]; COMPLETED keeps the tail from
 *       the appointment's end; CANCELLED / NO_SHOW / FAILED never count.</li>
 *   <li><b>Panel assignment</b> (V149) — live while ACTIVE, no expiry.</li>
 *   <li><b>Open order</b> — a lab or imaging order not yet COMPLETED or
 *       CANCELLED. Weakest, because an order can outlive the visit that
 *       placed it.</li>
 * </ol>
 *
 * <p>Repositories are read through derived finders on purpose: the tenant
 * filter intercepts only Specification paths and {@code findById}, so these
 * queries see the carrier rows at the acting hospital regardless of which
 * hospital the patient row itself is scoped to. That is the point — a patient
 * registered at A who is admitted at B has a carrier at B.
 */
@Service
public class TreatmentRelationshipResolverImpl implements TreatmentRelationshipResolver {

    private static final Set<AdmissionStatus> ADMISSION_LIVE = EnumSet.of(
        AdmissionStatus.PENDING, AdmissionStatus.ACTIVE,
        AdmissionStatus.ON_LEAVE, AdmissionStatus.AWAITING_DISCHARGE);
    private static final Set<AdmissionStatus> ADMISSION_TAILED = EnumSet.of(
        AdmissionStatus.DISCHARGED, AdmissionStatus.TRANSFERRED);

    private static final Set<EncounterStatus> ENCOUNTER_TERMINAL = EnumSet.of(
        EncounterStatus.COMPLETED, EncounterStatus.CANCELLED);

    private static final Set<AppointmentStatus> APPOINTMENT_LIVE = EnumSet.of(
        AppointmentStatus.SCHEDULED, AppointmentStatus.CONFIRMED, AppointmentStatus.PENDING,
        AppointmentStatus.RESCHEDULED, AppointmentStatus.CHECKED_IN, AppointmentStatus.IN_PROGRESS);

    private final AdmissionRepository admissionRepository;
    private final EncounterRepository encounterRepository;
    private final AppointmentRepository appointmentRepository;
    private final PanelAssignmentRepository panelAssignmentRepository;
    private final LabOrderRepository labOrderRepository;
    private final ImagingOrderRepository imagingOrderRepository;
    private final StaffRepository staffRepository;
    private final RecordAccessProperties properties;
    private final Clock clock;

    public TreatmentRelationshipResolverImpl(AdmissionRepository admissionRepository,
                                             EncounterRepository encounterRepository,
                                             AppointmentRepository appointmentRepository,
                                             PanelAssignmentRepository panelAssignmentRepository,
                                             LabOrderRepository labOrderRepository,
                                             ImagingOrderRepository imagingOrderRepository,
                                             StaffRepository staffRepository,
                                             RecordAccessProperties properties,
                                             ObjectProvider<Clock> clock) {
        this.admissionRepository = admissionRepository;
        this.encounterRepository = encounterRepository;
        this.appointmentRepository = appointmentRepository;
        this.panelAssignmentRepository = panelAssignmentRepository;
        this.labOrderRepository = labOrderRepository;
        this.imagingOrderRepository = imagingOrderRepository;
        this.staffRepository = staffRepository;
        this.properties = properties;
        // No Clock bean is registered in the app; tests supply one to pin "now".
        this.clock = clock.getIfAvailable(Clock::systemDefaultZone);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TreatmentRelationship> resolve(UUID patientId, UUID hospitalId, UUID actorUserId) {
        if (patientId == null || hospitalId == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // The actor's staff row here, if any — only to mark direct attachment.
        UUID actorStaffId = actorUserId == null ? null
            : staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)
                .map(Staff::getId).orElse(null);

        Optional<TreatmentRelationship> hit = fromAdmissions(patientId, hospitalId, actorStaffId, now);
        if (hit.isEmpty()) hit = fromEncounters(patientId, hospitalId, actorStaffId, now);
        if (hit.isEmpty()) hit = fromAppointments(patientId, hospitalId, actorStaffId, now);
        if (hit.isEmpty()) hit = fromPanel(patientId, hospitalId, actorStaffId);
        if (hit.isEmpty()) hit = fromOrders(patientId, hospitalId, actorStaffId, actorUserId);
        return hit;
    }

    // ---------------------------------------------------------------- carriers

    private Optional<TreatmentRelationship> fromAdmissions(UUID patientId, UUID hospitalId,
                                                           UUID actorStaffId, LocalDateTime now) {
        List<Admission> rows = admissionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId);
        TreatmentRelationship best = null;
        for (Admission a : rows) {
            AdmissionStatus status = a.getStatus();
            LocalDateTime expires;
            if (status != null && ADMISSION_LIVE.contains(status)) {
                expires = null;
            } else if (status != null && ADMISSION_TAILED.contains(status)
                && a.getActualDischargeDateTime() != null) {
                expires = a.getActualDischargeDateTime().plus(properties.getPostVisitTail());
                if (!expires.isAfter(now)) continue;
            } else {
                continue;
            }
            boolean attached = actorStaffId != null && (
                idEquals(a.getAttendingPhysician(), actorStaffId)
                    || idEquals(a.getAdmittingProvider(), actorStaffId)
                    || idEquals(a.getDischargingProvider(), actorStaffId));
            TreatmentRelationship candidate = new TreatmentRelationship(
                TreatmentRelationshipKind.ACTIVE_ADMISSION, a.getId(), hospitalId, patientId,
                a.getAdmissionDateTime(), expires, attached);
            best = prefer(best, candidate);
        }
        return Optional.ofNullable(best);
    }

    private Optional<TreatmentRelationship> fromEncounters(UUID patientId, UUID hospitalId,
                                                           UUID actorStaffId, LocalDateTime now) {
        List<Encounter> rows = encounterRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId);
        TreatmentRelationship best = null;
        for (Encounter e : rows) {
            EncounterStatus status = e.getStatus();
            LocalDateTime expires;
            if (status == EncounterStatus.CANCELLED) {
                continue;
            } else if (status == EncounterStatus.COMPLETED) {
                LocalDateTime end = e.getCheckoutTimestamp() != null ? e.getCheckoutTimestamp() : e.getEncounterDate();
                if (end == null) continue;
                expires = end.plus(properties.getPostVisitTail());
                if (!expires.isAfter(now)) continue;
            } else {
                expires = null;
            }
            boolean attached = actorStaffId != null && idEquals(e.getStaff(), actorStaffId);
            TreatmentRelationship candidate = new TreatmentRelationship(
                TreatmentRelationshipKind.OPEN_ENCOUNTER, e.getId(), hospitalId, patientId,
                e.getArrivalTimestamp() != null ? e.getArrivalTimestamp() : e.getEncounterDate(),
                expires, attached);
            best = prefer(best, candidate);
        }
        return Optional.ofNullable(best);
    }

    private Optional<TreatmentRelationship> fromAppointments(UUID patientId, UUID hospitalId,
                                                             UUID actorStaffId, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        // The window is widened by the tail on the past side so a COMPLETED
        // appointment inside its tail is still fetched.
        LocalDate from = today.minus(properties.getAppointmentLookback())
            .minusDays(properties.getPostVisitTail().toDays());
        LocalDate to = today.plus(properties.getAppointmentLookahead());
        List<Appointment> rows = appointmentRepository
            .findByPatient_IdAndHospital_IdAndAppointmentDateBetween(patientId, hospitalId, from, to);

        LocalDate liveFrom = today.minus(properties.getAppointmentLookback());
        TreatmentRelationship best = null;
        for (Appointment ap : rows) {
            AppointmentStatus status = ap.getStatus();
            LocalDate date = ap.getAppointmentDate();
            if (status == null || date == null) continue;
            LocalDateTime expires;
            if (APPOINTMENT_LIVE.contains(status)) {
                if (date.isBefore(liveFrom) || date.isAfter(to)) continue;
                expires = null;
            } else if (status == AppointmentStatus.COMPLETED) {
                LocalTime endTime = ap.getEndTime() != null ? ap.getEndTime() : LocalTime.MAX;
                expires = LocalDateTime.of(date, endTime).plus(properties.getPostVisitTail());
                if (!expires.isAfter(now)) continue;
            } else {
                continue; // CANCELLED, NO_SHOW, FAILED and anything new: never a relationship
            }
            boolean attached = actorStaffId != null && idEquals(ap.getStaff(), actorStaffId);
            LocalTime start = ap.getStartTime() != null ? ap.getStartTime() : LocalTime.MIDNIGHT;
            TreatmentRelationship candidate = new TreatmentRelationship(
                TreatmentRelationshipKind.SCHEDULED_APPOINTMENT, ap.getId(), hospitalId, patientId,
                LocalDateTime.of(date, start), expires, attached);
            best = prefer(best, candidate);
        }
        return Optional.ofNullable(best);
    }

    private Optional<TreatmentRelationship> fromPanel(UUID patientId, UUID hospitalId, UUID actorStaffId) {
        List<PanelAssignment> rows = panelAssignmentRepository
            .findByPatient_IdAndHospital_IdAndStatus(patientId, hospitalId, PanelAssignmentStatus.ACTIVE);
        TreatmentRelationship best = null;
        for (PanelAssignment p : rows) {
            boolean attached = actorStaffId != null && idEquals(p.getProviderStaff(), actorStaffId);
            LocalDateTime since = p.getAssignedOn() != null ? p.getAssignedOn().atStartOfDay() : null;
            best = prefer(best, new TreatmentRelationship(
                TreatmentRelationshipKind.PANEL_ASSIGNMENT, p.getId(), hospitalId, patientId,
                since, null, attached));
        }
        return Optional.ofNullable(best);
    }

    private Optional<TreatmentRelationship> fromOrders(UUID patientId, UUID hospitalId,
                                                       UUID actorStaffId, UUID actorUserId) {
        for (LabOrder o : labOrderRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)) {
            if (o.getStatus() == null || isTerminalByName(o.getStatus().name())) continue;
            boolean attached = actorStaffId != null && idEquals(o.getOrderingStaff(), actorStaffId);
            return Optional.of(new TreatmentRelationship(
                TreatmentRelationshipKind.OPEN_ORDER, o.getId(), hospitalId, patientId,
                o.getCreatedAt(), null, attached));
        }
        for (ImagingOrder o : imagingOrderRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)) {
            if (o.getStatus() == null || isTerminalByName(o.getStatus().name())) continue;
            boolean attached = actorUserId != null && actorUserId.equals(o.getOrderingProviderUserId());
            return Optional.of(new TreatmentRelationship(
                TreatmentRelationshipKind.OPEN_ORDER, o.getId(), hospitalId, patientId,
                o.getCreatedAt(), null, attached));
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Terminal for an order means the loop is closed: COMPLETED, or CANCELLED
     * where the enum has it. Checked by name so the two order enums, which do
     * not share a type, get one rule — and so a status added later defaults to
     * "open", which is the safe direction for a relationship carrier.
     */
    private static boolean isTerminalByName(String name) {
        return "COMPLETED".equals(name) || "CANCELLED".equals(name);
    }

    /** Between two live carriers of the same kind, keep the one that lapses later (null = never). */
    private static TreatmentRelationship prefer(TreatmentRelationship current, TreatmentRelationship candidate) {
        if (current == null) return candidate;
        if (current.expiresAt() == null) return current;
        if (candidate.expiresAt() == null) return candidate;
        return candidate.expiresAt().isAfter(current.expiresAt()) ? candidate : current;
    }

    private static boolean idEquals(Staff staff, UUID id) {
        return staff != null && Objects.equals(staff.getId(), id);
    }
}
