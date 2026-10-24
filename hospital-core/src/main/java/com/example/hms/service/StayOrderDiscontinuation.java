package com.example.hms.service;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.QueueClaimExitActor;
import com.example.hms.enums.QueueClaimReleaseReason;
import com.example.hms.enums.ReadyCancelReason;
import com.example.hms.model.Admission;
import com.example.hms.model.Prescription;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.pharmacy.PreparedFillVoider;
import com.example.hms.service.pharmacy.PrescriptionQueueClaimService;
import com.example.hms.service.pharmacy.partner.WithdrawnOrderPartnerHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * QA D757: a ward order ends with its stay. After a discharge the stay's
 * orders stayed SIGNED: they sat on the pharmacy queue, were verified and
 * dispensed (IV ceftriaxone for a patient at home) and printed on the
 * after-visit summary as medicines to take home, while the MAR had already
 * dropped them. Every discharge path (direct, and the approval flow) calls
 * this before the stay's encounter is closed: each live order of the stay
 * (the rule the MAR uses, {@link InpatientOrderEnd#orderOfStay}) that is not
 * marked take-home is DISCONTINUED with a reason, exactly as a prescriber's
 * withdrawal: its prepared fill voided, its queue claim released and its
 * partner offers withdrawn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StayOrderDiscontinuation {

    /** The statuses of an order that is still live: anything not yet withdrawn or fully handed over. */
    static final Set<PrescriptionStatus> LIVE_STATUSES = EnumSet.of(
        PrescriptionStatus.DRAFT,
        PrescriptionStatus.PENDING_SIGNATURE,
        PrescriptionStatus.SIGNED,
        PrescriptionStatus.TRANSMITTED,
        PrescriptionStatus.TRANSMISSION_FAILED,
        PrescriptionStatus.PENDING_CLARIFICATION,
        PrescriptionStatus.PENDING_STOCK,
        PrescriptionStatus.PARTIALLY_FILLED);

    private final PrescriptionRepository prescriptionRepository;
    private final PreparedFillVoider preparedFills;
    private final PrescriptionQueueClaimService queueClaims;
    private final WithdrawnOrderPartnerHandler withdrawnOrders;
    private final MessageSource messageSource;

    /**
     * Discontinues the live, not take-home orders of the stay. Returns the
     * orders it ended (for the caller's tests and audit); ids only are logged.
     */
    @Transactional
    public List<Prescription> discontinueStayOrders(Admission stay, UUID actorUserId) {
        if (stay == null || stay.getId() == null || stay.getPatient() == null || stay.getHospital() == null) {
            return List.of();
        }
        List<Prescription> candidates = prescriptionRepository.findByPatient_IdAndHospital_Id(
            stay.getPatient().getId(), stay.getHospital().getId());
        String reason = messageSource.getMessage("prescription.discontinuedAtDischarge", null,
            "Discontinued at discharge", LocaleContextHolder.getLocale());
        List<Prescription> ended = new ArrayList<>();
        for (Prescription rx : candidates) {
            if (!endsWithStay(rx, stay)) {
                continue;
            }
            rx.setStatus(PrescriptionStatus.DISCONTINUED);
            rx.setWithdrawalReason(reason);
            withdrawnOrders.withdrawPartnerOffers(rx);
            preparedFills.voidPreparedFill(rx, ReadyCancelReason.PRESCRIPTION_WITHDRAWN);
            queueClaims.releaseOnExit(rx, QueueClaimReleaseReason.WITHDRAWN, actorUserId, QueueClaimExitActor.OTHER);
            ended.add(rx);
        }
        if (!ended.isEmpty()) {
            prescriptionRepository.saveAll(ended);
            log.info("Discontinued {} ward order(s) of admission {} at discharge", ended.size(), stay.getId());
        }
        return ended;
    }

    /** A live order of this stay, not marked for home. */
    static boolean endsWithStay(Prescription rx, Admission stay) {
        return rx != null && !rx.isTakeHome()
            && rx.getStatus() != null && LIVE_STATUSES.contains(rx.getStatus())
            && InpatientOrderEnd.orderOfStay(rx, stay);
    }
}
