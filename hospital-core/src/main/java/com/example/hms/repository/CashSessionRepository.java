package com.example.hms.repository;

import com.example.hms.enums.CashTill;
import com.example.hms.model.CashSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Billing-money plan PR 10 (V309): cash sessions, always read within one hospital. */
public interface CashSessionRepository extends JpaRepository<CashSession, UUID> {

    /**
     * The cashier's session for a till and day, row-locked to the end of the transaction: a movement and the close
     * of the same session run one after the other, so nothing slips in while the close is counted.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT s FROM CashSession s
            WHERE s.hospitalId = :hospitalId AND s.cashierUserId = :cashierId
              AND s.till = :till AND s.businessDate = :day
           """)
    Optional<CashSession> findLockedForDay(@Param("hospitalId") UUID hospitalId,
                                           @Param("cashierId") UUID cashierId,
                                           @Param("till") CashTill till,
                                           @Param("day") LocalDate day);

    /** One session by id within its hospital, row-locked (close, reopen, review). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CashSession s WHERE s.id = :id AND s.hospitalId = :hospitalId")
    Optional<CashSession> findLockedByIdAndHospitalId(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId);

    Optional<CashSession> findByIdAndHospitalId(UUID id, UUID hospitalId);

    /** Every session of a hospital on one business day (the supervisor view, the day report). */
    List<CashSession> findByHospitalIdAndBusinessDateOrderByTillAscOpenedAtAsc(UUID hospitalId, LocalDate day);

    /** One cashier's sessions of a hospital on one business day. */
    List<CashSession> findByHospitalIdAndCashierUserIdAndBusinessDateOrderByTillAsc(UUID hospitalId, UUID cashierId,
                                                                                    LocalDate day);
}
