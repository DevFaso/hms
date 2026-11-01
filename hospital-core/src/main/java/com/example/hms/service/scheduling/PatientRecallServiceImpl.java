package com.example.hms.service.scheduling;

import com.example.hms.enums.RecallStatus;
import com.example.hms.enums.RecallType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Appointment;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.payload.dto.scheduling.RecallRequestDTO;
import com.example.hms.payload.dto.scheduling.RecallResponseDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientRecallServiceImpl implements PatientRecallService {

    /** States a recall can still move out of. */
    private static final Set<RecallStatus> OPEN_STATUSES =
        Set.of(RecallStatus.PENDING, RecallStatus.NOTIFIED);

    private final PatientRecallRepository recallRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final StaffRepository staffRepository;
    private final AppointmentRepository appointmentRepository;
    private final RoleValidator roleValidator;
    /** Same clock as {@code RecallReminderService}'s notice window (TimeConfig). */
    private final Clock clock;

    /**
     * QA D1215 (round 23): how far in the past a new recall may be due. A
     * recall is a follow-up still to make; one entered after the fact (the
     * visit due last week that nobody booked) is legitimate, so a month back
     * is accepted: that covers a recall written from a monthly review of
     * missed follow-ups. A due date further back (the QA's 01/01/2020) is a
     * typing error that would sit at the top of the list as overdue for good.
     */
    static final int PAST_DUE_TOLERANCE_DAYS = 30;

    static void requireRecentDueDate(java.time.LocalDate dueDate, java.time.LocalDate today) {
        if (dueDate != null && dueDate.isBefore(today.minusDays(PAST_DUE_TOLERANCE_DAYS))) {
            throw new com.example.hms.exception.FieldBusinessException("dueDate", "recall.dueDate.tooFarInPast",
                String.valueOf(PAST_DUE_TOLERANCE_DAYS));
        }
    }

    @Override
    @Transactional
    public RecallResponseDTO createRecall(RecallRequestDTO request, UUID hospitalId,
                                          String actorUsername) {
        Hospital hospital = hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", hospitalId));
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));
        if (!patient.isRegisteredInHospital(hospitalId)) {
            throw new BusinessException("recall.patient.notRegistered");
        }
        requireRecentDueDate(request.getDueDate(), com.example.hms.utility.HospitalCalendar.today(hospital, clock));

        Department department = null;
        if (request.getDepartmentId() != null) {
            department = departmentRepository.findById(request.getDepartmentId())
                .filter(d -> d.getHospital() != null && hospitalId.equals(d.getHospital().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("department.notFound", request.getDepartmentId()));
        }
        Staff provider = request.getPreferredProviderId() != null
            ? staffRepository.findById(request.getPreferredProviderId()).orElse(null)
            : null;

        PatientRecall recall = recallRepository.save(PatientRecall.builder()
            .patient(patient)
            .hospital(hospital)
            .department(department)
            .preferredProvider(provider)
            .recallType(request.getRecallType() != null ? request.getRecallType() : RecallType.FOLLOW_UP)
            .dueDate(request.getDueDate())
            .reason(request.getReason())
            .notes(request.getNotes())
            .createdBy(actorUsername)
            .build());
        return toDto(recall, hospitalId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecallResponseDTO> getRecalls(UUID hospitalId, RecallStatus status, UUID patientId) {
        return recallRepository.findForHospital(hospitalId, status, patientId).stream()
            .map(recall -> toDto(recall, hospitalId))
            .toList();
    }

    @Override
    @Transactional
    public RecallResponseDTO closeRecall(UUID recallId, UUID hospitalId) {
        PatientRecall recall = loadScoped(recallId, hospitalId);
        // SCHEDULED may close too — that is the recall completing normally.
        if (!OPEN_STATUSES.contains(recall.getStatus())
            && recall.getStatus() != RecallStatus.SCHEDULED) {
            // QA D1187 (round 22): the refusal is a key, in the reader's language.
            throw new BusinessException(recall.getStatus() == RecallStatus.CANCELLED
                ? "recall.close.alreadyCancelled" : "recall.close.alreadyClosed");
        }
        return toDto(finish(recall, RecallStatus.CLOSED), hospitalId);
    }

    @Override
    @Transactional
    public RecallResponseDTO cancelRecall(UUID recallId, UUID hospitalId, String reason) {
        PatientRecall recall = loadScoped(recallId, hospitalId);
        if (!OPEN_STATUSES.contains(recall.getStatus())) {
            throw new BusinessException("recall.cancel.notOpen");
        }
        // QA D1157 (round 21): a cancelled recall says why, on the server.
        recall.setCloseReason(com.example.hms.service.support.CancelledOrders.requireCancelReason(reason));
        return toDto(finish(recall, RecallStatus.CANCELLED), hospitalId);
    }

    @Override
    @Transactional
    public RecallResponseDTO linkAppointment(UUID recallId, UUID hospitalId, UUID appointmentId) {
        PatientRecall recall = loadScoped(recallId, hospitalId);
        if (!OPEN_STATUSES.contains(recall.getStatus())) {
            throw new BusinessException("recall.link.notOpen");
        }
        Appointment appointment = appointmentRepository.findById(appointmentId)
            .filter(a -> a.getHospital() != null && hospitalId.equals(a.getHospital().getId()))
            .orElseThrow(() -> new ResourceNotFoundException("appointment.notFound", appointmentId));
        if (appointment.getPatient() == null
            || !recall.getPatient().getId().equals(appointment.getPatient().getId())) {
            throw new BusinessException("recall.link.otherPatient");
        }
        recall.setLinkedAppointment(appointment);
        recall.setStatus(RecallStatus.SCHEDULED);
        return toDto(recallRepository.save(recall), hospitalId);
    }

    /** QA D985: the booking request property a recall refusal is about. */
    private static final String RECALL_FIELD = "recallId";

    /** QA D985: how early a visit may come and still answer a recall. */
    private static final int RECALL_EARLY_DAYS = 30;

    @Override
    @Transactional
    public void scheduleOpenRecallFor(Appointment appointment, UUID recallId) {
        if (appointment == null || appointment.getPatient() == null || appointment.getHospital() == null) {
            return;
        }
        UUID hospitalId = appointment.getHospital().getId();
        UUID patientId = appointment.getPatient().getId();
        java.util.Optional<PatientRecall> target;
        if (recallId != null) {
            // Booked from the recall list: exactly that recall. One that is not
            // this patient's, not this hospital's or no longer open is a refusal,
            // never a silent no-op that leaves the recall chasing the patient.
            // The refusal rolls the booking back and names the recallId field,
            // so the form drops the recall and the next submit books without it.
            PatientRecall named = recallRepository.findByIdAndHospital_Id(recallId, hospitalId)
                .orElseThrow(() -> new FieldBusinessException(RECALL_FIELD, "recall.book.notFound"));
            if (!OPEN_STATUSES.contains(named.getStatus())) {
                throw new FieldBusinessException(RECALL_FIELD, "recall.book.notOpen");
            }
            if (named.getPatient() == null || !patientId.equals(named.getPatient().getId())) {
                throw new FieldBusinessException(RECALL_FIELD, "recall.book.otherPatient");
            }
            target = java.util.Optional.of(named);
        } else {
            target = recallRepository.findByPatient_IdAndStatusIn(patientId, OPEN_STATUSES).stream()
                .filter(r -> r.getHospital() != null && hospitalId.equals(r.getHospital().getId()))
                .filter(r -> asksForThisVisit(r, appointment))
                .min(java.util.Comparator.comparing(PatientRecall::getDueDate,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        }
        target.ifPresent(recall -> {
            recall.setLinkedAppointment(appointment);
            recall.setStatus(RecallStatus.SCHEDULED);
            recallRepository.save(recall);
        });
    }

    /**
     * A recall visibly asks for this visit when it names the visit's
     * department or clinician and the visit is not far ahead of its due date.
     * An acute visit in another service, or months before the recall is due,
     * does not answer it.
     */
    private static boolean asksForThisVisit(PatientRecall recall, Appointment appointment) {
        boolean sameDepartment = recall.getDepartment() != null && appointment.getDepartment() != null
            && recall.getDepartment().getId().equals(appointment.getDepartment().getId());
        boolean sameClinician = recall.getPreferredProvider() != null && appointment.getStaff() != null
            && recall.getPreferredProvider().getId().equals(appointment.getStaff().getId());
        if (!sameDepartment && !sameClinician) {
            return false;
        }
        return recall.getDueDate() == null || appointment.getAppointmentDate() == null
            || !appointment.getAppointmentDate().isBefore(recall.getDueDate().minusDays(RECALL_EARLY_DAYS));
    }

    @Override
    @Transactional
    public void completeRecallsOf(UUID appointmentId) {
        if (appointmentId == null) {
            return;
        }
        for (PatientRecall recall : recallRepository.findByLinkedAppointment_IdAndStatus(
                appointmentId, RecallStatus.SCHEDULED)) {
            finish(recall, RecallStatus.CLOSED);
        }
    }

    @Override
    @Transactional
    public void reopenRecallsOf(UUID appointmentId) {
        if (appointmentId == null) {
            return;
        }
        for (PatientRecall recall : recallRepository.findByLinkedAppointment_IdAndStatus(
                appointmentId, RecallStatus.SCHEDULED)) {
            // Open again, but still pointing at the visit (review round 18): if
            // that visit is brought back (a missed one moved through
            // RESCHEDULED), the recall follows it; a new booking for the recall
            // replaces the link.
            recall.setStatus(RecallStatus.PENDING);
            recallRepository.save(recall);
        }
    }

    @Override
    @Transactional
    public void rescheduleRecallsOf(UUID appointmentId) {
        if (appointmentId == null) {
            return;
        }
        for (PatientRecall recall : recallRepository.findByLinkedAppointment_IdAndStatus(
                appointmentId, RecallStatus.PENDING)) {
            recall.setStatus(RecallStatus.SCHEDULED);
            recallRepository.save(recall);
        }
    }

    /* ---------------- helpers ---------------- */

    private PatientRecall finish(PatientRecall recall, RecallStatus status) {
        recall.setStatus(status);
        recall.setClosedAt(LocalDateTime.now(clock));
        recall.setClosedByUserId(roleValidator.getCurrentUserId());
        return recallRepository.save(recall);
    }

    private PatientRecall loadScoped(UUID recallId, UUID hospitalId) {
        return recallRepository.findByIdAndHospital_Id(recallId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("recall.notFound", recallId));
    }

    private RecallResponseDTO toDto(PatientRecall recall, UUID hospitalId) {
        Patient patient = recall.getPatient();
        String mrn = patient.getHospitalRegistrations() == null ? null
            : patient.getHospitalRegistrations().stream()
                .filter(r -> r.getHospital() != null && hospitalId.equals(r.getHospital().getId()))
                .map(PatientHospitalRegistration::getMrn)
                .findFirst().orElse(null);
        String providerName = null;
        if (recall.getPreferredProvider() != null && recall.getPreferredProvider().getUser() != null) {
            var user = recall.getPreferredProvider().getUser();
            providerName = user.getFirstName() + " " + user.getLastName();
        }
        return RecallResponseDTO.builder()
            .id(recall.getId())
            .hospitalId(recall.getHospital().getId())
            .patientId(patient.getId())
            .patientName(patient.getFullName())
            .mrn(mrn)
            .departmentId(recall.getDepartment() != null ? recall.getDepartment().getId() : null)
            .departmentName(recall.getDepartment() != null ? recall.getDepartment().getName() : null)
            .preferredProviderId(recall.getPreferredProvider() != null
                ? recall.getPreferredProvider().getId() : null)
            .preferredProviderName(providerName)
            .encounterId(recall.getEncounter() != null ? recall.getEncounter().getId() : null)
            .recallType(recall.getRecallType())
            .status(recall.getStatus())
            .source(recall.getSource())
            .dueDate(recall.getDueDate())
            .reason(recall.getReason())
            .notes(recall.getNotes())
            .notifiedAt(recall.getNotifiedAt())
            .linkedAppointmentId(recall.getLinkedAppointment() != null
                ? recall.getLinkedAppointment().getId() : null)
            .closedAt(recall.getClosedAt())
            .closeReason(recall.getCloseReason())
            .createdAt(recall.getCreatedAt())
            .createdBy(recall.getCreatedBy())
            .build();
    }
}
