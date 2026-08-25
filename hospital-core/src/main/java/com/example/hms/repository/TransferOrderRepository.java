package com.example.hms.repository;

import com.example.hms.enums.TransferOrderStatus;
import com.example.hms.model.TransferOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** In-app transfer orders (Tier 2 item 30). */
@Repository
public interface TransferOrderRepository extends JpaRepository<TransferOrder, UUID> {

    /**
     * The transfer worklist: everything ordered and not yet carried out,
     * oldest first because that is the order a porter works through them.
     */
    @Query("SELECT t FROM TransferOrder t "
        + "WHERE t.hospital.id = :hospitalId AND t.status = :status "
        + "ORDER BY t.requestedAt ASC")
    List<TransferOrder> findByHospitalAndStatus(@Param("hospitalId") UUID hospitalId,
                                                @Param("status") TransferOrderStatus status);

    /**
     * The open order for an admission, if any.
     *
     * <p>A patient cannot be on their way to two places at once, which a
     * partial unique index enforces at the database. This is the readable
     * guard that produces a sensible message before the constraint fires.
     */
    @Query("SELECT t FROM TransferOrder t "
        + "WHERE t.admission.id = :admissionId AND t.status = 'REQUESTED'")
    Optional<TransferOrder> findPendingForAdmission(@Param("admissionId") UUID admissionId);

    /** The open order holding a destination bed, if any. */
    @Query("SELECT t FROM TransferOrder t "
        + "WHERE t.toBed.id = :bedId AND t.status = 'REQUESTED'")
    Optional<TransferOrder> findPendingForDestinationBed(@Param("bedId") UUID bedId);

    /** Where this patient has been moved, newest first. */
    @Query("SELECT t FROM TransferOrder t "
        + "WHERE t.admission.id = :admissionId ORDER BY t.requestedAt DESC")
    List<TransferOrder> findHistoryForAdmission(@Param("admissionId") UUID admissionId);
}
