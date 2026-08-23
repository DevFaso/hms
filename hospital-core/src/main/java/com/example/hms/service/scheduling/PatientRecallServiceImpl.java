package com.example.hms.service.scheduling;

import com.example.hms.enums.RecallStatus;
import com.example.hms.enums.RecallType;
import com.example.hms.exception.BusinessException;
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

    @Override
    @Transactional
    public RecallResponseDTO createRecall(RecallRequestDTO request, UUID hospitalId,
                                          String actorUsername) {
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Patient not found with ID: " + request.getPatientId()));
        if (!patient.isRegisteredInHospital(hospitalId)) {
            throw new BusinessException("The patient is not registered at this hospital.");
        }

        Department department = null;
        if (request.getDepartmentId() != null) {
            department = departmentRepository.findById(request.getDepartmentId())
                .filter(d -> d.getHospital() != null && hospitalId.equals(d.getHospital().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Department not found"));
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
            throw new BusinessException("This recall is already " + recall.getStatus() + ".");
        }
        return toDto(finish(recall, RecallStatus.CLOSED), hospitalId);
    }

    @Override
    @Transactional
    public RecallResponseDTO cancelRecall(UUID recallId, UUID hospitalId) {
        PatientRecall recall = loadScoped(recallId, hospitalId);
        if (!OPEN_STATUSES.contains(recall.getStatus())) {
            throw new BusinessException("Only a pending or notified recall can be cancelled.");
        }
        return toDto(finish(recall, RecallStatus.CANCELLED), hospitalId);
    }

    @Override
    @Transactional
    public RecallResponseDTO linkAppointment(UUID recallId, UUID hospitalId, UUID appointmentId) {
        PatientRecall recall = loadScoped(recallId, hospitalId);
        if (!OPEN_STATUSES.contains(recall.getStatus())) {
            throw new BusinessException("Only a pending or notified recall can be scheduled.");
        }
        Appointment appointment = appointmentRepository.findById(appointmentId)
            .filter(a -> a.getHospital() != null && hospitalId.equals(a.getHospital().getId()))
            .orElseThrow(() -> new ResourceNotFoundException(
                "Appointment not found with ID: " + appointmentId));
        if (appointment.getPatient() == null
            || !recall.getPatient().getId().equals(appointment.getPatient().getId())) {
            throw new BusinessException("That appointment belongs to a different patient.");
        }
        recall.setLinkedAppointment(appointment);
        recall.setStatus(RecallStatus.SCHEDULED);
        return toDto(recallRepository.save(recall), hospitalId);
    }

    /* ---------------- helpers ---------------- */

    private PatientRecall finish(PatientRecall recall, RecallStatus status) {
        recall.setStatus(status);
        recall.setClosedAt(LocalDateTime.now());
        recall.setClosedByUserId(roleValidator.getCurrentUserId());
        return recallRepository.save(recall);
    }

    private PatientRecall loadScoped(UUID recallId, UUID hospitalId) {
        return recallRepository.findByIdAndHospital_Id(recallId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Recall not found with ID: " + recallId));
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
            .patientName(patient.getFirstName() + " " + patient.getLastName())
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
            .createdAt(recall.getCreatedAt())
            .createdBy(recall.getCreatedBy())
            .build();
    }
}
