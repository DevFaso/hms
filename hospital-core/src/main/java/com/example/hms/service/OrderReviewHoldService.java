package com.example.hms.service;

import com.example.hms.model.DeathDispute;
import com.example.hms.model.DeathRecord;
import com.example.hms.payload.dto.review.OrderReviewDecisionDTO;
import com.example.hms.payload.dto.review.OrderReviewReplacementDTO;
import com.example.hms.payload.dto.review.OrderReviewRowDTO;

import java.util.List;
import java.util.UUID;

/**
 * The review of orders that existed when a recorded death was disputed (user ruling, round 36; NOT clinically
 * approved until the clinical lead signs it off). See {@code OrderReviewHoldGuard} for the rule that holds them.
 */
public interface OrderReviewHoldService {

    /**
     * In the death event's own transaction: holds the patient's outstanding orders at every hospital, each to be
     * reviewed at its own, and flags running treatment. Later correcting the death's date cannot evade this.
     */
    void snapshotAtDeath(DeathRecord record);

    /** In the dispute's own transaction: binds this hospital's standing rows to it, and holds what is new. */
    void snapshot(DeathDispute dispute);

    /** A relevant change after a reconfirmation invalidates it: the order is held again, the history kept. */
    void reviewAgainAfterChange(com.example.hms.enums.OrderReviewType type, UUID orderId);

    /** The chart's list at the acting hospital, standing or decided. */
    List<OrderReviewRowDTO> forPatient(UUID patientId);

    /** Reconfirmed unchanged: audited, against the version read. The role and step-up of signing that order. */
    OrderReviewRowDTO continueOrder(UUID holdId, OrderReviewDecisionDTO decision);

    /** Closes the held order and links the new one, written and signed the normal way. */
    OrderReviewRowDTO replaceOrder(UUID holdId, OrderReviewReplacementDTO replacement);

    /** Stops the order through its own stop path. */
    OrderReviewRowDTO stopOrder(UUID holdId, OrderReviewDecisionDTO decision);

    /** The treating clinician's assessment of a running treatment, which lifts its flag and changes nothing else. */
    OrderReviewRowDTO recordAssessment(UUID holdId, OrderReviewDecisionDTO decision);
}
