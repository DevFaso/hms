package com.example.hms.repository;

import com.example.hms.enums.OrderReviewStatus;
import com.example.hms.enums.OrderReviewType;
import com.example.hms.model.OrderReviewHold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderReviewHoldRepository extends JpaRepository<OrderReviewHold, UUID> {

    /** The one question {@code OrderReviewHoldGuard} asks, at every use of an order. */
    boolean existsByOrderTypeAndOrderIdAndStatus(OrderReviewType orderType, UUID orderId, OrderReviewStatus status);

    /** One question for a whole screen: the standing rows are few, whatever the rows of the screen. */
    @Query("SELECT h.orderId FROM OrderReviewHold h WHERE h.orderType = :type AND h.status = :status AND h.hospitalId = :hospital")
    java.util.Set<UUID> findStandingAtHospital(@Param("type") OrderReviewType type, @Param("status") OrderReviewStatus status,
                                               @Param("hospital") UUID hospital);

    /** {@link #findStandingAtHospital} for a global view. */
    @Query("SELECT h.orderId FROM OrderReviewHold h WHERE h.orderType = :type AND h.status = :status")
    java.util.Set<UUID> findStanding(@Param("type") OrderReviewType type, @Param("status") OrderReviewStatus status);

    List<OrderReviewHold> findByPatientIdAndStatusIn(UUID patientId, java.util.Collection<OrderReviewStatus> statuses);

    List<OrderReviewHold> findByDisputeIdAndStatusIn(UUID disputeId, java.util.Collection<OrderReviewStatus> statuses);

    List<OrderReviewHold> findByOrderTypeAndOrderId(OrderReviewType orderType, UUID orderId);

    /** Locked: two decisions on one order take turns, and the second reads what the first did. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM OrderReviewHold h WHERE h.id = :id")
    Optional<OrderReviewHold> lockById(@Param("id") UUID id);

    /** What the chart shows at this hospital, standing ones first by the status order of the screen. */
    List<OrderReviewHold> findByPatientIdAndHospitalIdOrderByCreatedAtDesc(UUID patientId, UUID hospitalId);

    List<OrderReviewHold> findByOrderTypeAndOrderIdAndStatus(OrderReviewType orderType, UUID orderId, OrderReviewStatus status);

    /** Hospital is part of the question: a row at another hospital answers as missing. */
    Optional<OrderReviewHold> findByOrderTypeAndOrderIdAndStatusAndHospitalId(
        OrderReviewType orderType, UUID orderId, OrderReviewStatus status, UUID hospitalId);
}
