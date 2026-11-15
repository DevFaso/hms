package com.example.hms.repository;

import com.example.hms.model.labor.FetalDeathEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** QA D1714: the fetal-death events of recorded deliveries. Every read is scoped by delivery or by hospital. */
@Repository
public interface FetalDeathEventRepository extends JpaRepository<FetalDeathEvent, UUID> {

    /** The live (not superseded) event of one baby, if any. */
    Optional<FetalDeathEvent> findFirstByDeliveryRecord_IdAndBirthOrderAndSupersededAtIsNull(UUID deliveryRecordId,
                                                                                            int birthOrder);

    /** Every event of a delivery, superseded ones included, in birth order then creation order. */
    List<FetalDeathEvent> findByDeliveryRecord_IdOrderByBirthOrderAscCreatedAtAsc(UUID deliveryRecordId);

    boolean existsByDeliveryRecord_Id(UUID deliveryRecordId);

    /** One hospital's event, for linking a death record to it (another hospital's reads as unknown). */
    Optional<FetalDeathEvent> findByIdAndHospital_Id(UUID id, UUID hospitalId);

    /** The register: one hospital's live events in a half-open period, oldest first. */
    @Query("SELECT e FROM FetalDeathEvent e WHERE e.hospital.id = :hospitalId AND e.supersededAt IS NULL "
        + "AND e.occurredAt >= :from AND e.occurredAt < :to ORDER BY e.occurredAt ASC")
    List<FetalDeathEvent> findRegister(@Param("hospitalId") UUID hospitalId,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    /**
     * One hospital's events among these ids, superseded ones included: a death record linked to any event of a baby
     * names that baby (delivery and birth order), whichever event of it is current.
     */
    @Query("SELECT e FROM FetalDeathEvent e WHERE e.hospital.id = :hospitalId AND e.id IN :ids")
    List<FetalDeathEvent> findAtHospitalByIdIn(@Param("hospitalId") UUID hospitalId,
                                               @Param("ids") Collection<UUID> ids);

    /**
     * The register's count-once identity: one hospital's live (not superseded) events of these deliveries, whatever
     * their date. A stillbirth death record of a baby that has one is counted through it, never again.
     */
    @Query("SELECT e FROM FetalDeathEvent e WHERE e.hospital.id = :hospitalId AND e.supersededAt IS NULL "
        + "AND e.deliveryRecord.id IN :deliveryIds")
    List<FetalDeathEvent> findLiveForDeliveries(@Param("hospitalId") UUID hospitalId,
                                                @Param("deliveryIds") Collection<UUID> deliveryIds);

    /**
     * The historical backfill: deliveries recorded not live before V293 that have no event at all (a superseded one
     * counts: a correction already happened, and nothing is recreated).
     */
    @Query("SELECT d.id FROM DeliveryRecord d WHERE d.liveBirth = false AND NOT EXISTS "
        + "(SELECT e.id FROM FetalDeathEvent e WHERE e.deliveryRecord = d)")
    List<UUID> findStillbirthDeliveriesWithoutEvent();
}
