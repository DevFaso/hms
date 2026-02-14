package com.example.hms.service.impl;

import com.example.hms.enums.PostpartumAlertSeverity;
import com.example.hms.enums.PostpartumAlertType;
import com.example.hms.enums.PostpartumFundusTone;
import com.example.hms.enums.PostpartumLochiaAmount;
import com.example.hms.enums.PostpartumMoodStatus;
import com.example.hms.enums.PostpartumSchedulePhase;
import com.example.hms.enums.PostpartumSupportStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PostpartumObservationMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.postpartum.PostpartumCarePlan;
import com.example.hms.model.postpartum.PostpartumObservation;
import com.example.hms.model.postpartum.PostpartumObservationAlert;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationRequestDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationResponseDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumScheduleDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PostpartumCarePlanRepository;
import com.example.hms.repository.PostpartumObservationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.PostpartumCareService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PostpartumCareServiceImpl implements PostpartumCareService {

    private static final double FEVER_THRESHOLD_C = 38.0;
    private static final int TACHYCARDIA_THRESHOLD = 100;
    private static final int HYPOTENSION_SYSTOLIC_THRESHOLD = 90;
    private static final int PAIN_ALERT_THRESHOLD = 7;
    private static final int HEMORRHAGE_BLOOD_LOSS_THRESHOLD_ML = 500;

    private final PostpartumObservationRepository observationRepository;
    private final PostpartumCarePlanRepository carePlanRepository;
    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final PostpartumObservationMapper mapper;

    @Override
    public PostpartumObservationResponseDTO recordObservation(UUID patientId,
                                                              PostpartumObservationRequestDTO request,
                                                              UUID recorderUserId) {
        Objects.requireNonNull(patientId, "patientId is required");
        if (request == null) {
            throw new BusinessException("Postpartum observation request payload is required.");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + patientId));

        PatientHospitalRegistration registration = resolveRegistration(patient, request.getRegistrationId(), request.getHospitalId());
        Hospital hospital = resolveHospital(patient, registration, request.getHospitalId());
        Staff staff = resolveRecorderStaff(request.getRecordedByStaffId(), recorderUserId, hospital);
        User documentedBy = resolveRecorderUser(recorderUserId);
        PostpartumCarePlan carePlan = resolveCarePlan(patient, hospital, registration, request);
        PostpartumObservation superseded = resolveSupersededObservation(patientId, hospital.getId(), request.getSupersedesObservationId());

        PostpartumObservation observation = buildObservation(patient, hospital, registration, carePlan, staff, documentedBy, superseded, request);
        AlertEvaluation evaluation = evaluateObservation(observation);

        ScheduleSnapshot snapshot = applyObservationToPlan(carePlan, observation, request, evaluation.escalateMonitoring());
        observation.setSchedulePhaseAtEntry(snapshot.phase());
        observation.setNextDueAtSnapshot(snapshot.nextDueAt());
        observation.setOverdueSinceSnapshot(snapshot.overdueSince());

        PostpartumObservation saved = observationRepository.save(observation);
        carePlanRepository.save(carePlan);

        publishAlerts(saved);
        return mapper.toResponse(saved, carePlan);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostpartumObservationResponseDTO> getRecentObservations(UUID patientId,
                                                                        UUID hospitalId,
                                                                        UUID carePlanId,
                                                                        int limit) {
        int effectiveLimit = Math.clamp(limit <= 0 ? 10 : limit, 1, 100);
        PostpartumCarePlan plan = resolvePlanForRead(patientId, hospitalId, carePlanId);
        List<PostpartumObservation> observations;
        if (plan != null) {
            observations = observationRepository.findByCarePlan_IdOrderByObservationTimeDesc(
                plan.getId(), PageRequest.of(0, effectiveLimit));
        } else if (hospitalId != null) {
            observations = observationRepository.findByPatient_IdAndHospital_IdOrderByObservationTimeDesc(
                patientId, hospitalId, PageRequest.of(0, effectiveLimit));
        } else {
            observations = observationRepository.findWithinRange(
                patientId, null, null, null, PageRequest.of(0, effectiveLimit));
        }
        return observations.stream()
            .map(o -> mapper.toResponse(o, plan))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostpartumObservationResponseDTO> searchObservations(UUID patientId,
                                                                     UUID hospitalId,
                                                                     UUID carePlanId,
                                                                     LocalDateTime from,
                                                                     LocalDateTime to,
                                                                     int page,
                                                                     int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(size, 1);
        PostpartumCarePlan plan = resolvePlanForRead(patientId, hospitalId, carePlanId);
        UUID resolvedHospitalId = hospitalId;
        if (resolvedHospitalId == null && plan != null) {
            resolvedHospitalId = plan.getHospital().getId();
        }
        List<PostpartumObservation> observations = observationRepository.findWithinRange(
            patientId,
            resolvedHospitalId,
            from,
            to,
            PageRequest.of(safePage, safeSize)
        );
        return observations.stream()
            .map(o -> mapper.toResponse(o, plan))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PostpartumScheduleDTO getSchedule(UUID patientId, UUID hospitalId, UUID carePlanId) {
        PostpartumCarePlan plan = resolvePlanForRead(patientId, hospitalId, carePlanId);
        if (plan == null) {
            return PostpartumScheduleDTO.builder()
                .carePlanId(null)
                .phase(null)
                .immediateWindowComplete(false)
                .immediateChecksCompleted(0)
                .immediateCheckTarget(PostpartumCarePlan.IMMEDIATE_WINDOW_MINUTES / PostpartumCarePlan.IMMEDIATE_INTERVAL_MINUTES)
                .frequencyMinutes(null)
                .nextDueAt(null)
                .overdueSince(null)
                .overdue(false)
                .build();
        }
        PostpartumObservationResponseDTO response = mapper.toResponse(
            observationRepository.findFirstByCarePlan_IdOrderByObservationTimeDesc(plan.getId()).orElse(null),
            plan
        );
        if (response != null && response.getSchedule() != null) {
            return response.getSchedule();
        }
        boolean overdue = plan.getOverdueSince() != null && plan.getOverdueSince().isBefore(LocalDateTime.now());
        Integer frequency = plan.getActivePhase() == PostpartumSchedulePhase.DISCHARGE_PLANNING
            ? null
            : plan.getShiftFrequencyMinutes();
        return PostpartumScheduleDTO.builder()
            .carePlanId(plan.getId())
            .phase(plan.getActivePhase())
            .immediateWindowComplete(plan.isImmediateWindowCompleted())
            .immediateChecksCompleted(plan.getImmediateObservationsCompleted())
            .immediateCheckTarget(plan.getImmediateObservationTarget())
            .frequencyMinutes(frequency)
            .nextDueAt(plan.getNextDueAt())
            .overdueSince(plan.getOverdueSince())
            .overdue(overdue)
            .build();
    }

    private PatientHospitalRegistration resolveRegistration(Patient patient, UUID registrationId, UUID hospitalId) {
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

    private Hospital resolveHospital(Patient patient, PatientHospitalRegistration registration, UUID requestedHospitalId) {
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
        throw new BusinessException("Unable to resolve hospital context for postpartum observation.");
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

    private PostpartumCarePlan resolveCarePlan(Patient patient,
                                               Hospital hospital,
                                               PatientHospitalRegistration registration,
                                               PostpartumObservationRequestDTO request) {
        if (request.getCarePlanId() != null) {
            return carePlanRepository.findByIdAndPatient_IdAndHospital_Id(
                    request.getCarePlanId(), patient.getId(), hospital.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Postpartum care plan not found for supplied identifiers."));
        }
        Optional<PostpartumCarePlan> existing = carePlanRepository
            .findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patient.getId(), hospital.getId());
        if (existing.isPresent()) {
            PostpartumCarePlan plan = existing.get();
            if (plan.getRegistration() == null && registration != null) {
                plan.setRegistration(registration);
            }
            if (plan.getDeliveryOccurredAt() == null) {
                plan.setDeliveryOccurredAt(resolveDeliveryTimestamp(request));
                plan.setNextDueAt(plan.getDeliveryOccurredAt()
                    .plusMinutes(PostpartumCarePlan.IMMEDIATE_INTERVAL_MINUTES));
            }
            return plan;
        }
        PostpartumCarePlan plan = PostpartumCarePlan.builder()
            .patient(patient)
            .hospital(hospital)
            .registration(registration)
            .deliveryOccurredAt(resolveDeliveryTimestamp(request))
            .build();
        if (plan.getDeliveryOccurredAt() == null) {
            plan.setDeliveryOccurredAt(LocalDateTime.now());
        }
        plan.setNextDueAt(plan.getDeliveryOccurredAt()
            .plusMinutes(PostpartumCarePlan.IMMEDIATE_INTERVAL_MINUTES));
        return carePlanRepository.save(plan);
    }

    private LocalDateTime resolveDeliveryTimestamp(PostpartumObservationRequestDTO request) {
        if (request.getDeliveryOccurredAt() != null) {
            return request.getDeliveryOccurredAt();
        }
        if (request.getObservationTime() != null) {
            return request.getObservationTime();
        }
        return LocalDateTime.now();
    }

    private PostpartumObservation resolveSupersededObservation(UUID patientId, UUID hospitalId, UUID observationId) {
        if (observationId == null) {
            return null;
        }
        return observationRepository.findByIdAndPatient_IdAndHospital_Id(observationId, patientId, hospitalId)
            .orElseThrow(() -> new BusinessException("Unable to record correction; original observation not located."));
    }

    @SuppressWarnings("java:S107") // builder method needs all domain objects to construct observation
    private PostpartumObservation buildObservation(Patient patient,
                                                   Hospital hospital,
                                                   PatientHospitalRegistration registration,
                                                   PostpartumCarePlan carePlan,
                                                   Staff staff,
                                                   User documentedBy,
                                                   PostpartumObservation superseded,
                                                   PostpartumObservationRequestDTO request) {
        LocalDateTime observationTime = request.getObservationTime() != null
            ? request.getObservationTime()
            : LocalDateTime.now();
        PostpartumObservation observation = PostpartumObservation.builder()
            .patient(patient)
            .hospital(hospital)
            .registration(registration)
            .carePlan(carePlan)
            .recordedByStaff(staff)
            .documentedBy(documentedBy)
            .observationTime(observationTime)
            .documentedAt(LocalDateTime.now())
            .lateEntry(Boolean.TRUE.equals(request.getLateEntry()))
            .originalEntryTime(request.getOriginalEntryTime())
            .temperatureCelsius(request.getTemperatureCelsius())
            .systolicBpMmHg(request.getSystolicBpMmHg())
            .diastolicBpMmHg(request.getDiastolicBpMmHg())
            .pulseBpm(request.getPulseBpm())
            .respirationsPerMin(request.getRespirationsPerMin())
            .painScore(request.getPainScore())
            .fundusHeightCm(request.getFundusHeightCm())
            .fundusTone(request.getFundusTone())
            .bladderStatus(request.getBladderStatus())
            .lochiaAmount(request.getLochiaAmount())
            .lochiaCharacter(request.getLochiaCharacter())
            .lochiaNotes(request.getLochiaNotes())
            .perineumFindings(request.getPerineumFindings())
            .uterineAtonySuspected(Boolean.TRUE.equals(request.getUterineAtonySuspected()))
            .excessiveBleeding(Boolean.TRUE.equals(request.getExcessiveBleeding()))
            .estimatedBloodLossMl(request.getEstimatedBloodLossMl())
            .uterotonicGiven(Boolean.TRUE.equals(request.getUterotonicGiven()))
            .hemorrhageProtocolActivated(Boolean.TRUE.equals(request.getHemorrhageProtocolActivated()))
            .foulLochiaOdor(Boolean.TRUE.equals(request.getFoulLochiaOdor()))
            .uterineTenderness(Boolean.TRUE.equals(request.getUterineTenderness()))
            .chillsOrRigors(Boolean.TRUE.equals(request.getChillsOrRigors()))
            .moodStatus(request.getMoodStatus())
            .supportStatus(request.getSupportStatus())
            .sleepStatus(request.getSleepStatus())
            .psychosocialNotes(request.getPsychosocialNotes())
            .mentalHealthReferralSuggested(Boolean.TRUE.equals(request.getMentalHealthReferralSuggested()))
            .socialSupportReferralSuggested(Boolean.TRUE.equals(request.getSocialSupportReferralSuggested()))
            .painManagementReferralSuggested(Boolean.TRUE.equals(request.getPainManagementReferralSuggested()))
            .educationNotes(request.getEducationNotes())
            .educationCompleted(Boolean.TRUE.equals(request.getEducationCompleted()))
            .postpartumVisitDate(request.getPostpartumVisitDate())
            .dischargeChecklistComplete(Boolean.TRUE.equals(request.getDischargeChecklistComplete()))
            .rhImmunoglobulinCompleted(Boolean.TRUE.equals(request.getRhImmunoglobulinCompleted()))
            .immunizationsUpdated(Boolean.TRUE.equals(request.getImmunizationsUpdated()))
            .hemorrhageProtocolConfirmed(Boolean.TRUE.equals(request.getHemorrhageProtocolConfirmed()))
            .uterotonicAvailabilityConfirmed(Boolean.TRUE.equals(request.getUterotonicAvailabilityConfirmed()))
            .contactInfoVerified(Boolean.TRUE.equals(request.getContactInfoVerified()))
            .followUpContactMethod(request.getFollowUpContactMethod())
            .dischargeSafetyNotes(request.getDischargeSafetyNotes())
            .signoffName(request.getSignoffName())
            .signoffCredentials(request.getSignoffCredentials())
            .signedAt(request.getSignedAt())
            .supersedesObservation(superseded)
            .build();
        if (request.getEducationTopics() != null) {
            observation.getEducationTopics().addAll(request.getEducationTopics());
        }
        return observation;
    }

    private AlertEvaluation evaluateObservation(PostpartumObservation observation) {
        List<PostpartumObservationAlert> alerts = new ArrayList<>();
        boolean escalateMonitoring = false;
        boolean hemorrhageAlert = false;
        boolean infectionAlert = false;

        if (hasHemorrhageIndicators(observation)) {
            alerts.add(buildAlert(PostpartumAlertType.HEMORRHAGE, PostpartumAlertSeverity.URGENT,
                "postpartum-hemorrhage",
                "Hemorrhage risk detected. Activate uterotonic protocol and notify provider immediately.",
                "Bleeding Assessment"));
            observation.setHemorrhageProtocolActivated(true);
            escalateMonitoring = true;
            hemorrhageAlert = true;
        }

        if (hasInfectionIndicators(observation)) {
            alerts.add(buildAlert(PostpartumAlertType.INFECTION, PostpartumAlertSeverity.URGENT,
                "postpartum-infection",
                "Possible postpartum infection. Initiate sepsis screening and contact provider.",
                "Infection Screening"));
            escalateMonitoring = true;
            infectionAlert = true;
        }

        if (observation.getPainScore() != null && observation.getPainScore() >= PAIN_ALERT_THRESHOLD) {
            alerts.add(buildAlert(PostpartumAlertType.PAIN, PostpartumAlertSeverity.CAUTION,
                "postpartum-pain",
                "Pain score above comfort threshold. Reassess analgesia plan and comfort interventions.",
                "Pain Score"));
            observation.setPainManagementReferralSuggested(true);
        }

        applyPsychosocialFlags(observation);
        if (observation.isMentalHealthReferralSuggested()) {
            alerts.add(buildAlert(PostpartumAlertType.PSYCHOSOCIAL, PostpartumAlertSeverity.CAUTION,
                "postpartum-psychosocial",
                "Psychosocial concern identified. Coordinate mental health or social work referral.",
                "Psychosocial Screening"));
        }

        alerts.forEach(observation::addAlert);
        return new AlertEvaluation(alerts, escalateMonitoring, hemorrhageAlert, infectionAlert);
    }

    private boolean hasHemorrhageIndicators(PostpartumObservation observation) {
        boolean vitalSignInstability = (observation.getSystolicBpMmHg() != null
            && observation.getSystolicBpMmHg() < HYPOTENSION_SYSTOLIC_THRESHOLD)
            || (observation.getPulseBpm() != null && observation.getPulseBpm() > TACHYCARDIA_THRESHOLD);

        boolean fundusConcern = observation.getFundusTone() != null
            && (observation.getFundusTone() == PostpartumFundusTone.BOGGY
            || observation.getFundusTone() == PostpartumFundusTone.SLIGHTLY_BOGGY
            || observation.getFundusTone() == PostpartumFundusTone.DEVIATED);

        boolean heavyLochia = observation.getLochiaAmount() != null
            && (observation.getLochiaAmount() == PostpartumLochiaAmount.HEAVY
            || observation.getLochiaAmount() == PostpartumLochiaAmount.EXCESSIVE);

        return observation.isExcessiveBleeding()
            || (observation.getEstimatedBloodLossMl() != null
                && observation.getEstimatedBloodLossMl() >= HEMORRHAGE_BLOOD_LOSS_THRESHOLD_ML)
            || observation.isUterineAtonySuspected()
            || fundusConcern
            || heavyLochia
            || vitalSignInstability;
    }

    private boolean hasInfectionIndicators(PostpartumObservation observation) {
        return (observation.getTemperatureCelsius() != null
            && observation.getTemperatureCelsius() >= FEVER_THRESHOLD_C)
            || observation.isFoulLochiaOdor()
            || observation.isUterineTenderness()
            || observation.isChillsOrRigors();
    }

    private PostpartumObservationAlert buildAlert(PostpartumAlertType type, PostpartumAlertSeverity severity,
                                                  String code, String message, String triggeredBy) {
        return PostpartumObservationAlert.builder()
            .type(type)
            .severity(severity)
            .code(code)
            .message(message)
            .triggeredBy(triggeredBy)
            .build();
    }

    private void applyPsychosocialFlags(PostpartumObservation observation) {
        if (observation.getMoodStatus() == PostpartumMoodStatus.DEPRESSED
            || observation.getMoodStatus() == PostpartumMoodStatus.TEARFUL
            || observation.getMoodStatus() == PostpartumMoodStatus.WITHDRAWN) {
            observation.setMentalHealthReferralSuggested(true);
        }
        if (observation.getSupportStatus() == PostpartumSupportStatus.LIMITED
            || observation.getSupportStatus() == PostpartumSupportStatus.NONE) {
            observation.setSocialSupportReferralSuggested(true);
        }
    }

    private ScheduleSnapshot applyObservationToPlan(PostpartumCarePlan plan,
                                                    PostpartumObservation observation,
                                                    PostpartumObservationRequestDTO request,
                                                    boolean escalateMonitoring) {
        plan.setLastObservationAt(observation.getObservationTime());
        if (plan.getDeliveryOccurredAt() == null) {
            plan.setDeliveryOccurredAt(resolveDeliveryTimestamp(request));
        }
        if (plan.getRegistration() == null && observation.getRegistration() != null) {
            plan.setRegistration(observation.getRegistration());
        }

        if (plan.isImmediatePhase() && !plan.isImmediateWindowCompleted()) {
            applyImmediatePhaseScheduling(plan, observation, request);
        } else {
            applyShiftPhaseScheduling(plan, observation, request, escalateMonitoring);
        }

        applyProtocolFlags(plan, observation);
        applyDischargeFlags(plan, observation);
        applyReferralFlags(plan, observation);
        applyOverdueTracking(plan);

        return new ScheduleSnapshot(plan.getActivePhase(), plan.getNextDueAt(), plan.getOverdueSince());
    }

    private void applyImmediatePhaseScheduling(PostpartumCarePlan plan,
                                               PostpartumObservation observation,
                                               PostpartumObservationRequestDTO request) {
        plan.incrementImmediateObservationCount();
        LocalDateTime windowEnd = plan.getDeliveryOccurredAt()
            .plusMinutes(PostpartumCarePlan.IMMEDIATE_WINDOW_MINUTES);
        boolean targetMet = plan.getImmediateObservationsCompleted() >= plan.getImmediateObservationTarget();
        boolean outsideWindow = observation.getObservationTime().isAfter(windowEnd);
        boolean stabilization = Boolean.TRUE.equals(request.getStabilizationConfirmed());
        if (targetMet || outsideWindow || stabilization) {
            plan.setImmediateWindowCompleted(true);
            plan.setStabilizationAchievedAt(observation.getObservationTime());
            plan.markActivePhase(PostpartumSchedulePhase.SHIFT_BASELINE);
            int frequency = sanitizeFrequency(request.getShiftFrequencyMinutes());
            plan.setShiftFrequencyMinutes(frequency);
            plan.setNextDueAt(observation.getObservationTime().plusMinutes(frequency));
        } else {
            plan.setNextDueAt(observation.getObservationTime()
                .plusMinutes(PostpartumCarePlan.IMMEDIATE_INTERVAL_MINUTES));
        }
    }

    private void applyShiftPhaseScheduling(PostpartumCarePlan plan,
                                           PostpartumObservation observation,
                                           PostpartumObservationRequestDTO request,
                                           boolean escalateMonitoring) {
        int frequency = sanitizeFrequency(request.getShiftFrequencyMinutes() != null
            ? request.getShiftFrequencyMinutes()
            : plan.getShiftFrequencyMinutes());
        boolean enhancedRequested = Boolean.TRUE.equals(request.getEnhancedMonitoring());
        boolean resolveEnhanced = Boolean.TRUE.equals(request.getEnhancedMonitoringResolved());

        if ((enhancedRequested || escalateMonitoring) && plan.getActivePhase() != PostpartumSchedulePhase.DISCHARGE_PLANNING) {
            plan.markActivePhase(PostpartumSchedulePhase.ENHANCED_MONITORING);
            int enhancedFrequency = sanitizeFrequency(request.getEnhancedMonitoringFrequencyMinutes() != null
                ? request.getEnhancedMonitoringFrequencyMinutes()
                : Math.max(PostpartumCarePlan.MIN_SHIFT_FREQUENCY_MINUTES, frequency / 2));
            plan.setShiftFrequencyMinutes(enhancedFrequency);
            plan.setEscalationReason("Critical postpartum alert triggered");
            plan.setNextDueAt(observation.getObservationTime().plusMinutes(enhancedFrequency));
        } else if (plan.isEnhancedMonitoring() && resolveEnhanced) {
            plan.markActivePhase(PostpartumSchedulePhase.SHIFT_BASELINE);
            plan.setShiftFrequencyMinutes(frequency);
            plan.setEscalationReason(null);
            plan.setNextDueAt(observation.getObservationTime().plusMinutes(frequency));
        } else {
            plan.setShiftFrequencyMinutes(frequency);
            if (plan.getActivePhase() == PostpartumSchedulePhase.DISCHARGE_PLANNING) {
                plan.setNextDueAt(null);
            } else {
                plan.setNextDueAt(observation.getObservationTime().plusMinutes(frequency));
            }
        }
    }

    private void applyProtocolFlags(PostpartumCarePlan plan, PostpartumObservation observation) {
        if (observation.isHemorrhageProtocolConfirmed()) {
            plan.setHemorrhageProtocolConfirmed(true);
        }
        if (observation.isUterotonicAvailabilityConfirmed()) {
            plan.setUterotonicAvailabilityConfirmed(true);
        }
        if (observation.isRhImmunoglobulinCompleted()) {
            plan.setRhImmunoglobulinCompleted(true);
        }
        if (observation.isImmunizationsUpdated()) {
            plan.setImmunizationsUpdated(true);
        }
        if (observation.isContactInfoVerified()) {
            plan.setContactInfoVerified(true);
        }
    }

    private void applyDischargeFlags(PostpartumCarePlan plan, PostpartumObservation observation) {
        if (observation.getFollowUpContactMethod() != null) {
            plan.setFollowUpContactMethod(observation.getFollowUpContactMethod());
        }
        if (observation.getDischargeSafetyNotes() != null) {
            plan.setDischargeSafetyNotes(observation.getDischargeSafetyNotes());
        }
        if (observation.getPostpartumVisitDate() != null) {
            plan.setPostpartumVisitDate(observation.getPostpartumVisitDate());
        }
        if (observation.isDischargeChecklistComplete()) {
            plan.setDischargeChecklistComplete(true);
            plan.markActivePhase(PostpartumSchedulePhase.DISCHARGE_PLANNING);
            plan.markClosed(LocalDateTime.now());
        }
    }

    private void applyReferralFlags(PostpartumCarePlan plan, PostpartumObservation observation) {
        if (observation.isMentalHealthReferralSuggested()) {
            plan.setMentalHealthReferralOutstanding(true);
        }
        if (observation.isSocialSupportReferralSuggested()) {
            plan.setSocialSupportReferralOutstanding(true);
        }
        if (observation.isPainManagementReferralSuggested()) {
            plan.setPainFollowupOutstanding(true);
        }
    }

    private void applyOverdueTracking(PostpartumCarePlan plan) {
        LocalDateTime nextDue = plan.getNextDueAt();
        if (nextDue != null && nextDue.isBefore(LocalDateTime.now())) {
            plan.setOverdueSince(nextDue);
        } else if (plan.getActivePhase() == PostpartumSchedulePhase.DISCHARGE_PLANNING) {
            plan.setOverdueSince(null);
        }
    }

    private int sanitizeFrequency(Integer candidate) {
        int frequency = candidate == null ? PostpartumCarePlan.DEFAULT_SHIFT_FREQUENCY_MINUTES : candidate;
        if (frequency < PostpartumCarePlan.MIN_SHIFT_FREQUENCY_MINUTES) {
            frequency = PostpartumCarePlan.MIN_SHIFT_FREQUENCY_MINUTES;
        }
        if (frequency > PostpartumCarePlan.MAX_SHIFT_FREQUENCY_MINUTES) {
            frequency = PostpartumCarePlan.MAX_SHIFT_FREQUENCY_MINUTES;
        }
        return frequency;
    }

    private PostpartumCarePlan resolvePlanForRead(UUID patientId, UUID hospitalId, UUID carePlanId) {
        if (carePlanId != null) {
            return carePlanRepository.findByIdAndPatient_IdAndHospital_Id(carePlanId, patientId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Postpartum care plan not found."));
        }
        if (hospitalId != null) {
            return carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId)
                .orElse(null);
        }
        List<PostpartumCarePlan> plans = carePlanRepository.findByPatient_IdAndActiveTrue(patientId);
        return plans.isEmpty() ? null : plans.get(0);
    }

    private void publishAlerts(PostpartumObservation observation) {
        if (observation.getAlerts() == null || observation.getAlerts().isEmpty()) {
            return;
        }
        User documentedBy = observation.getDocumentedBy();
        if (documentedBy == null || documentedBy.getUsername() == null) {
            return;
        }
        String patientName = observation.getPatient() != null
            ? (observation.getPatient().getFirstName() + " " + observation.getPatient().getLastName()).trim()
            : "patient";
        observation.getAlerts().stream()
            .filter(alert -> alert.getSeverity() == PostpartumAlertSeverity.URGENT)
            .forEach(alert -> {
                String message = String.format("%s alert for %s: %s",
                    alert.getType(), patientName.isBlank() ? "patient" : patientName, alert.getMessage());
                try {
                    notificationService.createNotification(message, documentedBy.getUsername());
                } catch (RuntimeException ex) {
                    log.warn("Failed to create postpartum alert notification: {}", ex.getMessage());
                }
            });
    }

    private record AlertEvaluation(List<PostpartumObservationAlert> alerts,
                                   boolean escalateMonitoring,
                                   boolean hemorrhageDetected,
                                   boolean infectionDetected) {
    }

    private record ScheduleSnapshot(PostpartumSchedulePhase phase,
                                     LocalDateTime nextDueAt,
                                     LocalDateTime overdueSince) {
    }
}
