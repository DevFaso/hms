package com.example.hms.service.impl;

import com.example.hms.service.i18n.NotificationLocalizer;
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
import com.example.hms.utility.ObstetricEligibility;
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
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;
import com.example.hms.security.context.HospitalContextHolder;
import org.springframework.context.MessageSource;
import com.example.hms.service.i18n.NotificationLocales;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class NewbornAssessmentServiceImpl implements NewbornAssessmentService {

    private static final String ALERT_MSG_PREFIX = "newborn.alert.msg.";

    private static final String HOSPITAL_NOT_FOUND = "hospital.notFound";

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
    private final com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final HospitalRepository hospitalRepository;
    private final com.example.hms.repository.DeliveryRecordRepository deliveryRecordRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    /** QA D605: URGENT alerts reach the on-call team and escalate until acknowledged. */
    private final com.example.hms.service.maternity.ObstetricAlertEscalationService obstetricAlertEscalation;
    private final NewbornAssessmentMapper mapper;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final MessageSource messageSource;

    @Override
    public NewbornAssessmentResponseDTO recordAssessment(UUID patientId,
                                                         NewbornAssessmentRequestDTO request,
                                                         UUID recorderUserId) {
        Objects.requireNonNull(patientId, "patientId is required");
        if (request == null) {
            throw new BusinessException("maternity.newborn.payloadRequired");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", patientId));
        boolean recordedOnNewbornChart = isNeonate(patient);
        if (!recordedOnNewbornChart) {
            // QA D161 (D103 family): a newborn cannot yet be registered as its own
            // patient (D163), so the maternity screen records the assessment on the
            // MOTHER's chart. On a chart that is not a neonate's, the patient is the
            // mother and the obstetric rule applies, as for every other maternity record.
            ObstetricEligibility.requireObstetricEligible(patient);
        }

        PatientHospitalRegistration registration = resolveRegistration(patient, request.getRegistrationId(), request.getHospitalId());
        Hospital hospital = resolveHospital(patient, registration, request.getHospitalId());
        // QA D401: the record is written at the caller's acting hospital (the
        // body's hospital id is a claim), for a patient registered there.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(hospital, HOSPITAL_NOT_FOUND, hospital.getId());
        patientRegistrationGuard.requireRegisteredAt(patient.getId(), hospital.getId());
        Staff staff = resolveRecorderStaff(request.getRecordedByStaffId(), recorderUserId, hospital);
        User documentedBy = resolveRecorderUser(recorderUserId);

        com.example.hms.model.labor.DeliveryRecord deliveryRecord =
            resolveDeliveryRecord(request.getDeliveryRecordId(), hospital,
                recordedOnNewbornChart ? null : patient);

        NewbornAssessment assessment =
            buildAssessment(patient, hospital, registration, staff, documentedBy, request);
        assessment.setDeliveryRecord(deliveryRecord);
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
            // E9 #59d — newborn assessments follow the patient across the
            // readable hospitals; every foreign row surfaced is accounted.
            UUID requesterUserId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
            Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, hospitalId);
            assessments = assessmentRepository.findByPatient_IdAndHospital_IdInOrderByAssessmentTimeDesc(
                patientId, readable, PageRequest.of(0, effectiveLimit));
            reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null,
                CrossHospitalReachRecorder.reachOf(assessments.stream().map(a -> CrossHospitalReachRecorder.hospitalIdOf(a.getHospital())).toList(), hospitalId),
                "Cross-hospital newborn assessment read on the treatment relationship");
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
                "newborn-apgar-1", alertText("newborn-apgar-1"), "Apgar 1 minute"));
            assessment.addFollowUpAction(NewbornFollowUpAction.NICU_CONSULT);
            urgent = true;
        }
        if (fiveMinute != null && fiveMinute < LOW_APGAR_THRESHOLD) {
            alerts.add(buildAlert(NewbornAlertType.APGAR, NewbornAlertSeverity.URGENT,
                "newborn-apgar-5", alertText("newborn-apgar-5"), "Apgar 5 minute"));
            assessment.addFollowUpAction(NewbornFollowUpAction.NICU_CONSULT);
            urgent = true;
        }
        if (!urgent && tenMinute != null && tenMinute < LOW_APGAR_THRESHOLD) {
            alerts.add(buildAlert(NewbornAlertType.APGAR, NewbornAlertSeverity.CAUTION,
                "newborn-apgar-10", alertText("newborn-apgar-10"),
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
                "newborn-temperature-critical", alertText("newborn-temperature-critical"),
                "Temperature"));
            assessment.addFollowUpAction(NewbornFollowUpAction.THERMAL_SUPPORT);
            if (!assessment.isThermoregulationSupportInitiated()) {
                assessment.setThermoregulationSupportInitiated(true);
            }
            return true;
        }
        if (temperature < LOW_TEMP_THRESHOLD || temperature > HIGH_TEMP_THRESHOLD) {
            alerts.add(buildAlert(NewbornAlertType.THERMOREGULATION, NewbornAlertSeverity.CAUTION,
                "newborn-temperature", alertText("newborn-temperature"),
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
                "newborn-heart-rate", alertText("newborn-heart-rate"),
                "Heart Rate"));
            assessment.addFollowUpAction(NewbornFollowUpAction.NICU_CONSULT);
            urgent = true;
        }
        if (respirations != null && (respirations < RESP_RATE_LOW || respirations > RESP_RATE_HIGH)) {
            alerts.add(buildAlert(NewbornAlertType.RESPIRATORY, NewbornAlertSeverity.URGENT,
                "newborn-respiratory", alertText("newborn-respiratory"),
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
                "newborn-oxygenation", alertText("newborn-oxygenation"),
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
                "newborn-glucose", alertText("newborn-glucose"),
                "Glucose"));
            assessment.addFollowUpAction(NewbornFollowUpAction.GLUCOSE_MONITORING);
            if (!assessment.isGlucoseProtocolInitiated()) {
                assessment.setGlucoseProtocolInitiated(true);
            }
            return true;
        }
        return false;
    }

    /**
     * Alert text in the staff locale (QA D226), like the labor alerts: the alert is
     * stored and read by staff, so it is never composed in English in code.
     */
    private String alertText(String code) {
        return messageSource.getMessage(ALERT_MSG_PREFIX + code, null, NotificationLocales.STAFF);
    }

    private NewbornAssessmentAlert buildAlert(NewbornAlertType type,
                                              NewbornAlertSeverity severity,
                                              String code,
                                              String message,
                                              String trigger) {
        // QA D216 (D172/D61): the stored alert text is shown on the chart and
        // sent as a notification, so it is rendered in the staff locale from
        // the bundle; the English literal is only the fallback.
        return NewbornAssessmentAlert.builder()
            .type(type)
            .severity(severity)
            .code(code)
            .message(messageSource.getMessage(ALERT_MSG_PREFIX + code, null, message, NotificationLocales.STAFF))
            .triggeredBy(messageSource.getMessage("newborn.alert.trigger." + code, null, trigger,
                NotificationLocales.STAFF))
            .build();
    }

    private void publishAlerts(NewbornAssessment assessment, User documentedBy) {
        if (assessment.getAlerts() == null || assessment.getAlerts().isEmpty()) {
            return;
        }
        // QA D605: the team is told even when the documenter is unknown.
        boolean notifyDocumenter = documentedBy != null && documentedBy.getUsername() != null;
        String fullName = assessment.getPatient() != null
            ? (assessment.getPatient().getFirstName() + " " + assessment.getPatient().getLastName()).trim()
            : "";
        assessment.getAlerts().stream()
            .filter(alert -> alert.getSeverity() == NewbornAlertSeverity.URGENT)
            .forEach(alert -> publishUrgentAlert(alert, assessment, documentedBy, fullName, notifyDocumenter));
    }

    /** The documenting clinician's own notice, in her language (QA D665). Never throws. */
    private void notifyDocumenter(NewbornAssessmentAlert alert, User documentedBy, String fullName, String messageKey) {
        // Read by the documenting clinician, in her language (QA D665).
        // QA D216: the type is a translated label, never the raw enum.
        Object typeKey = alert.getType() == null ? ""
            : NotificationLocalizer.key("newborn.alert.type." + alert.getType().name());
        Object patientArg = fullName.isBlank() ? NotificationLocalizer.key("patient.fallback.generic") : fullName;
        try {
            notificationService.createNotification("newborn.alert.notification",
                new Object[]{typeKey, patientArg,
                    com.example.hms.service.maternity.ObstetricAlertEscalationWorker.alertMessageArg(
                        messageKey, null, alert.getMessage())}, documentedBy.getUsername(), null);
        } catch (RuntimeException ex) {
            log.warn("Failed to create newborn alert notification: {}", ex.getMessage());
        }
    }

    private void publishUrgentAlert(NewbornAssessmentAlert alert, NewbornAssessment assessment, User documentedBy,
                                    String fullName, boolean notifyDocumenter) {
        // QA D665: the alert sentence travels as its key, rendered per reader.
        String messageKey = alert.getCode() == null ? null : ALERT_MSG_PREFIX + alert.getCode();
        if (notifyDocumenter) {
            notifyDocumenter(alert, documentedBy, fullName, messageKey);
        }
        if (obstetricAlertEscalation != null && assessment.getHospital() != null && assessment.getPatient() != null) {
            // Stored on the escalation row: staff-locale text (the worker re-keys its notifications).
            java.util.Locale locale = NotificationLocales.STAFF;
            String patientName = fullName.isBlank()
                ? messageSource.getMessage("patient.fallback.generic", null, locale) : fullName;
            String typeLabel = alert.getType() == null ? ""
                : messageSource.getMessage("newborn.alert.type." + alert.getType().name(), null, locale);
            String message = messageSource.getMessage("newborn.alert.notification",
                new Object[]{typeLabel, patientName, alert.getMessage()}, locale);
            obstetricAlertEscalation.raiseAfterCommit(new com.example.hms.service.maternity.ObstetricAlertEscalationService.UrgentAlert(
                assessment.getHospital().getId(), assessment.getPatient().getId(), patientName,
                com.example.hms.service.maternity.ObstetricAlertEscalationService.SOURCE_NEWBORN, assessment.getId(),
                alert.getType() == null ? null : alert.getType().name(), typeLabel, alert.getMessage(),
                documentedBy == null ? null : documentedBy.getId(), message, messageKey, null));
        }
    }

    /**
     * Resolve the delivery this newborn came from.
     *
     * <p>Optional: a newborn may be transferred in already born, or predate the
     * L&amp;D module — the entity javadoc says as much. Null in, null out.
     *
     * <p>Always validated against the HOSPITAL. A delivery record's patient is
     * the MOTHER: when the assessment sits on a neonate's chart the two differ
     * and {@code mother} is null; when it sits on the mother's chart (QA D161,
     * the only path the maternity screen has today) the delivery must be hers.
     */
    private com.example.hms.model.labor.DeliveryRecord resolveDeliveryRecord(UUID deliveryRecordId,
                                                                             Hospital hospital,
                                                                             Patient mother) {
        if (deliveryRecordId == null) {
            if (mother == null) {
                // A neonate's own chart: the baby may have been born elsewhere.
                return null;
            }
            // QA D517: on the mother's chart the newborn comes from a birth
            // on file - her latest delivery at this hospital - or there is
            // no newborn to assess. Only a birth within the neonatal period
            // counts: a delivery from years ago has no newborn left.
            java.time.LocalDateTime neonatalSince = java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
                .minusDays(NEONATAL_PERIOD_DAYS);
            return (hospital == null ? java.util.Optional.<com.example.hms.model.labor.DeliveryRecord>empty()
                : deliveryRecordRepository.findFirstByPatient_IdAndHospital_IdOrderByBirthDateTimeDesc(
                    mother.getId(), hospital.getId()))
                .filter(d -> d.getBirthDateTime() != null && !d.getBirthDateTime().isBefore(neonatalSince))
                .orElseThrow(() -> new BusinessException("maternity.newborn.noDelivery"));
        }
        com.example.hms.model.labor.DeliveryRecord delivery = deliveryRecordRepository.findById(deliveryRecordId)
            .orElseThrow(() -> new ResourceNotFoundException("deliveryRecord.notFound", deliveryRecordId));

        if (hospital != null && delivery.getHospital() != null
            && !hospital.getId().equals(delivery.getHospital().getId())) {
            throw new BusinessException("maternity.newborn.deliveryOtherHospital");
        }
        if (mother != null && delivery.getPatient() != null
            && !mother.getId().equals(delivery.getPatient().getId())) {
            throw new BusinessException("newborn.delivery.otherMother");
        }
        return delivery;
    }

    /** Neonatal period: the first 28 days of life (WHO). */
    private static final int NEONATAL_PERIOD_DAYS = 28;

    /** True when the chart is a neonate's (born within the neonatal period). */
    static boolean isNeonate(Patient patient) {
        java.time.LocalDate dob = patient.getDateOfBirth();
        return dob != null && !dob.isBefore(java.time.LocalDate.now().minusDays(NEONATAL_PERIOD_DAYS));
    }

    private PatientHospitalRegistration resolveRegistration(Patient patient,
                                                             UUID registrationId,
                                                             UUID hospitalId) {
        if (registrationId != null) {
            PatientHospitalRegistration registration = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException("registration.notFound", registrationId));
            if (!registration.getPatient().getId().equals(patient.getId())) {
                throw new BusinessException("maternity.registration.otherPatient");
            }
            // QA r5 tenancy sweep: a registration of another hospital answers as unknown.
            if (hospitalId != null && (registration.getHospital() == null
                    || !hospitalId.equals(registration.getHospital().getId()))) {
                throw new ResourceNotFoundException("registration.notFound", registrationId);
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
            return hospitalRepository.findClinicalById(requestedHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND, requestedHospitalId));
        }
        if (patient.getHospitalId() != null) {
            return hospitalRepository.findClinicalById(patient.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND, patient.getHospitalId()));
        }
        throw new BusinessException("maternity.newborn.hospitalUnresolved");
    }

    private Staff resolveRecorderStaff(UUID staffId, UUID recorderUserId, Hospital hospital) {
        if (staffId != null) {
            Staff staff = staffRepository.findByIdAndActiveTrue(staffId)
                .orElseThrow(() -> new ResourceNotFoundException("staff.not.found.or.inactive", staffId));
            if (hospital != null && staff.getHospital() != null
                && !hospital.getId().equals(staff.getHospital().getId())) {
                throw new BusinessException("maternity.recorder.hospitalMismatch");
            }
            // QA D503 class: the recorder is the caller, not a colleague named in the body.
            com.example.hms.security.CallerIdentity.requireCallerUnlessSuperAdmin(staff);
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
