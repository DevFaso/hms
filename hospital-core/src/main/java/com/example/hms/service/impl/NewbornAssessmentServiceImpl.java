package com.example.hms.service.impl;

import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.enums.NewbornAlertType;
import com.example.hms.enums.NewbornFollowUpAction;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.NewbornAssessmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.model.neonatal.NewbornAssessmentAlert;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.NewbornAssessmentService;
import com.example.hms.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class NewbornAssessmentServiceImpl implements NewbornAssessmentService {

    private static final int LOW_APGAR_THRESHOLD = 7;
    private static final double LOW_TEMP_THRESHOLD = 36.5;
    private static final double HIGH_TEMP_THRESHOLD = 37.5;
    private static final double CRITICAL_LOW_TEMP = 36.0;
    private static final double CRITICAL_HIGH_TEMP = 38.0;
    private static final int HEART_RATE_LOW = 100;
    private static final int HEART_RATE_HIGH = 180;
    private static final int RESP_RATE_LOW = 30;
    private static final int RESP_RATE_HIGH = 70;
    private static final int OXYGEN_CRITICAL = 90;
    private static final int GLUCOSE_CRITICAL = 45;
    private static final int DEFAULT_RECENT_LIMIT = 10;
    private static final int MAX_RECENT_LIMIT = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final NewbornAssessmentRepository assessmentRepository;
    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final NewbornAssessmentMapper mapper;

    @Override
    public NewbornAssessmentResponseDTO recordAssessment(UUID patientId,
                                                         NewbornAssessmentRequestDTO request,
                                                         UUID recorderUserId) {
        Objects.requireNonNull(patientId, "patientId is required");
        if (request == null) {
            throw new BusinessException("Newborn assessment request payload is required.");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + patientId));

        PatientHospitalRegistration registration = resolveRegistration(patient, request.getRegistrationId(), request.getHospitalId());
        Hospital hospital = resolveHospital(patient, registration, request.getHospitalId());
        Staff staff = resolveRecorderStaff(request.getRecordedByStaffId(), recorderUserId, hospital);
        User documentedBy = resolveRecorderUser(recorderUserId);

        NewbornAssessment assessment = buildAssessment(patient, hospital, registration, staff, documentedBy, request);
        evaluateAssessment(assessment);

        NewbornAssessment saved = assessmentRepository.save(assessment);
        publishAlerts(saved, documentedBy);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NewbornAssessmentResponseDTO> getRecentAssessments(UUID patientId,
                                                                   UUID hospitalId,
                                                                   int limit) {
        int effectiveLimit = sanitizeLimit(limit, DEFAULT_RECENT_LIMIT, MAX_RECENT_LIMIT);
        List<NewbornAssessment> assessments;
        if (hospitalId != null) {
            assessments = assessmentRepository.findByPatient_IdAndHospital_IdOrderByAssessmentTimeDesc(
                patientId, hospitalId, PageRequest.of(0, effectiveLimit));
        } else {
            assessments = assessmentRepository.findWithinRange(
                patientId, null, null, null, PageRequest.of(0, effectiveLimit));
        }
        return assessments.stream()
            .map(mapper::toResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NewbornAssessmentResponseDTO> searchAssessments(UUID patientId,
                                                                UUID hospitalId,
                                                                LocalDateTime from,
                                                                LocalDateTime to,
                                                                int page,
                                                                int size) {
        int safePage = Math.max(page, 0);
        int safeSize = sanitizeLimit(size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        List<NewbornAssessment> assessments = assessmentRepository.findWithinRange(
            patientId,
            hospitalId,
            from,
            to,
            PageRequest.of(safePage, safeSize)
        );
        return assessments.stream()
            .map(mapper::toResponse)
            .toList();
    }

    private NewbornAssessment buildAssessment(Patient patient,
                                              Hospital hospital,
                                              PatientHospitalRegistration registration,
                                              Staff staff,
                                              User documentedBy,
                                              NewbornAssessmentRequestDTO request) {
        LocalDateTime assessmentTime = request.getAssessmentTime() != null
            ? request.getAssessmentTime()
            : LocalDateTime.now();
        NewbornAssessment assessment = NewbornAssessment.builder()
            .patient(patient)
            .hospital(hospital)
            .registration(registration)
            .recordedByStaff(staff)
            .documentedBy(documentedBy)
            .assessmentTime(assessmentTime)
            .documentedAt(LocalDateTime.now())
            .lateEntry(Boolean.TRUE.equals(request.getLateEntry()))
            .originalEntryTime(request.getOriginalEntryTime())
            .apgarOneMinute(request.getApgarOneMinute())
            .apgarFiveMinute(request.getApgarFiveMinute())
            .apgarTenMinute(request.getApgarTenMinute())
            .apgarNotes(request.getApgarNotes())
            .temperatureCelsius(request.getTemperatureCelsius())
            .heartRateBpm(request.getHeartRateBpm())
            .respirationsPerMin(request.getRespirationsPerMin())
            .systolicBpMmHg(request.getSystolicBpMmHg())
            .diastolicBpMmHg(request.getDiastolicBpMmHg())
            .oxygenSaturationPercent(request.getOxygenSaturationPercent())
            .glucoseMgDl(request.getGlucoseMgDl())
            .examGeneralAppearance(request.getExamGeneralAppearance())
            .examHeadNeck(request.getExamHeadNeck())
            .examChestLungs(request.getExamChestLungs())
            .examCardiac(request.getExamCardiac())
            .examAbdomen(request.getExamAbdomen())
            .examGenitourinary(request.getExamGenitourinary())
            .examSkin(request.getExamSkin())
            .examNeurological(request.getExamNeurological())
            .examMusculoskeletal(request.getExamMusculoskeletal())
            .examNotes(request.getExamNotes())
            .followUpNotes(request.getFollowUpNotes())
            .parentEducationNotes(request.getParentEducationNotes())
            .parentEducationCompleted(Boolean.TRUE.equals(request.getParentEducationCompleted()))
            .escalationRecommended(Boolean.TRUE.equals(request.getEscalationRecommended()))
            .respiratorySupportInitiated(Boolean.TRUE.equals(request.getRespiratorySupportInitiated()))
            .glucoseProtocolInitiated(Boolean.TRUE.equals(request.getGlucoseProtocolInitiated()))
            .thermoregulationSupportInitiated(Boolean.TRUE.equals(request.getThermoregulationSupportInitiated()))
            .build();
        if (request.getFollowUpActions() != null) {
            assessment.addFollowUpActions(request.getFollowUpActions());
        }
        if (request.getParentEducationTopics() != null) {
            assessment.addParentEducationTopics(request.getParentEducationTopics());
        }
        return assessment;
    }

    private void evaluateAssessment(NewbornAssessment assessment) {
        List<NewbornAssessmentAlert> alerts = new ArrayList<>();
        boolean urgentDetected = false;

        urgentDetected |= evaluateApgar(assessment, alerts);
        urgentDetected |= evaluateTemperature(assessment, alerts);
        urgentDetected |= evaluateCardiorespiratory(assessment, alerts);
        urgentDetected |= evaluateOxygenation(assessment, alerts);
        urgentDetected |= evaluateGlucose(assessment, alerts);

        alerts.forEach(assessment::addAlert);
        if (urgentDetected) {
            assessment.markEscalationRecommended();
        }
    }

    private boolean evaluateApgar(NewbornAssessment assessment, List<NewbornAssessmentAlert> alerts) {
        boolean urgent = false;
        Integer oneMinute = assessment.getApgarOneMinute();
        Integer fiveMinute = assessment.getApgarFiveMinute();
        Integer tenMinute = assessment.getApgarTenMinute();

        if (oneMinute != null && oneMinute < LOW_APGAR_THRESHOLD) {
            alerts.add(buildAlert(NewbornAlertType.APGAR, NewbornAlertSeverity.URGENT,
                "newborn-apgar-1", "Apgar score under 7 at 1 minute.", "Apgar 1 minute"));
            assessment.addFollowUpAction(NewbornFollowUpAction.NICU_CONSULT);
            urgent = true;
        }
        if (fiveMinute != null && fiveMinute < LOW_APGAR_THRESHOLD) {
            alerts.add(buildAlert(NewbornAlertType.APGAR, NewbornAlertSeverity.URGENT,
                "newborn-apgar-5", "Apgar score under 7 at 5 minutes.", "Apgar 5 minute"));
            assessment.addFollowUpAction(NewbornFollowUpAction.NICU_CONSULT);
            urgent = true;
        }
        if (!urgent && tenMinute != null && tenMinute < LOW_APGAR_THRESHOLD) {
            alerts.add(buildAlert(NewbornAlertType.APGAR, NewbornAlertSeverity.CAUTION,
                "newborn-apgar-10", "Apgar score remains below 7 at 10 minutes; continue close monitoring.",
                "Apgar 10 minute"));
        }
        return urgent;
    }

    private boolean evaluateTemperature(NewbornAssessment assessment, List<NewbornAssessmentAlert> alerts) {
        Double temperature = assessment.getTemperatureCelsius();
        if (temperature == null) {
            return false;
        }
        if (temperature < CRITICAL_LOW_TEMP || temperature > CRITICAL_HIGH_TEMP) {
            alerts.add(buildAlert(NewbornAlertType.THERMOREGULATION, NewbornAlertSeverity.URGENT,
                "newborn-temperature-critical", "Critical temperature instability detected. Initiate thermoregulation protocol.",
                "Temperature"));
            assessment.addFollowUpAction(NewbornFollowUpAction.THERMAL_SUPPORT);
            if (!assessment.isThermoregulationSupportInitiated()) {
                assessment.setThermoregulationSupportInitiated(true);
            }
            return true;
        }
        if (temperature < LOW_TEMP_THRESHOLD || temperature > HIGH_TEMP_THRESHOLD) {
            alerts.add(buildAlert(NewbornAlertType.THERMOREGULATION, NewbornAlertSeverity.CAUTION,
                "newborn-temperature", "Temperature outside preferred range. Reassess thermoregulation plan.",
                "Temperature"));
        }
        return false;
    }

    private boolean evaluateCardiorespiratory(NewbornAssessment assessment, List<NewbornAssessmentAlert> alerts) {
        boolean urgent = false;
        Integer heartRate = assessment.getHeartRateBpm();
        Integer respirations = assessment.getRespirationsPerMin();

        if (heartRate != null && (heartRate < HEART_RATE_LOW || heartRate > HEART_RATE_HIGH)) {
            alerts.add(buildAlert(NewbornAlertType.CARDIOVASCULAR, NewbornAlertSeverity.URGENT,
                "newborn-heart-rate", "Heart rate outside safe range. Escalate to neonatal team.",
                "Heart Rate"));
            assessment.addFollowUpAction(NewbornFollowUpAction.NICU_CONSULT);
            urgent = true;
        }
        if (respirations != null && (respirations < RESP_RATE_LOW || respirations > RESP_RATE_HIGH)) {
            alerts.add(buildAlert(NewbornAlertType.RESPIRATORY, NewbornAlertSeverity.URGENT,
                "newborn-respiratory", "Respiratory rate outside safe range. Initiate respiratory support.",
                "Respiratory Rate"));
            assessment.addFollowUpAction(NewbornFollowUpAction.RESPIRATORY_SUPPORT);
            if (!assessment.isRespiratorySupportInitiated()) {
                assessment.setRespiratorySupportInitiated(true);
            }
            urgent = true;
        }
        return urgent;
    }

    private boolean evaluateOxygenation(NewbornAssessment assessment, List<NewbornAssessmentAlert> alerts) {
        Integer oxygen = assessment.getOxygenSaturationPercent();
        if (oxygen == null) {
            return false;
        }
        if (oxygen < OXYGEN_CRITICAL) {
            alerts.add(buildAlert(NewbornAlertType.OXYGENATION, NewbornAlertSeverity.URGENT,
                "newborn-oxygenation", "Oxygen saturation under 90%. Provide supplemental oxygen and escalate.",
                "SpO2"));
            assessment.addFollowUpAction(NewbornFollowUpAction.OXYGEN_THERAPY);
            return true;
        }
        return false;
    }

    private boolean evaluateGlucose(NewbornAssessment assessment, List<NewbornAssessmentAlert> alerts) {
        Integer glucose = assessment.getGlucoseMgDl();
        if (glucose == null) {
            return false;
        }
        if (glucose < GLUCOSE_CRITICAL) {
            alerts.add(buildAlert(NewbornAlertType.GLUCOSE, NewbornAlertSeverity.URGENT,
                "newborn-glucose", "Critical hypoglycemia detected. Initiate glucose protocol immediately.",
                "Glucose"));
            assessment.addFollowUpAction(NewbornFollowUpAction.GLUCOSE_MONITORING);
            if (!assessment.isGlucoseProtocolInitiated()) {
                assessment.setGlucoseProtocolInitiated(true);
            }
            return true;
        }
        return false;
    }

    private NewbornAssessmentAlert buildAlert(NewbornAlertType type,
                                              NewbornAlertSeverity severity,
                                              String code,
                                              String message,
                                              String trigger) {
        return NewbornAssessmentAlert.builder()
            .type(type)
            .severity(severity)
            .code(code)
            .message(message)
            .triggeredBy(trigger)
            .build();
    }

    private void publishAlerts(NewbornAssessment assessment, User documentedBy) {
        if (assessment.getAlerts() == null || assessment.getAlerts().isEmpty()) {
            return;
        }
        if (documentedBy == null || documentedBy.getUsername() == null) {
            return;
        }
        String patientName = assessment.getPatient() != null
            ? (assessment.getPatient().getFirstName() + " " + assessment.getPatient().getLastName()).trim()
            : "patient";
        assessment.getAlerts().stream()
            .filter(alert -> alert.getSeverity() == NewbornAlertSeverity.URGENT)
            .forEach(alert -> {
                String message = String.format("%s newborn alert for %s: %s",
                    alert.getType(), patientName.isBlank() ? "patient" : patientName, alert.getMessage());
                try {
                    notificationService.createNotification(message, documentedBy.getUsername());
                } catch (Exception ex) {
                    log.warn("Failed to create newborn alert notification: {}", ex.getMessage());
                }
            });
    }

    private PatientHospitalRegistration resolveRegistration(Patient patient,
                                                             UUID registrationId,
                                                             UUID hospitalId) {
        if (registrationId != null) {
            PatientHospitalRegistration registration = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException("Registration not found with ID: " + registrationId));
            if (!registration.getPatient().getId().equals(patient.getId())) {
                throw new BusinessException("Registration does not belong to the specified patient.");
            }
            return registration;
        }
        if (hospitalId != null) {
            return registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patient.getId(), hospitalId)
                .orElse(null);
        }
        return registrationRepository.findByPatientId(patient.getId()).stream()
            .filter(PatientHospitalRegistration::isActive)
            .findFirst()
            .orElse(null);
    }

    private Hospital resolveHospital(Patient patient,
                                     PatientHospitalRegistration registration,
                                     UUID requestedHospitalId) {
        if (registration != null && registration.getHospital() != null) {
            return registration.getHospital();
        }
        if (requestedHospitalId != null) {
            return hospitalRepository.findById(requestedHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + requestedHospitalId));
        }
        if (patient.getHospitalId() != null) {
            return hospitalRepository.findById(patient.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + patient.getHospitalId()));
        }
        throw new BusinessException("Unable to resolve hospital context for newborn assessment.");
    }

    private Staff resolveRecorderStaff(UUID staffId, UUID recorderUserId, Hospital hospital) {
        if (staffId != null) {
            Staff staff = staffRepository.findByIdAndActiveTrue(staffId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff not found or inactive with ID: " + staffId));
            if (hospital != null && staff.getHospital() != null
                && !hospital.getId().equals(staff.getHospital().getId())) {
                throw new BusinessException("Recorder staff assignment does not match resolved hospital context.");
            }
            return staff;
        }
        if (recorderUserId == null) {
            return null;
        }
        if (hospital != null) {
            return staffRepository.findByUserIdAndHospitalId(recorderUserId, hospital.getId()).orElse(null);
        }
        return staffRepository.findFirstByUserIdOrderByCreatedAtAsc(recorderUserId).orElse(null);
    }

    private User resolveRecorderUser(UUID recorderUserId) {
        if (recorderUserId == null) {
            return null;
        }
        return userRepository.findById(recorderUserId).orElse(null);
    }

    private int sanitizeLimit(Integer candidate, int defaultValue, int maxValue) {
        int value = candidate == null ? defaultValue : candidate;
        if (value < 1) {
            value = 1;
        }
        return Math.min(value, maxValue);
    }
}
