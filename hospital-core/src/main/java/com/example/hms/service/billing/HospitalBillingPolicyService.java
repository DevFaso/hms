package com.example.hms.service.billing;

import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.HospitalBillingPolicy;
import com.example.hms.payload.dto.billing.HospitalBillingPolicyDTO;
import com.example.hms.repository.HospitalBillingPolicyRepository;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Policy rules 1 and 6 (provisional, V345): the billing and dispensing choices a hospital makes for itself, and the
 * one reader of them. A hospital with no row uses the defaults, which are the rules of the policy report: no
 * automatic consultation on a planned admission, and no renewal by the pharmacist.
 *
 * <p>Read by the hospital's own staff; changed only by a hospital admin on an assignment there (never the
 * super-admin alone). The pharmacist-renewal flag exists and stays off: nothing is built behind it, so turning it on
 * is refused rather than stored as a promise the product does not keep.
 */
@Service
@RequiredArgsConstructor
public class HospitalBillingPolicyService {

    private final HospitalBillingPolicyRepository policyRepository;
    private final RoleValidator roleValidator;

    /**
     * Rule 6: whether {@code hospitalId} bills a default consultation on a planned, day-case, newborn, labour or
     * transfer admission. False unless the hospital chose otherwise.
     */
    @Transactional(readOnly = true)
    public boolean autoConsultationOnPlannedAdmission(UUID hospitalId) {
        if (hospitalId == null) {
            return false;
        }
        return policyRepository.findByHospitalId(hospitalId)
                .map(HospitalBillingPolicy::isAutoConsultationOnPlannedAdmission)
                .orElse(false);
    }

    /** The acting hospital's policy, with the defaults where it never chose. */
    @Transactional(readOnly = true)
    public HospitalBillingPolicyDTO current() {
        UUID hospitalId = requireHospital();
        return toDto(policyRepository.findByHospitalId(hospitalId).orElse(null), hospitalId);
    }

    /** A hospital admin of the acting hospital changes its policy. A field left out keeps its value. */
    @Transactional
    public HospitalBillingPolicyDTO update(com.example.hms.payload.dto.billing.HospitalBillingPolicyUpdateDTO body) {
        UUID hospitalId = requireHospital();
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || !roleValidator.canSetHospitalBillingPolicy(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.policy.adminsOnly"));
        }
        if (body != null && Boolean.TRUE.equals(body.getPharmacistExceptionalRenewal())) {
            throw new FieldBusinessException("pharmacistExceptionalRenewal", "billing.policy.pharmacistRenewalUnavailable");
        }
        HospitalBillingPolicy policy = policyRepository.findByHospitalId(hospitalId)
                .orElseGet(() -> HospitalBillingPolicy.builder().hospitalId(hospitalId).build());
        if (body != null && body.getAutoConsultationOnPlannedAdmission() != null) {
            policy.setAutoConsultationOnPlannedAdmission(body.getAutoConsultationOnPlannedAdmission());
        }
        policy.setUpdatedBy(userId);
        return toDto(policyRepository.save(policy), hospitalId);
    }

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.policy.hospitalRequired"));
        }
        return hospitalId;
    }

    private HospitalBillingPolicyDTO toDto(HospitalBillingPolicy policy, UUID hospitalId) {
        UUID userId = roleValidator.getCurrentUserId();
        return HospitalBillingPolicyDTO.builder()
                .autoConsultationOnPlannedAdmission(policy != null && policy.isAutoConsultationOnPlannedAdmission())
                .pharmacistExceptionalRenewal(policy != null && policy.isPharmacistExceptionalRenewal())
                .updatedAt(policy != null ? policy.getUpdatedAt() : null)
                .canEdit(userId != null && roleValidator.canSetHospitalBillingPolicy(userId, hospitalId))
                .build();
    }
}
