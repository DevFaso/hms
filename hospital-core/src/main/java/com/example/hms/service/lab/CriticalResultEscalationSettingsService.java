package com.example.hms.service.lab;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Rule CRITICAL_RESULT_ESCALATION (« provisoire »): a hospital's own steps of the critical-result ladder (V363)
 * and the doctor it names médecin-chef. The defaults are 15, 30 and 60 minutes; a hospital may move them between
 * {@value CriticalResultEscalationLadder#MIN_MINUTES} minutes and a day, in increasing order. Nothing here can
 * switch the ladder off or end it: there is no setting for that.
 */
@Service
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.CRITICAL_RESULT_ESCALATION)
public class CriticalResultEscalationSettingsService {

    private static final Set<String> DOCTOR_ROLE_CODES = Set.copyOf(CriticalResultRecipients.DOCTOR_ROLES);

    private final HospitalRepository hospitalRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    /** A doctor of the hospital, as the médecin-chef picker lists them. */
    public record Doctor(UUID userId, String name) {
    }

    /** {@code usesDefaults}: the hospital set no minutes of its own. */
    public record Settings(UUID hospitalId, int coverAfterMinutes, int leadAfterMinutes, int unresolvedAfterMinutes,
                           boolean usesDefaults, UUID medicalLeadUserId, List<Doctor> doctors) {
    }

    @Transactional(readOnly = true)
    public Settings get(UUID hospitalId) {
        return toSettings(require(hospitalId));
    }

    @Transactional
    public Settings update(UUID hospitalId, int cover, int lead, int unresolved, UUID medicalLeadUserId) {
        Hospital hospital = require(hospitalId);
        if (!new CriticalResultEscalationLadder.Minutes(cover, lead, unresolved).valid()) {
            throw new FieldBusinessException("coverAfterMinutes", "lab.criticalEscalation.minutesInvalid",
                CriticalResultEscalationLadder.MIN_MINUTES, CriticalResultEscalationLadder.MAX_MINUTES);
        }
        if (medicalLeadUserId != null
                && !assignmentRepository.existsActiveWitness(medicalLeadUserId, hospitalId, DOCTOR_ROLE_CODES)) {
            throw new FieldBusinessException("medicalLeadUserId", "lab.criticalEscalation.leadNotADoctor");
        }
        hospital.setCriticalCoverAfterMinutes(cover);
        hospital.setCriticalLeadAfterMinutes(lead);
        hospital.setCriticalUnresolvedAfterMinutes(unresolved);
        hospital.setCriticalMedicalLeadUserId(medicalLeadUserId);
        hospitalRepository.save(hospital);
        return toSettings(hospital);
    }

    private Hospital require(UUID hospitalId) {
        return hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound", hospitalId));
    }

    private Settings toSettings(Hospital hospital) {
        CriticalResultEscalationLadder.Minutes minutes = CriticalResultEscalationLadder.Minutes.of(
            hospital.getCriticalCoverAfterMinutes(), hospital.getCriticalLeadAfterMinutes(),
            hospital.getCriticalUnresolvedAfterMinutes());
        List<Doctor> doctors = assignmentRepository
            .findLiveUsersAtHospitalWithRoleCodes(hospital.getId(), DOCTOR_ROLE_CODES).stream()
            .map(CriticalResultEscalationSettingsService::doctor)
            .distinct()
            .sorted(Comparator.comparing(Doctor::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
        boolean usesDefaults = hospital.getCriticalCoverAfterMinutes() == null
            && hospital.getCriticalLeadAfterMinutes() == null && hospital.getCriticalUnresolvedAfterMinutes() == null;
        return new Settings(hospital.getId(), minutes.cover(), minutes.lead(), minutes.unresolved(), usesDefaults,
            hospital.getCriticalMedicalLeadUserId(), doctors);
    }

    private static Doctor doctor(User user) {
        String name = TransfusionMapper.userName(user);
        return new Doctor(user.getId(), name == null ? "" : name);
    }
}
