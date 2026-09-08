package com.example.hms.service.recordaccess;

import com.example.hms.enums.RecordAccessDenialReason;
import com.example.hms.enums.RecordAccessPosture;
import com.example.hms.enums.TenantIsolationMode;
import com.example.hms.model.Hospital;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRecordSharingOptOutRepository;
import com.example.hms.repository.StaffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;
import java.util.UUID;

@Service
public class RecordAccessPolicyImpl implements RecordAccessPolicy {

    private static final String CACHE_PREFIX = RecordAccessPolicyImpl.class.getName() + ":";

    private final HospitalRepository hospitalRepository;
    private final PatientRecordSharingOptOutRepository optOutRepository;
    private final StaffRepository staffRepository;
    private final TreatmentRelationshipResolver resolver;

    public RecordAccessPolicyImpl(HospitalRepository hospitalRepository,
                                  PatientRecordSharingOptOutRepository optOutRepository,
                                  StaffRepository staffRepository,
                                  TreatmentRelationshipResolver resolver) {
        this.hospitalRepository = hospitalRepository;
        this.optOutRepository = optOutRepository;
        this.staffRepository = staffRepository;
        this.resolver = resolver;
    }

    @Override
    @Transactional(readOnly = true)
    public RecordAccessDecision decide(UUID actorUserId, UUID patientId, UUID actingHospitalId) {
        String key = CACHE_PREFIX + actorUserId + ":" + patientId + ":" + actingHospitalId;
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object cached = attrs.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof RecordAccessDecision d) {
                return d;
            }
        }
        RecordAccessDecision decision = evaluate(actorUserId, patientId, actingHospitalId);
        if (attrs != null) {
            attrs.setAttribute(key, decision, RequestAttributes.SCOPE_REQUEST);
        }
        return decision;
    }

    private RecordAccessDecision evaluate(UUID actorUserId, UUID patientId, UUID hospitalId) {
        if (hospitalId == null || patientId == null) {
            return RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.HOSPITAL_UNKNOWN, null);
        }
        Optional<Hospital> hospital = hospitalRepository.findById(hospitalId);
        if (hospital.isEmpty()) {
            return RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.HOSPITAL_UNKNOWN, null);
        }
        Hospital h = hospital.get();
        RecordAccessPosture posture = h.getRecordAccessPosture() != null
            ? h.getRecordAccessPosture() : RecordAccessPosture.TREATMENT_PRESUMED;

        // A SCHEMA tenant's clinical tables live in their own schema: there is
        // nothing to read across, and the decision record says there must not be.
        if (h.getIsolationMode() == TenantIsolationMode.SCHEMA) {
            return RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.SCHEMA_ISOLATED_TENANT, posture);
        }
        if (posture != RecordAccessPosture.TREATMENT_PRESUMED) {
            return RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.HOSPITAL_REQUIRES_CONSENT, posture);
        }
        // Opt-out is checked before the relationship on purpose: a patient who
        // has withdrawn from sharing gets the same answer whether or not they
        // are on this hospital's schedule, so the refusal itself discloses
        // nothing about their movements.
        if (optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patientId)) {
            return RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.PATIENT_OPTED_OUT, posture);
        }
        boolean staffHere = actorUserId != null
            && staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)
                .map(s -> s.isActive())
                .orElse(false);
        if (!staffHere) {
            return RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.NOT_STAFF_AT_HOSPITAL, posture);
        }
        return resolver.resolve(patientId, hospitalId, actorUserId)
            .map(rel -> RecordAccessDecision.permitted(patientId, hospitalId, actorUserId, rel, posture))
            .orElseGet(() -> RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.NO_TREATMENT_RELATIONSHIP, posture));
    }
}
